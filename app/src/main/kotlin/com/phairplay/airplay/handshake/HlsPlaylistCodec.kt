package com.phairplay.airplay.handshake

import com.phairplay.airplay.LocalMediaAddressPolicy
import java.net.URI
import java.util.Locale

/**
 * HLS playlist codec for the sender-mediated video path.
 *
 * WHY A CODEC AND NOT A URL SEARCH-AND-REPLACE: an HLS playlist is a list of *references*
 * (variant URIs, `#EXT-X-MEDIA` rendition URIs, `#EXT-X-MAP` init segments, `#EXT-X-KEY` URIs and
 * segment URIs) and everything else must survive byte-for-byte — in particular the signed query
 * strings of segment URLs, which stop working if a single character is rewritten or a separator is
 * added. A reference is resolved against the playlist that contained it (RFC 3986), and a reference
 * this receiver cannot fetch is reported as a failure rather than rewritten into a URL that will
 * 404 at playback time.
 *
 * Everything here is pure: no sockets, no logging of URLs, no policy about what "fetchable" means
 * (that is the caller's `resolver`).
 */
private val AUDIO_CODEC_FAMILIES = setOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "alac")

internal object HlsPlaylistCodec {

    const val TAG_EXTM3U = "#EXTM3U"
    const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"
    const val TAG_I_FRAME_STREAM_INF = "#EXT-X-I-FRAME-STREAM-INF"
    const val TAG_MEDIA = "#EXT-X-MEDIA"
    const val TAG_MAP = "#EXT-X-MAP"
    const val TAG_KEY = "#EXT-X-KEY"
    const val TAG_SESSION_KEY = "#EXT-X-SESSION-KEY"
    const val TAG_EXTINF = "#EXTINF"
    const val TAG_BYTERANGE = "#EXT-X-BYTERANGE"
    const val TAG_TARGETDURATION = "#EXT-X-TARGETDURATION"
    const val TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE"
    const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    const val TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    /** YouTube's non-standard condensed-segment tag; see [expandCondensed]. */
    const val TAG_YT_CONDENSED = "#YT-EXT-CONDENSED-URL"

    enum class Kind { MASTER, MEDIA, EMPTY, UNKNOWN }

    /** What sort of reference a line or attribute is, for diagnostics. */
    enum class ReferenceKind(val label: String) {
        VARIANT_URI("variant"),
        I_FRAME_VARIANT_URI("i-frame variant"),
        RENDITION_URI("rendition"),
        SEGMENT_URI("segment"),
        INIT_MAP_URI("init map"),
        KEY_URI("key"),
    }

    data class Segment(
        val uri: String,
        val durationSec: Double?,
        val byteRange: String?,
        val startsWithDiscontinuity: Boolean,
    )

    /** Parsed media playlist (a segment list). */
    data class MediaPlaylist(
        val segments: List<Segment>,
        val initMapUri: String?,
        val keyMethod: String?,
        val keyUri: String?,
        val targetDurationSec: Int?,
        val mediaSequence: Long?,
        val hasEndList: Boolean,
        val discontinuityCount: Int,
        val hasByteRanges: Boolean,
        /** Non-null when the playlist uses YouTube's condensed segment form. */
        val condensed: CondensedTag?,
    ) {
        val isLive: Boolean get() = !hasEndList
        val segmentCount: Int get() = segments.size
        /** True when the playlist declares media encryption (other than `NONE`/absent). */
        val isEncrypted: Boolean get() = !keyMethod.isNullOrBlank() && keyMethod != "NONE"
        /** True for the one encryption scheme this receiver refuses to pretend it can play. */
        val isSampleAes: Boolean get() = keyMethod?.uppercase(Locale.US) == "SAMPLE-AES"
    }

    /**
     * YouTube's condensed-segment tag: the shared parts of every segment URI.
     *
     * [baseUri] is empty when the tag carries no `BASE-URI`. That is *not* treated as "no condensed
     * form": the segment lines under such a tag are fragments, not real paths, so the playlist is
     * refused (see [expandCondensed]) instead of being served with segment names that do not exist.
     */
    data class CondensedTag(val baseUri: String, val params: String, val prefix: String)

    data class Rendition(
        val type: String,
        val groupId: String?,
        val name: String?,
        val uri: String?,
        val isDefault: Boolean,
        val autoselect: Boolean,
        val language: String?,
    )

    data class Variant(
        val uri: String,
        val bandwidth: Long?,
        val codecs: List<String>,
        /** `AUDIO="…"` group this variant pairs with, when the master separates audio. */
        val audioGroupId: String?,
    )

    /** Parsed master playlist. */
    data class MasterPlaylist(val variants: List<Variant>, val renditions: List<Rendition>) {
        /** How the sender's master describes the streams it offers. */
        enum class Shape { MUXED, SEPARATE_AUDIO, AUDIO_ONLY, NO_VARIANTS }

        val audioRenditions: List<Rendition> get() = renditions.filter { it.type.equals("AUDIO", true) }

        /**
         * True only when at least one variant is not explicitly audio-only. Missing CODECS metadata
         * stays ambiguous rather than being misreported as audio-only.
         */
        val hasPotentialVideoVariant: Boolean
            get() = variants.any { variant ->
                variant.codecs.isEmpty() || variant.codecs.any { codec ->
                    codec.substringBefore('.').lowercase(Locale.US) !in AUDIO_CODEC_FAMILIES
                }
            }

        val shape: Shape
            get() = when {
                variants.isEmpty() && audioRenditions.isNotEmpty() -> Shape.AUDIO_ONLY
                variants.isEmpty() -> Shape.NO_VARIANTS
                !hasPotentialVideoVariant -> Shape.AUDIO_ONLY
                audioRenditions.any { !it.uri.isNullOrBlank() } -> Shape.SEPARATE_AUDIO
                else -> Shape.MUXED
            }

        /** Distinct video codec families the master offers, for diagnostics (no URLs). */
        fun videoCodecFamilies(): List<String> = variants
            .flatMap { it.codecs }
            .map { codec -> codec.substringBefore('.').lowercase(Locale.US) }
            .filter { it !in AUDIO_CODEC_FAMILIES }
            .distinct()
            .sorted()
    }

    /** Classifies a playlist by its first meaningful tags, without rewriting anything. */
    fun kindOf(text: String): Kind {
        var sawMasterTag = false
        var sawMediaTag = false
        var sawSegment = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith(TAG_EXTM3U, true) -> Unit
                line.startsWith(TAG_STREAM_INF, true) || line.startsWith(TAG_I_FRAME_STREAM_INF, true) ||
                    line.startsWith(TAG_MEDIA, true) -> sawMasterTag = true
                line.startsWith(TAG_EXTINF, true) || line.startsWith(TAG_TARGETDURATION, true) ||
                    line.startsWith(TAG_BYTERANGE, true) || line.startsWith(TAG_ENDLIST, true) -> sawMediaTag = true
                !line.startsWith('#') -> sawSegment = true
            }
        }
        return when {
            sawMasterTag && !sawMediaTag -> Kind.MASTER
            sawMediaTag || sawSegment -> Kind.MEDIA
            else -> Kind.UNKNOWN
        }
    }

    // ─── Parsing ────────────────────────────────────────────────────────────────────────────────

    fun parseMedia(text: String): MediaPlaylist {
        val segments = ArrayList<Segment>()
        var pendingDuration: Double? = null
        var pendingByteRange: String? = null
        var pendingDiscontinuity = false
        var mapUri: String? = null
        var keyMethod: String? = null
        var keyUri: String? = null
        var targetDuration: Int? = null
        var mediaSequence: Long? = null
        var endList = false
        var discontinuityCount = 0
        var byteRanges = false
        var condensed: CondensedTag? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith(TAG_EXTINF, true) -> pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                line.startsWith(TAG_BYTERANGE, true) -> {
                    byteRanges = true
                    pendingByteRange = line.substringAfter(':').trim()
                }
                line.startsWith(TAG_DISCONTINUITY + ":", true) || line == TAG_DISCONTINUITY -> {
                    discontinuityCount++
                    pendingDiscontinuity = true
                }
                line.startsWith(TAG_TARGETDURATION + ":", true) ->
                    targetDuration = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith(TAG_MEDIA_SEQUENCE + ":", true) ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull()
                line == TAG_ENDLIST || line.startsWith(TAG_ENDLIST + ":", true) -> endList = true
                line.startsWith(TAG_MAP + ":", true) -> {
                    val value = attribute(line, "URI")
                    if (!value.isNullOrBlank()) mapUri = value
                }
                line.startsWith(TAG_KEY + ":", true) || line.startsWith(TAG_SESSION_KEY + ":", true) -> {
                    attribute(line, "METHOD")?.let { keyMethod = it.uppercase(Locale.US) }
                    attribute(line, "URI")?.takeIf { it.isNotBlank() }?.let { keyUri = it }
                }
                line.startsWith(TAG_YT_CONDENSED, true) -> condensed = parseCondensedTag(line)
                line.startsWith('#') -> Unit
                else -> {
                    segments += Segment(line, pendingDuration, pendingByteRange, pendingDiscontinuity)
                    pendingDuration = null
                    pendingByteRange = null
                    pendingDiscontinuity = false
                }
            }
        }
        return MediaPlaylist(
            segments = segments,
            initMapUri = mapUri,
            keyMethod = keyMethod,
            keyUri = keyUri,
            targetDurationSec = targetDuration,
            mediaSequence = mediaSequence,
            hasEndList = endList,
            discontinuityCount = discontinuityCount,
            hasByteRanges = byteRanges,
            condensed = condensed,
        )
    }

    fun parseMaster(text: String): MasterPlaylist {
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        var pendingVariant: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith(TAG_STREAM_INF, true) -> pendingVariant = line
                line.startsWith(TAG_I_FRAME_STREAM_INF, true) -> {
                    val uri = attribute(line, "URI")
                    if (!uri.isNullOrBlank()) {
                        variants += Variant(
                            uri = uri,
                            bandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull(),
                            codecs = codecsOf(line),
                            audioGroupId = attribute(line, "AUDIO"),
                        )
                    }
                }
                line.startsWith(TAG_MEDIA + ":", true) -> renditions += Rendition(
                    type = attribute(line, "TYPE").orEmpty().uppercase(Locale.US),
                    groupId = attribute(line, "GROUP-ID"),
                    name = attribute(line, "NAME"),
                    uri = attribute(line, "URI"),
                    isDefault = attribute(line, "DEFAULT")?.equals("YES", true) == true,
                    autoselect = attribute(line, "AUTOSELECT")?.equals("YES", true) == true,
                    language = attribute(line, "LANGUAGE"),
                )
                line.startsWith('#') -> pendingVariant = null
                else -> {
                    val info = pendingVariant
                    pendingVariant = null
                    variants += Variant(
                        uri = line,
                        bandwidth = info?.let { attribute(it, "BANDWIDTH")?.toLongOrNull() },
                        codecs = info?.let(::codecsOf).orEmpty(),
                        audioGroupId = info?.let { attribute(it, "AUDIO") },
                    )
                }
            }
        }
        return MasterPlaylist(variants, renditions)
    }

    // ─── Rewriting ──────────────────────────────────────────────────────────────────────────────

    /** What a rewrite produced: the playlist bytes to serve, or a URL-free reason it cannot be served. */
    sealed interface Rewrite {
        data class Ok(
            val text: String,
            /** References handed to the caller's own transport. */
            val bridged: Int,
            /** References the player fetches itself (absolute, directly reachable URLs). */
            val direct: Int,
            val condensedExpanded: Boolean,
        ) : Rewrite

        data class Failed(val reason: String, val referenceKind: ReferenceKind) : Rewrite
    }

    /**
     * Rewrites every reference in a master playlist.
     *
     * @param baseUri the URI the master was fetched from (for relative references)
     * @param map decides what the player is given for one reference: the URI to serve, or null when
     *   this receiver cannot serve that reference at all. It is called for **every** reference with
     *   the resolved absolute URI and whether the playlist wrote that reference as a *relative*
     *   path — a relative reference belongs to the sender's own transport (its base may be a host
     *   only the sender can reach: `mlhls://`, `localhost:<port>`, a session-bound CDN), while an
     *   absolute one is a URL the sender wrote out in full, signed bytes included.
     */
    fun rewriteMaster(text: String, baseUri: String, map: (String, Boolean) -> String?): Rewrite =
        rewriteLines(text, baseUri, map, master = true)

    /**
     * Rewrites a media playlist: expands YouTube's condensed form when present, then rewrites every
     * segment, init-map and key reference. The bytes of everything else — `#EXTINF`, byte ranges,
     * discontinuity markers, `#EXT-X-PROGRAM-DATE-TIME` — are preserved.
     *
     * @param map as for [rewriteMaster]: `(resolvedUri, wasRelative)`
     */
    fun rewriteMedia(text: String, baseUri: String, map: (String, Boolean) -> String?): Rewrite {
        val parsed = parseMedia(text)
        if (parsed.isSampleAes) {
            return Rewrite.Failed("media playlist requires SAMPLE-AES protection", ReferenceKind.KEY_URI)
        }
        var working = text
        var expanded = false
        if (parsed.condensed != null) {
            working = when (val result = expandCondensed(text)) {
                is CondensedResult.Expanded -> {
                    expanded = true
                    result.text
                }
                is CondensedResult.Unsupported -> return Rewrite.Failed(result.reason, ReferenceKind.SEGMENT_URI)
            }
        }
        val rewritten = rewriteLines(working, baseUri, map, master = false)
        return when (rewritten) {
            is Rewrite.Ok -> rewritten.copy(condensedExpanded = expanded)
            is Rewrite.Failed -> rewritten
        }
    }

    private fun rewriteLines(
        text: String,
        baseUri: String,
        map: (String, Boolean) -> String?,
        master: Boolean,
    ): Rewrite {
        val base = runCatching { URI(baseUri) }.getOrNull()
        if (base == null || base.scheme.isNullOrBlank()) {
            return Rewrite.Failed("playlist has no usable base URI", ReferenceKind.VARIANT_URI)
        }
        val out = StringBuilder(text.length + 64)
        var bridged = 0
        var direct = 0
        val lines = text.split('\n')
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            val mapped: Mapping = when {
                line.isBlank() -> Mapping.Keep(line)
                line.startsWith('#') -> when {
                    master && carriesUriAttribute(line) -> rewriteAttribute(line, "URI", base, map, ReferenceKind.RENDITION_URI)
                    !master && line.startsWith(TAG_MAP + ":", true) -> rewriteAttribute(line, "URI", base, map, ReferenceKind.INIT_MAP_URI)
                    !master && (line.startsWith(TAG_KEY + ":", true) || line.startsWith(TAG_SESSION_KEY + ":", true)) ->
                        rewriteAttribute(line, "URI", base, map, ReferenceKind.KEY_URI)
                    else -> Mapping.Keep(line)
                }
                else -> rewriteReference(line, base, map, if (master) ReferenceKind.VARIANT_URI else ReferenceKind.SEGMENT_URI)
            }
            when (mapped) {
                is Mapping.Keep -> out.append(mapped.text)
                is Mapping.Direct -> {
                    direct++
                    out.append(mapped.text)
                }
                is Mapping.Replaced -> {
                    bridged++
                    out.append(mapped.text)
                }
                is Mapping.Failed -> return Rewrite.Failed(mapped.reason, mapped.kind)
            }
            if (index != lines.lastIndex) out.append('\n')
        }
        return Rewrite.Ok(out.toString(), bridged, direct, condensedExpanded = false)
    }

    private fun carriesUriAttribute(line: String): Boolean =
        line.startsWith(TAG_MEDIA + ":", true) ||
            line.startsWith(TAG_I_FRAME_STREAM_INF + ":", true) ||
            line.startsWith(TAG_MAP + ":", true) ||
            line.startsWith(TAG_KEY + ":", true)

    private sealed interface Mapping {
        data class Keep(val text: String) : Mapping
        data class Direct(val text: String) : Mapping
        data class Replaced(val text: String) : Mapping
        data class Failed(val reason: String, val kind: ReferenceKind) : Mapping
    }

    /** Rewrites one URI line (`Mapping.Direct` = leave the resolved URL, `Replaced` = caller's URI). */
    private fun rewriteReference(
        reference: String,
        base: URI,
        map: (String, Boolean) -> String?,
        kind: ReferenceKind,
    ): Mapping {
        val resolved = resolve(base, reference)
            ?: return Mapping.Failed("unusable ${kind.label} reference (${describe(reference)})", kind)
        val replacement = map(resolved, isRelativeReference(reference))
            ?: return Mapping.Failed("no transport for the ${kind.label} (${describe(resolved)})", kind)
        return if (replacement == resolved) Mapping.Direct(replacement) else Mapping.Replaced(replacement)
    }

    /** Rewrites a single `NAME="…"` attribute value in place, preserving every other byte. */
    private fun rewriteAttribute(
        line: String,
        attributeName: String,
        base: URI,
        map: (String, Boolean) -> String?,
        kind: ReferenceKind,
    ): Mapping {
        val marker = "$attributeName=\""
        val start = line.indexOf(marker)
        if (start < 0) return Mapping.Keep(line)
        val valueStart = start + marker.length
        val valueEnd = line.indexOf('"', valueStart)
        if (valueEnd < 0) return Mapping.Keep(line)
        val reference = line.substring(valueStart, valueEnd)
        if (reference.isBlank()) return Mapping.Keep(line)
        val resolved = resolve(base, reference)
            ?: return Mapping.Failed("unusable ${kind.label} reference (${describe(reference)})", kind)
        val replacement = map(resolved, isRelativeReference(reference))
            ?: return Mapping.Failed("no transport for the ${kind.label} (${describe(resolved)})", kind)
        if (replacement == resolved) return Mapping.Direct(line)
        return Mapping.Replaced(line.substring(0, valueStart) + replacement + line.substring(valueEnd))
    }

    /**
     * Resolves one playlist reference against the playlist's own URI.
     *
     * A reference with its own scheme is returned unchanged; otherwise [URI.resolve] applies RFC 3986
     * — including "drop the base's query string unless the reference starts with `?`", which is the
     * reason a signed parent URL must never be pasted onto a child reference.
     */
    fun resolve(base: URI, reference: String): String? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (parsed.isAbsolute) return if (parsed.scheme.isNullOrBlank()) null else trimmed
        return runCatching { base.resolve(parsed).toString() }.getOrNull()
    }

    /**
     * True for URLs this receiver's player can fetch directly (the sender signed them).
     *
     * A loopback host is never directly fetchable: `http://localhost:<port>/…` and `127.0.0.1` point
     * at the *sender's* machine, so a URL naming one has to travel through the sender or not at all
     * (handing it to the player is how a session ends up with a black screen and a connection error
     * that says nothing about the real cause).
     */
    fun isDirectlyFetchable(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = parsed.host ?: return false
        if (host.isBlank() || parsed.rawUserInfo != null) return false
        return !LocalMediaAddressPolicy.isLoopbackHost(host)
    }

    /** True when the reference is not absolute, i.e. relative to the playlist's own URI. */
    private fun isRelativeReference(reference: String): Boolean {
        val parsed = runCatching { URI(reference.trim()) }.getOrNull() ?: return true
        return !parsed.isAbsolute
    }

    /** Short description of a reference for diagnostics: scheme, extension, query presence, length. */
    fun describe(reference: String): String {
        val parsed = runCatching { URI(reference.trim()) }.getOrNull() ?: return "unparseable"
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: "relative"
        val path = parsed.path.orEmpty()
        val extension = path.substringAfterLast('.', "").take(8)
        return buildString {
            append(scheme)
            if (extension.isNotEmpty()) append('/').append(extension)
            if (!parsed.rawQuery.isNullOrEmpty()) append("+query")
            append(' ').append(path.length).append("ch")
        }
    }

    // ─── YouTube condensed segments ─────────────────────────────────────────────────────────────

    /**
     * Result of [expandCondensed]. Not private: [expandCondensed] is callable from the same module
     * (tests, diagnostics) and a private return type would make that impossible.
     */
    sealed interface CondensedResult {
        data class Expanded(val text: String) : CondensedResult
        data class Unsupported(val reason: String) : CondensedResult
    }

    private fun parseCondensedTag(line: String): CondensedTag {
        val body = line.substringAfter(':', "")
        return CondensedTag(
            baseUri = attribute(body, "BASE-URI").orEmpty(),
            params = attribute(body, "PARAMS").orEmpty(),
            prefix = attribute(body, "PREFIX").orEmpty(),
        )
    }

    /**
     * Expands YouTube's condensed segment form.
     *
     * The tag replaces each segment URI with `<PREFIX><fragment>` and carries the shared parts:
     * `BASE-URI`, and optionally `PARAMS`. Two shapes are handled:
     *
     *  - `PARAMS` empty or absent — every segment is `BASE-URI + "/" + fragment` (with an empty
     *    `PREFIX`, which is what real playlists carry, the fragment is the whole segment line);
     *  - `PARAMS` non-empty — its comma-separated values are interleaved with the fragment's
     *    `/`-separated components: `BASE-URI/param0/part0/param1/part1/…` (the shape UxPlay's
     *    `adjust_yt_condensed_playlist` expands).
     *
     * Anything else — a missing or relative base, a fragment that is not a path, a component count
     * that does not match the parameter count, an expansion that is not a usable `http(s)` URL — is
     * refused with a reason instead of guessed at: a wrong guess produces signed URLs that fail with
     * no explanation.
     */
    fun expandCondensed(text: String): CondensedResult {
        val tagLine = text.lineSequence().firstOrNull { it.trim().startsWith(TAG_YT_CONDENSED, true) }
            ?: return CondensedResult.Unsupported("condensed tag missing")
        val tag = parseCondensedTag(tagLine)
        if (tag.baseUri.isEmpty()) return CondensedResult.Unsupported("condensed tag has no BASE-URI")
        val base = runCatching { URI(tag.baseUri) }.getOrNull()
            ?: return CondensedResult.Unsupported("condensed BASE-URI is not a URI")
        if (!base.isAbsolute || base.host.isNullOrBlank()) {
            return CondensedResult.Unsupported("condensed BASE-URI is not an absolute network URL")
        }
        val params = tag.params.takeIf { it.isNotEmpty() }?.split(',') ?: emptyList()
        if (params.any { it.isEmpty() || it.any { c -> c.isWhitespace() || c.isISOControl() } }) {
            return CondensedResult.Unsupported("condensed PARAMS contain an unusable value")
        }
        var segmentLines = 0
        val out = StringBuilder(text.length * 2)
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            when {
                trimmed.startsWith(TAG_YT_CONDENSED, true) -> Unit // drop the non-standard tag
                trimmed.isEmpty() -> out.append('\n')
                trimmed.startsWith('#') -> out.append(trimmed).append('\n')
                // An empty PREFIX is the form real YouTube playlists carry: the whole line is the
                // fragment (there is nothing to strip), which is what independent receivers had to
                // fix. A non-empty PREFIX must actually match this line, or the line is not a
                // condensed segment and is left alone (the count check below then refuses the
                // playlist rather than emitting a half-expanded one).
                (tag.prefix.isEmpty() || trimmed.startsWith(tag.prefix)) -> {
                    val fragment = if (tag.prefix.isEmpty()) trimmed else trimmed.substring(tag.prefix.length).trim()
                    if (fragment.isEmpty() || fragment.any { it.isWhitespace() || it.isISOControl() }) {
                        return CondensedResult.Unsupported("condensed segment fragment is unusable")
                    }
                    if (runCatching { URI(fragment) }.getOrNull()?.isAbsolute == true) {
                        return CondensedResult.Unsupported("condensed segment fragment is not a path")
                    }
                    val parts = interleave(params, fragment.split('/'))
                        ?: return CondensedResult.Unsupported("condensed PARAMS do not match the segment path")
                    val uri = joinUnder(tag.baseUri, parts)
                    if (!isDirectlyFetchable(uri)) {
                        return CondensedResult.Unsupported("condensed expansion produced an unusable URL")
                    }
                    out.append(uri).append('\n')
                    segmentLines++
                }
                else -> out.append(trimmed).append('\n')
            }
        }
        if (segmentLines == 0) return CondensedResult.Unsupported("condensed playlist had no segment lines")
        val declared = parseMedia(text).segmentCount
        if (declared != segmentLines) {
            return CondensedResult.Unsupported("condensed playlist segment count mismatch")
        }
        return CondensedResult.Expanded(out.toString())
    }

    private fun interleave(params: List<String>, parts: List<String>): List<String>? {
        if (params.isEmpty()) return parts.ifEmpty { null }
        if (params.size != parts.size) return null
        val result = ArrayList<String>(params.size * 2)
        params.forEachIndexed { index, param ->
            result += param
            result += parts[index]
        }
        return result
    }

    private fun joinUnder(baseUri: String, parts: List<String>): String {
        val builder = StringBuilder(baseUri)
        parts.forEach { part ->
            if (part.isEmpty()) return@forEach
            if (!builder.endsWith("/")) builder.append('/')
            builder.append(part.trimStart('/'))
        }
        return builder.toString()
    }

    // ─── Attribute helpers ──────────────────────────────────────────────────────────────────────

    /** Reads `NAME=value` / `NAME="value"` from a tag line or attribute list; null when absent. */
    fun attribute(line: String, name: String): String? {
        val marker = "$name="
        var index = line.indexOf(marker)
        while (index >= 0) {
            val before = if (index == 0) ':' else line[index - 1]
            if (before == ':' || before == ',' || before == ' ' || before == '\t') {
                val valueStart = index + marker.length
                if (valueStart < line.length && line[valueStart] == '"') {
                    val end = line.indexOf('"', valueStart + 1)
                    return if (end < 0) null else line.substring(valueStart + 1, end)
                }
                var end = valueStart
                while (end < line.length && line[end] != ',' && !line[end].isWhitespace()) end++
                return line.substring(valueStart, end).ifBlank { null }
            }
            index = line.indexOf(marker, index + marker.length)
        }
        return null
    }

    private fun codecsOf(line: String): List<String> =
        attribute(line, "CODECS")
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

}
