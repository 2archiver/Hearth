package com.phairplay.cast.bridge

import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * CastReceiverApp — the Cast V2 protocol logic: what to answer when a sender says
 * `CONNECT`, `GET_STATUS`, `LAUNCH`, `LOAD`, `PLAY`, `PAUSE`, `SEEK`, `STOP`, `QUEUE_*`.
 *
 * WHY: PhairPlay's Google Cast support used to be a thin wrapper around Google's Cast
 * Connect SDK, which refuses to start without a Cast Application ID issued by Google after
 * registering the app in the Cast SDK Developer Console. Without that ID the Cast card in
 * PhairPlay could only ever show an error. This class speaks the wire protocol a Chromecast
 * speaks, so "cast a video" works without any registration: the sender discovers us over
 * mDNS (or SSDP for DIAL senders), opens the TLS channel on 8009 and sends `LOAD <url>`,
 * which we hand to [CastMediaPlayer].
 *
 * Deliberately permissive about the requested App ID: a real Chromecast only launches IDs
 * registered to it, but accepting any ID is what lets arbitrary sender apps (VLC, Plex,
 * Chrome, photo and file apps) cast to us. We echo the ID back in RECEIVER_STATUS, which is
 * all a sender checks.
 *
 * WHAT 1.5.0 CHANGED — every item here was a way for a sender to hang or give up:
 *  - **Replies go to the sender that asked.** [replyTo] used to blast every reply to every
 *    connected sink, so a second phone was bombarded with statuses meant for the first.
 *  - **Failures are answered, not swallowed.** A `LOAD` with no `contentId`, or a media command
 *    with no session, now gets `LOAD_FAILED` / `INVALID_REQUEST` instead of silence — a sender
 *    waiting on a reply it will never get shows "connecting…" forever.
 *  - **Queue commands are implemented** (`QUEUE_LOAD`, `QUEUE_NEXT`, `QUEUE_PREV`,
 *    `QUEUE_GET_ITEMS`, …). Senders that build a playlist rather than loading a single URL
 *    previously got nothing back at all.
 *  - **Playback state is broadcast periodically.** The sender's progress bar is driven by
 *    `MEDIA_STATUS`, and it used to only arrive on a state change, so the scrubber froze.
 *
 * Pure protocol logic — no sockets, no Android framework beyond [CastPlayback] — so it
 * can be unit tested with a fake [CastSink].
 */
internal class CastReceiverApp(
    private val displayName: String,
    private val player: CastPlayback,
    private val onVolumeChanged: (level: Double, muted: Boolean) -> Unit = { _, _ -> },
    /** Called with true when a sender launches an app and false when the session ends. */
    private val onSessionChanged: (Boolean) -> Unit = {},
    /**
     * Called when a sender talks on a namespace we cannot emulate (YouTube's `…youtube.mdx`,
     * Spotify's, Netflix's). Those apps launch their own receiver and then speak a private
     * protocol; no generic receiver can answer. We say so instead of pretending.
     */
    private val onUnsupportedNamespace: (String) -> Unit = {}
) {

    /** A live Cast application session, mirroring what RECEIVER_STATUS reports. */
    private data class CastSession(
        val appId: String,
        val displayName: String,
        val sessionId: String,
        val transportId: String
    )

    /** One entry of a sender-built queue (`QUEUE_LOAD` items). */
    private class QueueItem(
        val itemId: Int,
        val media: JSONObject,
        val autoplay: Boolean
    )

    private val lock = Any()

    /** Every connected sender channel, so RECEIVER_STATUS can be broadcast to all of them. */
    private val sinks = LinkedHashSet<CastSink>()

    @Volatile private var session: CastSession? = null
    @Volatile private var volumeLevel = 1.0
    @Volatile private var volumeMuted = false
    @Volatile private var mediaSessionId = 1
    @Volatile private var contentId: String? = null
    @Volatile private var contentType: String = "video/mp4"
    @Volatile private var streamType: String = "BUFFERED"
    @Volatile private var metadataJson: String? = null
    @Volatile private var playbackRate = 1.0
    @Volatile private var repeatMode = "REPEAT_OFF"
    private var sessionCounter = 0

    // ─── Queue state (sender-built playlists) ────────────────────────────────
    // Own lock rather than [lock]: queue work calls into the player and then replies, and
    // taking [lock] (which guards [sinks]) across those would risk nesting inside send.
    private val queueLock = Any()
    private val queue = ArrayList<QueueItem>()
    private var queueIndex = -1
    private var nextItemId = 1

    /**
     * The item id reported by MEDIA_STATUS, kept as a plain volatile because mediaStatus() is
     * also reached from the player's own status timer — reading it must not need [queueLock].
     */
    @Volatile private var currentItemIdValue = 1

    /** Namespaces we have already reported as unsupported, so the notice is logged once. */
    private val reportedUnsupported = LinkedHashSet<String>()

    /** True while a sender has an application session open. */
    fun hasSession(): Boolean = session != null

    /** Registers a sender channel. */
    fun addSink(sink: CastSink) {
        synchronized(lock) { sinks.add(sink) }
    }

    /** Unregisters a sender channel. */
    fun removeSink(sink: CastSink) {
        synchronized(lock) { sinks.remove(sink) }
    }

    /**
     * Routes one decoded [message].
     *
     * Never throws — a sender we do not understand must not take the bridge down, so an
     * unparseable body is logged and dropped.
     *
     * @param from the connection the message arrived on, so replies go back to that sender
     *             only. Pass null (the default) to broadcast instead.
     */
    fun handle(message: CastMessage, from: CastSink? = null) {
        when (message.namespace) {
            NS_CONNECTION -> handleConnection(message, from)
            NS_HEARTBEAT -> handleHeartbeat(message, from)
            NS_RECEIVER -> handleReceiver(message, from)
            NS_MEDIA -> handleMedia(message, from)
            else -> {
                Logger.d("Cast: ignoring namespace ${message.namespace}")
                if (synchronized(lock) { reportedUnsupported.add(message.namespace) }) {
                    // A sender app with its own registered receiver has got as far as launching
                    // here and is now waiting on a private channel we cannot speak. Say so once.
                    val appId = session?.appId ?: "(no session)"
                    val notice = "\"$appId\" is using a private Cast channel " +
                        "(${message.namespace}) that only its own receiver understands. " +
                        "PhairPlay cannot play it — use Screen Mirroring for this app."
                    Logger.w("Cast: unsupported namespace ${message.namespace} from app $appId")
                    onUnsupportedNamespace(notice)
                }
            }
        }
    }

    // ─── Namespaces ──────────────────────────────────────────────────────────

    private fun handleConnection(message: CastMessage, from: CastSink?) {
        val type = message.payloadUtf8?.messageType() ?: return
        when (type) {
            "CONNECT" -> {
                Logger.d("Cast: CONNECT from ${message.sourceId} → ${message.destinationId}")
                // Real receivers acknowledge a CONNECT; senders that do not wait for one
                // simply ignore this.
                replyTo(from, message.sourceId, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
            }

            "CLOSE" -> Logger.d("Cast: CLOSE from ${message.sourceId}")
            else -> Logger.d("Cast: unhandled connection message type=$type")
        }
    }

    private fun handleHeartbeat(message: CastMessage, from: CastSink?) {
        if (message.payloadUtf8?.messageType() == "PING") {
            replyTo(from, message.sourceId, NS_HEARTBEAT, JSONObject().put("type", "PONG"))
        }
    }

    private fun handleReceiver(message: CastMessage, from: CastSink?) {
        val body = message.payloadUtf8?.toJsonObject() ?: return
        val requestId = body.optInt("requestId", 0)
        val sender = message.sourceId

        when (body.optString("type")) {
            "GET_STATUS" -> {
                Logger.d("Cast: GET_STATUS from $sender")
                replyTo(from, sender, NS_RECEIVER, receiverStatus(requestId))
            }

            "LAUNCH" -> {
                val appId = body.optString("appId").ifBlank { DEFAULT_APP_ID }
                Logger.i("Cast: LAUNCH appId=$appId from $sender")
                val opened = openSession(appId)
                if (opened) {
                    player.stop()
                }
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "STOP" -> {
                Logger.i("Cast: STOP session from $sender")
                closeSession()
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "SET_VOLUME" -> {
                val volume = body.optJSONObject("volume")
                if (volume != null) {
                    if (volume.has("level")) {
                        volumeLevel = volume.optDouble("level", volumeLevel).coerceIn(0.0, 1.0)
                    }
                    if (volume.has("muted")) {
                        volumeMuted = volume.optBoolean("muted", volumeMuted)
                    }
                    onVolumeChanged(volumeLevel, volumeMuted)
                }
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "GET_APP_AVAILABILITY" -> {
                val ids = body.optJSONArray("appId")
                val availability = JSONObject()
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        availability.put(ids.optString(i, ""), "APP_AVAILABLE")
                    }
                }
                replyTo(
                    from,
                    sender,
                    NS_RECEIVER,
                    JSONObject()
                        .put("type", "GET_APP_AVAILABILITY")
                        .put("requestId", requestId)
                        .put("availability", availability)
                )
            }

            else -> Logger.d("Cast: unhandled receiver message type=${body.optString("type")}")
        }
    }

    private fun handleMedia(message: CastMessage, from: CastSink?) {
        val active = session
        val body = message.payloadUtf8?.toJsonObject() ?: return
        val requestId = body.optInt("requestId", 0)
        val sender = message.sourceId
        val type = body.optString("type")

        // Queue commands are answered even with no media loaded: a sender that has built a
        // queue is waiting on a reply, and silence reads as "the TV stopped responding".
        if (type.startsWith("QUEUE_")) {
            handleQueue(body, from, sender, requestId)
            return
        }

        if (active == null) {
            Logger.w("Cast: media $type with no running session — reporting INVALID_REQUEST")
            replyTo(from, sender, NS_MEDIA, invalidRequest(requestId, "INVALID_MEDIA_SESSION_ID"))
            return
        }
        if (message.destinationId != active.transportId && message.destinationId != RECEIVER_ID) {
            Logger.d("Cast: media message for ${message.destinationId}, session is ${active.transportId}")
        }

        when (type) {
            "LOAD" -> {
                val media = body.optJSONObject("media")
                val url = media?.optString("contentId").orEmpty()
                if (url.isBlank()) {
                    Logger.e("Cast: LOAD without a contentId — replying LOAD_FAILED")
                    replyTo(
                        from, sender, NS_MEDIA,
                        JSONObject()
                            .put("type", "LOAD_FAILED")
                            .put("requestId", requestId)
                            .put("detailedErrorCode", DETAILED_ERROR_MEDIA_INVALID)
                    )
                    return
                }
                // A single LOAD replaces whatever queue was there before.
                synchronized(queueLock) {
                    queue.clear()
                    queueIndex = -1
                    nextItemId = 1
                }
                currentItemIdValue = 1

                contentId = url
                contentType = media?.optString("contentType").orEmpty().ifBlank { "video/mp4" }
                streamType = media?.optString("streamType").orEmpty().ifBlank { "BUFFERED" }
                metadataJson = media?.optJSONObject("metadata")?.toString()
                mediaSessionId++
                StreamStats.castContentId = url
                val autoplay = body.optBoolean("autoplay", true)
                val start = body.optDouble("currentTime", 0.0).coerceAtLeast(0.0)
                playbackRate = body.optDouble("playbackRate", 1.0).let { if (it <= 0.0) 1.0 else it }
                Logger.i("Cast: LOAD $url (type=$contentType start=${start}s autoplay=$autoplay)")
                player.load(url, start, autoplay)
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "PLAY" -> {
                Logger.d("Cast: PLAY")
                player.play()
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "PAUSE" -> {
                Logger.d("Cast: PAUSE")
                player.pause()
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "STOP" -> {
                Logger.d("Cast: media STOP")
                player.stop()
                contentId = null
                StreamStats.castContentId = ""
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "SEEK" -> {
                val position = body.optDouble("currentTime", 0.0)
                Logger.d("Cast: SEEK to ${position}s")
                player.seek(position)
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "GET_STATUS" -> replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))

            "SET_VOLUME" -> {
                val volume = body.optJSONObject("volume")
                if (volume != null) {
                    if (volume.has("level")) {
                        volumeLevel = volume.optDouble("level", volumeLevel).coerceIn(0.0, 1.0)
                    }
                    if (volume.has("muted")) {
                        volumeMuted = volume.optBoolean("muted", volumeMuted)
                    }
                    onVolumeChanged(volumeLevel, volumeMuted)
                }
                broadcast(NS_RECEIVER, receiverStatus(requestId))
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            "SET_PLAYBACK_RATE", "PRECACHE", "PRELOAD", "EDIT_TRACKS_INFO" -> {
                // Accepted and reported back: these adjust playback we already support
                // (rate) or pre-fetch state we do not (precache/preload/tracks). Answering is
                // what matters — a sender times out on silence, not on a no-op.
                if (type == "SET_PLAYBACK_RATE") {
                    playbackRate = body.optDouble("playbackRate", playbackRate)
                        .let { if (it <= 0.0) 1.0 else it }
                }
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
            }

            else -> {
                Logger.d("Cast: unhandled media message type=$type")
                replyTo(from, sender, NS_MEDIA, invalidRequest(requestId, "INVALID_COMMAND"))
            }
        }
    }

    // ─── Queue ───────────────────────────────────────────────────────────────

    private fun handleQueue(
        body: JSONObject,
        from: CastSink?,
        sender: String,
        requestId: Int
    ) {
        // The queue lives on one lock for the whole command. It must never be taken while the
        // player's own lock is held: the player's status timer calls back into mediaStatus()
        // from inside its monitor, so taking them in the opposite order here would deadlock.
        synchronized(queueLock) {
            handleQueueLocked(body, from, sender, requestId)
        }
    }

    private fun handleQueueLocked(
        body: JSONObject,
        from: CastSink?,
        sender: String,
        requestId: Int
    ) {
        when (body.optString("type")) {
            "QUEUE_LOAD" -> {
                val items = body.optJSONArray("items")
                queue.clear()
                nextItemId = 1
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val entry = items.optJSONObject(i) ?: continue
                        val media = entry.optJSONObject("media") ?: continue
                        queue += QueueItem(nextItemId++, media, entry.optBoolean("autoplay", true))
                    }
                }
                repeatMode = body.optString("repeatMode").ifBlank { repeatMode }
                if (queue.isEmpty()) {
                    Logger.w("Cast: QUEUE_LOAD with no usable items")
                    replyTo(from, sender, NS_MEDIA, invalidRequest(requestId, "INVALID_PARAMS"))
                    return
                }
                val startIndex = body.optInt("startIndex", 0).coerceIn(0, queue.size - 1)
                val start = body.optDouble("currentTime", 0.0).coerceAtLeast(0.0)
                Logger.i("Cast: QUEUE_LOAD ${queue.size} item(s), start at #$startIndex")
                loadQueueItem(startIndex, start, autoplay = body.optBoolean("autoplay", true))
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
            }

            "QUEUE_NEXT", "QUEUE_PREV" -> {
                if (queue.isEmpty()) {
                    replyTo(from, sender, NS_MEDIA, invalidRequest(requestId, "INVALID_COMMAND"))
                    return
                }
                val jump = body.optInt("jump", 1)
                val step = if (body.optString("type") == "QUEUE_NEXT") jump else -jump
                val target = (queueIndex + step).coerceIn(0, queue.size - 1)
                Logger.d("Cast: ${body.optString("type")} → #$target")
                loadQueueItem(target, 0.0, autoplay = true)
                replyTo(from, sender, NS_MEDIA, mediaStatus(requestId))
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
            }

            "QUEUE_GET_ITEMS", "QUEUE_GET_ITEM_IDS" -> {
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
                replyTo(from, sender, NS_MEDIA, queueItems(requestId))
            }

            "QUEUE_INSERT" -> {
                val items = body.optJSONArray("items")
                var insertBefore = body.optInt("insertBefore", -1)
                if (insertBefore < 0 || insertBefore > queue.size) insertBefore = queue.size
                var added = 0
                if (items != null) {
                    val batch = ArrayList<QueueItem>()
                    for (i in 0 until items.length()) {
                        val entry = items.optJSONObject(i) ?: continue
                        val media = entry.optJSONObject("media") ?: continue
                        batch += QueueItem(nextItemId++, media, entry.optBoolean("autoplay", true))
                    }
                    queue.addAll(insertBefore, batch)
                    added = batch.size
                }
                Logger.d("Cast: QUEUE_INSERT +$added item(s)")
                replyTo(from, sender, NS_MEDIA, queueChange(requestId, "INSERT", insertBefore))
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
            }

            "QUEUE_REMOVE" -> {
                val ids = body.optJSONArray("itemIds")
                var removed = 0
                if (ids != null) {
                    val doomed = HashSet<Int>()
                    for (i in 0 until ids.length()) doomed += ids.optInt(i, -1)
                    removed = queue.count { it.itemId in doomed }
                    queue.removeAll { it.itemId in doomed }
                }
                if (queueIndex >= queue.size) queueIndex = queue.size - 1
                Logger.d("Cast: QUEUE_REMOVE -$removed item(s)")
                replyTo(from, sender, NS_MEDIA, queueChange(requestId, "REMOVE", 0))
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
            }

            "QUEUE_UPDATE" -> {
                val items = body.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val entry = items.optJSONObject(i) ?: continue
                        val id = entry.optInt("itemId", -1)
                        val index = queue.indexOfFirst { it.itemId == id }
                        if (index < 0) continue
                        val media = entry.optJSONObject("media") ?: continue
                        queue[index] = QueueItem(id, media, entry.optBoolean("autoplay", true))
                    }
                }
                replyTo(from, sender, NS_MEDIA, queueChange(requestId, "UPDATE", 0))
                replyTo(from, sender, NS_MEDIA, queueItemIds(requestId))
            }

            "QUEUE_REPEAT" -> {
                repeatMode = body.optString("repeatMode").ifBlank { "REPEAT_OFF" }
                Logger.d("Cast: QUEUE_REPEAT → $repeatMode")
                replyTo(from, sender, NS_MEDIA, queueChange(requestId, "UPDATE", 0))
            }

            "QUEUE_SHUFFLE" -> {
                // Shuffling is accepted but not performed: reordering a queue a sender is also
                // tracking would desynchronise the two lists, and nothing plays differently
                // enough to be worth the risk. Answered, because silence is not an option.
                Logger.d("Cast: QUEUE_SHUFFLE acknowledged (order left unchanged)")
                replyTo(from, sender, NS_MEDIA, queueChange(requestId, "NO_CHANGE", 0))
            }

            else -> {
                Logger.d("Cast: unhandled queue command ${body.optString("type")}")
                replyTo(from, sender, NS_MEDIA, invalidRequest(requestId, "INVALID_COMMAND"))
            }
        }
    }

    /**
     * Loads `queue[index]` into the player and records it as the current item.
     *
     * Caller must hold [queueLock].
     */
    private fun loadQueueItem(index: Int, startSec: Double, autoplay: Boolean) {
        if (index !in queue.indices) return
        val item = queue[index]
        queueIndex = index
        val media = item.media
        val url = media.optString("contentId").orEmpty()
        if (url.isBlank()) return
        contentId = url
        contentType = media.optString("contentType").orEmpty().ifBlank { "video/mp4" }
        streamType = media.optString("streamType").orEmpty().ifBlank { "BUFFERED" }
        metadataJson = media.optJSONObject("metadata")?.toString()
        mediaSessionId++
        currentItemIdValue = item.itemId
        StreamStats.castContentId = url
        player.load(url, startSec, item.autoplay && autoplay)
    }

    // ─── Status broadcasts ───────────────────────────────────────────────────

    /**
     * Broadcasts the current media status, e.g. after the player changes state on its own.
     *
     * Called on every player transition *and* once a second while playing: the sender's
     * progress bar and elapsed-time readout are driven entirely by these messages, so without
     * the periodic tick the scrubber freezes even though playback is fine.
     */
    fun broadcastMediaStatus() {
        if (contentId == null) return
        broadcast(NS_MEDIA, mediaStatus(0))
    }

    // ─── Sessions ────────────────────────────────────────────────────────────

    private fun openSession(appId: String): Boolean {
        val existing = session
        if (existing != null && existing.appId == appId) return false
        sessionCounter++
        val newSession = CastSession(
            appId = appId,
            displayName = displayName,
            sessionId = UUID.randomUUID().toString(),
            transportId = "web-$sessionCounter"
        )
        session = newSession
        onSessionChanged(true)
        Logger.i("Cast: session ${newSession.sessionId} opened for $appId on ${newSession.transportId}")
        return true
    }

    private fun closeSession() {
        session = null
        player.stop()
        contentId = null
        StreamStats.castContentId = ""
        synchronized(queueLock) {
            queue.clear()
            queueIndex = -1
            nextItemId = 1
        }
        currentItemIdValue = 1
        onSessionChanged(false)
        Logger.i("Cast: session closed")
    }

    /**
     * Opens a session for [appId].
     *
     * Called from the castv2 `LAUNCH` handler and from the DIAL server (`POST /apps/<id>`),
     * because some sender apps launch over DIAL and only then open the castv2 channel.
     */
    fun launch(appId: String) {
        openSession(appId)
        player.stop()
        onSessionChanged(true)
        broadcast(NS_RECEIVER, receiverStatus(0))
    }

    /** Ends the current session (castv2 `STOP` and DIAL `DELETE`). */
    fun stopSession() {
        closeSession()
        broadcast(NS_RECEIVER, receiverStatus(0))
    }

    // ─── Payload builders ────────────────────────────────────────────────────

    private fun receiverStatus(requestId: Int): JSONObject {
        val applications = JSONArray()
        session?.let { s ->
            applications.put(
                JSONObject()
                    .put("appId", s.appId)
                    .put("displayName", s.displayName)
                    .put("sessionId", s.sessionId)
                    .put("statusText", s.displayName)
                    .put("transportId", s.transportId)
                    .put("isIdleScreen", false)
                    .put("launchedFromCloud", false)
                    .put(
                        "namespaces",
                        JSONArray().apply {
                            put(JSONObject().put("name", NS_CONNECTION))
                            put(JSONObject().put("name", NS_HEARTBEAT))
                            put(JSONObject().put("name", NS_RECEIVER))
                            put(JSONObject().put("name", NS_MEDIA))
                        }
                    )
            )
        }

        val status = JSONObject()
            .put("userEq", JSONObject())
            .put(
                "volume",
                JSONObject()
                    .put("controlType", "attenuation")
                    .put("level", volumeLevel)
                    .put("muted", volumeMuted)
                    .put("stepInterval", 0.05)
            )
            .put("applications", applications)
            .put("isActiveInput", true)
            .put("isStandBy", false)
            .put("displayName", displayName)

        val out = JSONObject()
            .put("type", "RECEIVER_STATUS")
            .put("status", status)
        if (requestId != 0) out.put("requestId", requestId)
        return out
    }

    private fun mediaStatus(requestId: Int): JSONObject {
        val state = if (contentId == null) {
            CastMediaPlayer.State.IDLE
        } else {
            player.currentState()
        }
        // CastMediaPlayer.State values are the protocol's own strings.

        val media = JSONObject()
            .put("contentId", contentId ?: "")
            .put("contentType", contentType)
            .put("streamType", streamType)
            .put("duration", player.durationSec())
        metadataJson?.let { raw ->
            runCatching { media.put("metadata", JSONObject(raw)) }
        }

        val item = JSONObject()
            .put("mediaSessionId", mediaSessionId)
            .put("media", media)
            .put("playbackRate", playbackRate)
            .put("playerState", state)
            .put("currentTime", player.positionSec())
            .put("supportedMediaCommands", SUPPORTED_MEDIA_COMMANDS)
            .put(
                "volume",
                JSONObject()
                    .put("level", volumeLevel)
                    .put("muted", volumeMuted)
                    .put("controlType", "attenuation")
            )
            .put("currentItemId", currentItemId())
            .put("repeatMode", repeatMode)
            .put("extendedStatus", JSONObject())
        if (state == CastMediaPlayer.State.IDLE) {
            item.put("idleReason", "FINISHED")
        }

        val out = JSONObject()
            .put("type", "MEDIA_STATUS")
            .put("status", JSONArray().put(item))
        if (requestId != 0) out.put("requestId", requestId)
        return out
    }

    private fun currentItemId(): Int = currentItemIdValue

    private fun invalidRequest(requestId: Int, reason: String): JSONObject =
        JSONObject()
            .put("type", "INVALID_REQUEST")
            .put("reason", reason)
            .apply { if (requestId != 0) put("requestId", requestId) }

    private fun queueItemIds(requestId: Int): JSONObject {
        val ids = JSONArray()
        for (item in queue) ids.put(item.itemId)
        return JSONObject()
            .put("type", "QUEUE_ITEM_IDS")
            .put("itemIds", ids)
            .apply { if (requestId != 0) put("requestId", requestId) }
    }

    private fun queueItems(requestId: Int): JSONObject {
        val items = JSONArray()
        for (item in queue) {
            items.put(
                JSONObject()
                    .put("itemId", item.itemId)
                    .put("media", item.media)
                    .put("autoplay", item.autoplay)
            )
        }
        return JSONObject()
            .put("type", "QUEUE_ITEMS")
            .put("items", items)
            .apply { if (requestId != 0) put("requestId", requestId) }
    }

    private fun queueChange(requestId: Int, changeType: String, insertBefore: Int): JSONObject {
        val change = JSONObject()
            .put("type", changeType)
        if (changeType == "INSERT") change.put("insertBefore", insertBefore)
        return JSONObject()
            .put("type", "QUEUE_CHANGE")
            .put("change", JSONArray().put(change))
            .apply { if (requestId != 0) put("requestId", requestId) }
    }

    // ─── Sending ─────────────────────────────────────────────────────────────

    /**
     * Sends to one connection when we know which one asked ([from]), and to every connection
     * otherwise. Before 1.5.0 every reply went to every sink, so a second phone was bombarded
     * with statuses addressed to the first.
     */
    private fun replyTo(from: CastSink?, destinationId: String, namespace: String, payload: JSONObject) {
        val message = CastMessage(
            sourceId = sourceIdFor(namespace),
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payload.toString()
        )
        if (from != null) {
            runCatching { from.send(message) }
                .onFailure { Logger.w("Cast: send to $destinationId failed: ${it.message}") }
            return
        }
        broadcast(message)
    }

    private fun broadcast(namespace: String, payload: JSONObject) {
        broadcast(
            CastMessage(
                sourceId = sourceIdFor(namespace),
                destinationId = "*",
                namespace = namespace,
                payloadUtf8 = payload.toString()
            )
        )
    }

    private fun broadcast(message: CastMessage) {
        val targets: List<CastSink> = synchronized(lock) { sinks.toList() }
        for (sink in targets) {
            runCatching { sink.send(message) }
                .onFailure { Logger.w("Cast: broadcast failed: ${it.message}") }
        }
    }

    /** Media replies come from the app's transport; everything else from the receiver. */
    private fun sourceIdFor(namespace: String): String =
        if (namespace == NS_MEDIA) session?.transportId ?: RECEIVER_ID else RECEIVER_ID

    private fun String.messageType(): String? = toJsonObject()?.optString("type")

    private fun String.toJsonObject(): JSONObject? = try {
        JSONObject(this)
    } catch (e: Exception) {
        Logger.w("Cast: could not parse payload: ${take(120)}")
        null
    }

    companion object {
        const val RECEIVER_ID = "receiver-0"

        /** Connection handshake / virtual channels. */
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        /** Keep-alive (PING/PONG). */
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        /** Receiver control: GET_STATUS, LAUNCH, STOP, SET_VOLUME. */
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        /** Media transport: LOAD, PLAY, PAUSE, SEEK, STOP, GET_STATUS, QUEUE_*. */
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** The Default Media Receiver — what senders ask for when casting a plain media URL. */
        const val DEFAULT_APP_ID = "CC1AD845"

        /** Mirrors what a real Chromecast reports (pause, seek, volume, mute, skip, queue…). */
        private const val SUPPORTED_MEDIA_COMMANDS = 274447

        /** Cast "detailed error code" for a media item we were asked to load but cannot. */
        private const val DETAILED_ERROR_MEDIA_INVALID = 101
    }
}

/** A connection a Cast sender has opened to us. */
internal interface CastSink {
    fun send(message: CastMessage)
}
