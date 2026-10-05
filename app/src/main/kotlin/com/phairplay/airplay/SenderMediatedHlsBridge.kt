package com.phairplay.airplay

import com.phairplay.airplay.handshake.FcupCodec
import com.phairplay.airplay.handshake.HlsPlaylistCodec
import com.phairplay.util.Logger
import java.net.URI
import java.util.Locale

/**
 * Session-scoped transport for sender-mediated HLS: the sender fetches the playlists, this bridge
 * turns what they reference into URIs the player can open, and reads them back on demand.
 *
 * WHY THIS EXISTS: the YouTube app's AirPlay video does not send a URL the receiver can open. It
 * sends an internal location (`mlhls://localhost/…/master.m3u8`) whose playlists only exist behind
 * the sender's own authenticated session, so the receiver asks the sender for them over the PTTH
 * reverse channel ([ReverseHttpChannel]) and serves them to the player under a private scheme
 * (`hearth-hls://<session>/…`), which [HearthUrlVideoDataSource] resolves back into this class.
 *
 * WHAT STAYS WHERE:
 *  - **Playlists** (master and media) are fetched through the sender, rewritten so every reference
 *    is either an absolute URL the player fetches itself (segment URLs keep their signed query
 *    bytes) or another `hearth-hls` URI, and cached only when they cannot change: the master for
 *    the session, media playlists never (a live playlist must be re-read for every refresh).
 *  - **Segments** referenced by relative or sender-internal URIs are the one thing the reference
 *    implementations do not fetch over FCUP, but they are the only channel that can fetch them, so
 *    they are fetched the same way, bounded, cached, and reported in the trace as an extension.
 *  - **Session identity** is the AirPlay session token: the scheme carries its id, [open] refuses
 *    any URI that names a different session, and [close] fails every future read.
 */
internal class SenderMediatedHlsBridge(
    val sessionToken: SessionToken,
    /** The `Content-Location` of the `/play` that started this session. */
    val contentLocation: String,
    private val channel: ReverseHttpChannel,
    private val maxPlaylistBytes: Int = DEFAULT_MAX_PLAYLIST_BYTES,
    private val maxSegmentBytes: Int = DEFAULT_MAX_SEGMENT_BYTES,
    private val cacheBytes: Int = DEFAULT_CACHE_BYTES,
    /** How many distinct media playlists / media items one session may address, bounded on purpose. */
    private val maxPlaylistReferences: Int = MAX_PLAYLIST_REFERENCES,
    private val maxItemReferences: Int = MAX_ITEM_REFERENCES,
) : UrlVideoSessionSource {

    override val sessionId: String get() = sessionToken.sessionId

    /**
     * The sender session id this bridge echoes, and the id every `POST /action` must carry.
     *
     * Upstream matches the two strictly (`http_handler_action` refuses a changed
     * `X-Apple-Session-ID`), and so does Hearth: an answer for another playback session is never
     * applied to this one.
     */
    val channelSessionId: String get() = channel.senderSessionId

    private val lock = Any()

    /** Player-facing playlists, in the order the sender's master listed them. */
    private val playlistTable = UriTable(sessionId, PLAYLIST_PREFIX, ".m3u8", maxPlaylistReferences)

    /**
     * Everything else the player must read through the reverse channel (segments, init maps, keys).
     * Bounded well above a live window's worth of segments: a handle must keep resolving to the same
     * sender URI for as long as the player may still open it, so entries are never recycled — the
     * session reports an explicit failure instead of quietly serving the wrong bytes.
     */
    private val itemTable = UriTable(sessionId, ITEM_PREFIX, null, maxItemReferences)

    private val cache = BoundedBodyCache(cacheBytes)

    @Volatile
    private var closed = false

    @Volatile
    private var masterBytes: ByteArray? = null

    /** Why the session could not serve media, once that is known. URL-free and shown to the user. */
    @Volatile
    private var failure: String? = null

    private val masterFetches = java.util.concurrent.atomic.AtomicInteger(0)
    private val playlistFetches = java.util.concurrent.atomic.AtomicInteger(0)
    private val itemFetches = java.util.concurrent.atomic.AtomicInteger(0)

    /** The URI the player must open to start this session. */
    val playerUri: String get() = "$SCHEME://$sessionId/$MASTER_PATH"

    /**
     * Verifies the session can still fetch and prepares the master playlist.
     *
     * @return true when the master is available; false leaves [failureReason] set
     */
    fun start(): Boolean {
        if (closed) {
            failure = "session is closed"
            return false
        }
        if (!channel.isWritable) {
            failure = "the reverse channel is not open"
            return false
        }
        return runCatching { masterPlaylist() }
            .onFailure { failure = it.message ?: "the sender did not answer" }
            .isSuccess
    }

    /**
     * Fetches the master playlist ahead of the player, so a failure is visible before the player
     * starts and the player's first read is served from memory. Never throws.
     */
    fun warmUp() {
        runCatching { masterPlaylist() }
            .onFailure {
                failure = it.message ?: "the sender did not answer"
                Logger.w("Sender-mediated HLS: master playlist unavailable (${it.javaClass.simpleName})")
            }
    }

    /** Why this session could not serve media; null while it is healthy. */
    override fun failureReason(): String? = failure

    /** One-line, URL-free summary for the connection log. */
    fun describe(): String = buildString {
        append("session=$sessionId fcup[${channel.stats()}] ")
        append("master=${if (masterBytes != null) "loaded" else "pending"} ")
        append("playlists=${playlistTable.size} items=${itemTable.size} ")
        append("reads=")
        append("master:${masterFetches.get()} playlist:${playlistFetches.get()} item:${itemFetches.get()}")
    }

    /**
     * Hands one decoded `POST /action` body to the request it answers.
     *
     * The reply is matched by request id **and** URL inside [ReverseHttpChannel]: an answer whose URL
     * differs from the request it claims to answer is ignored rather than delivered as the wrong
     * bytes. `playlistInsert`/`playlistRemove` (ad-break bookkeeping) are acknowledged — the sender
     * must not be left waiting — but deliberately not applied: acting on a mid-stream playlist edit
     * without a real YT-EXT-GAP/`#EXT-X-DISCONTINUITY` contract would corrupt the window, and the
     * playlists the sender already gave us are what the player is playing.
     */
    fun deliver(actionBody: ByteArray): ReverseHttpChannel.Delivery {
        if (closed) return ReverseHttpChannel.Delivery(false, 409, "session is closed")
        return when (val parsed = FcupCodec.parseAction(actionBody)) {
            is FcupCodec.ActionParse.Response -> {
                val response = parsed.response
                val status = response.statusCode
                AirPlayTrace.record(
                    message = "Sender-mediated HLS: answer for request ${response.requestId ?: -1L} " +
                        "(status=${status ?: 0}, ${response.data.size} B)",
                    sessionId = sessionId,
                    role = ROLE,
                )
                channel.deliver(
                    requestId = response.requestId,
                    url = response.url,
                    statusCode = status,
                    data = response.data,
                    senderSessionId = channel.senderSessionId,
                )
            }
            is FcupCodec.ActionParse.Playlist -> {
                AirPlayTrace.record(
                    message = "Sender-mediated HLS: ${parsed.type.take(32)} acknowledged (not applied)",
                    sessionId = sessionId,
                    role = ROLE,
                )
                ReverseHttpChannel.Delivery(true, 200, "playlist action acknowledged")
            }
            is FcupCodec.ActionParse.Unsupported ->
                ReverseHttpChannel.Delivery(false, 400, "unsupported action type")
            is FcupCodec.ActionParse.Invalid ->
                // The reason is short and URL-free by construction.
                ReverseHttpChannel.Delivery(false, 400, "invalid action body: ${parsed.reason}")
        }
    }

    // ─── UrlVideoSessionSource ──────────────────────────────────────────────────────────────────

    /**
     * Reads one player-facing `hearth-hls://` URI.
     *
     * @throws HlsBridgeException when the session is closed, the URI belongs to another session, a
     *   playlist cannot be fetched or rewritten, or a fetched body exceeds its limit
     */
    override fun open(uri: String): ByteArray {
        if (closed) {
            failure = failure ?: "session is closed"
            throw HlsBridgeException(failure!!)
        }
        val parsed = runCatching { URI(uri) }.getOrNull() ?: throw HlsBridgeException("unparsable URI")
        if (!parsed.scheme.equals(SCHEME, true)) throw HlsBridgeException("not a sender-mediated URI")
        if (parsed.authority != sessionId) throw HlsBridgeException("URI belongs to another session")
        val path = parsed.path.orEmpty().removePrefix("/")
        return try {
            when {
                path == MASTER_PATH -> masterPlaylist()
                path.startsWith(PLAYLIST_PREFIX) -> mediaPlaylist(path.removePrefix(PLAYLIST_PREFIX).substringBefore('.'))
                path.startsWith(ITEM_PREFIX) -> item(path.removePrefix(ITEM_PREFIX).substringBefore('.'))
                else -> throw HlsBridgeException("unknown sender-mediated path")
            }
        } catch (e: HlsBridgeException) {
            // Every failed read leaves a reason for the player's failure report — the player itself
            // only sees a failed read, and "why" is the difference between a diagnosable session and
            // a black screen with no explanation.
            failure = failure ?: e.message
            throw e
        }
    }

    private fun masterPlaylist(): ByteArray {
        masterBytes?.let { return it }
        val original = channel.fetch(contentLocation, "master playlist")
        if (original.size > maxPlaylistBytes) throw HlsBridgeException("master playlist exceeds the size limit")
        val text = original.toString(Charsets.UTF_8)
        var tableFull = false
        val rewrite = HlsPlaylistCodec.rewriteMaster(text, contentLocation) { resolved ->
            playlistTable.uriFor(resolved) ?: run { tableFull = true; null }
        }
        if (tableFull) {
            failure = "too many media playlists in this session"
            throw HlsBridgeException(failure!!)
        }
        val rewritten = when (rewrite) {
            is HlsPlaylistCodec.Rewrite.Failed -> {
                failure = "master playlist: ${rewrite.reason}"
                throw HlsBridgeException(failure!!)
            }
            is HlsPlaylistCodec.Rewrite.Ok -> rewrite
        }
        val bytes = rewritten.text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxPlaylistBytes) throw HlsBridgeException("rewritten master playlist exceeds the size limit")
        val master = HlsPlaylistCodec.parseMaster(text)
        masterFetches.incrementAndGet()
        AirPlayTrace.record(
            message = "Sender-mediated HLS: master loaded (variants=${master.variants.size} " +
                "renditions=${master.renditions.size} shape=${master.shape} " +
                "videoCodecs=${master.videoCodecFamilies().joinToString(",").ifBlank { "unknown" }} " +
                "bridged=${rewritten.bridged} direct=${rewritten.direct})",
            sessionId = sessionId,
            role = ROLE,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        synchronized(lock) { masterBytes = bytes }
        return bytes
    }

    private fun mediaPlaylist(handle: String): ByteArray {
        val index = handle.toIntOrNull() ?: throw HlsBridgeException("bad media playlist handle")
        val senderUri = playlistTable.uriAt(index) ?: throw HlsBridgeException("unknown media playlist handle")
        // Always re-read: live playlists change between refreshes, and a stale window would freeze
        // the picture or make a seek land on a segment the sender has already dropped.
        val original = channel.fetch(senderUri, "media playlist")
        if (original.size > maxPlaylistBytes) throw HlsBridgeException("media playlist exceeds the size limit")
        val text = original.toString(Charsets.UTF_8)
        val parsed = HlsPlaylistCodec.parseMedia(text)
        if (parsed.isSampleAes) throw HlsBridgeException("media playlist requires SAMPLE-AES protection")
        var tableFull = false
        val rewrite = HlsPlaylistCodec.rewriteMedia(text, senderUri) { resolved ->
            when {
                HlsPlaylistCodec.isDirectlyFetchable(resolved) -> resolved
                else -> itemTable.uriFor(resolved) ?: run { tableFull = true; null }
            }
        }
        if (tableFull) {
            failure = "too many media items in this session"
            throw HlsBridgeException(failure!!)
        }
        val rewritten = when (rewrite) {
            is HlsPlaylistCodec.Rewrite.Failed -> {
                failure = "media playlist: ${rewrite.reason}"
                throw HlsBridgeException(failure!!)
            }
            is HlsPlaylistCodec.Rewrite.Ok -> rewrite
        }
        val bytes = rewritten.text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxPlaylistBytes) throw HlsBridgeException("rewritten media playlist exceeds the size limit")
        playlistFetches.incrementAndGet()
        AirPlayTrace.record(
            message = "Sender-mediated HLS: media playlist loaded (segments=${parsed.segmentCount} " +
                "live=${parsed.isLive} target=${parsed.targetDurationSec ?: 0}s " +
                "byteRanges=${parsed.hasByteRanges} map=${parsed.initMapUri != null} " +
                "discontinuities=${parsed.discontinuityCount} " +
                "bridged=${rewritten.bridged} direct=${rewritten.direct}" +
                (if (rewritten.condensedExpanded) " condensed=expanded" else "") + ")",
            sessionId = sessionId,
            role = ROLE,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        return bytes
    }

    private fun item(handle: String): ByteArray {
        val index = handle.toIntOrNull() ?: throw HlsBridgeException("bad media handle")
        val senderUri = itemTable.uriAt(index) ?: throw HlsBridgeException("unknown media handle")
        cache.get(senderUri)?.let { return it }
        val body = channel.fetch(senderUri, "segment")
        if (body.size > maxSegmentBytes) throw HlsBridgeException("media item exceeds the size limit")
        itemFetches.incrementAndGet()
        cache.put(senderUri, body)
        return body
    }

    /** Releases the session: refusing new reads and answering every waiting one with a reason. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            masterBytes = null
            cache.clear()
        }
        channel.close("session ended")
    }

    /** Resolves references to stable player-facing URIs, bounded in count. */
    private class UriTable(
        private val sessionId: String,
        private val prefix: String,
        /** Fixed suffix (`.m3u8`), or null to preserve a short extension from the sender's URI. */
        private val fixedSuffix: String?,
        private val maxEntries: Int,
    ) {
        private val lock = Any()
        private val uris = ArrayList<String>()
        private val ids = HashMap<String, Int>()

        val size: Int get() = synchronized(lock) { uris.size }

        /** The player-facing URI for [uri], or null when this session's table is full. */
        fun uriFor(uri: String): String? = synchronized(lock) {
            ids[uri]?.let { return build(it, uri) }
            if (uris.size >= maxEntries) return null
            uris += uri
            ids[uri] = uris.size - 1
            build(uris.size - 1, uri)
        }

        fun uriAt(index: Int): String? = synchronized(lock) { uris.getOrNull(index) }

        private fun build(index: Int, original: String): String {
            val suffix = fixedSuffix ?: run {
                // Preserve a short alphanumeric extension so the player can pick its extractor
                // (`.ts`, `.m4s`, `.aac`); the handle itself stays opaque.
                val candidate = runCatching { URI(original).path }.getOrNull()
                    .orEmpty().substringAfterLast('/', "").substringAfter('.', "")
                if (candidate.isNotEmpty() && candidate.length <= 8 && candidate.all { it.isLetterOrDigit() }) {
                    ".$candidate"
                } else {
                    ""
                }
            }
            return "$SCHEME://$sessionId/$prefix$index$suffix"
        }
    }

    /** Small LRU over fetched bodies; only immutable media items are cached, never playlists. */
    private class BoundedBodyCache(private val maxBytes: Int) {
        private val lock = Any()
        private val entries = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
                totalBytes > maxBytes
        }
        private var totalBytes = 0

        fun get(key: String): ByteArray? = synchronized(lock) { entries[key] }

        fun put(key: String, value: ByteArray) {
            if (value.size > maxBytes) return
            synchronized(lock) {
                entries.put(key, value)?.let { totalBytes -= it.size }
                totalBytes += value.size
            }
        }

        fun clear() = synchronized(lock) {
            entries.clear()
            totalBytes = 0
        }
    }

    override fun toString(): String = "SenderMediatedHlsBridge(${describe()})"

    companion object {
        /**
         * Private scheme for player-facing sender-mediated URIs. Deliberately not `http(s)`: the
         * player must never be able to fetch these directly, and nothing outside this process can
         * serve them.
         */
        const val SCHEME = "hearth-hls"
        const val MASTER_PATH = "master.m3u8"
        const val PLAYLIST_PREFIX = "playlist/"
        const val ITEM_PREFIX = "item/"

        const val ROLE = "SENDER_HLS"

        const val DEFAULT_MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_SEGMENT_BYTES = 32 * 1024 * 1024
        const val DEFAULT_CACHE_BYTES = 8 * 1024 * 1024

        /** A master playlist never legitimately names this many variants/renditions. */
        const val MAX_PLAYLIST_REFERENCES = 512

        /**
         * Live windows advance: at six seconds per segment this covers many hours of playback
         * before a session has to state that it can no longer address new segments.
         */
        const val MAX_ITEM_REFERENCES = 8192

        /** True for a `Content-Location` this bridge can serve (the sender's HLS master marker). */
        fun isMasterPlaylistLocation(location: String): Boolean {
            val path = runCatching { URI(location).path }.getOrNull() ?: return false
            val lowered = path.lowercase(Locale.US)
            return lowered.endsWith("/$MASTER_PATH") || lowered == "/$MASTER_PATH"
        }
    }
}
