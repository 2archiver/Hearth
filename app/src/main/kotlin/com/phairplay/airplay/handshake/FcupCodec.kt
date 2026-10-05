package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import java.util.Locale

/**
 * FCUP ("FairPlay Content Update Protocol" in Apple's own naming) request/reply codec — the
 * sender-mediated half of AirPlay video, also called *HTTP Live Streaming with the client as the
 * CDN*. It is the transport YouTube's iOS app uses for AirPlay video, and the reason a receiver
 * that only accepts direct `http(s)` media locations gets **audio only** from that app.
 *
 * How the handshake works (this is the reverse channel, not the media channel):
 *
 * 1. The sender asks for an AirPlay video session (`GET /server-info`, then `POST /play` whose
 *    `Content-Location` is an internal location such as `mlhls://…/master.m3u8` that only the
 *    sender can resolve).
 * 2. The receiver sends a **reverse HTTP request** back to the sender on the PTTH channel
 *    (`POST /event`, see [EVENT_PATH]) carrying an XML plist that says "fetch this URL for me and
 *    send me the bytes"; [buildEventRequest] is that plist.
 * 3. The sender answers on the forward AirPlay channel with `POST /action`, a **binary plist**
 *    whose `params` dictionary carries the request id it is answering, the URL, and the data;
 *    [parseAction] decodes it.
 * 4. Playlists are exchanged that way — the master playlist first, then each media playlist the
 *    master names, because a media playlist's segments are signed URLs the *sender* is allowed to
 *    fetch (they are the same URLs the sender would have played itself).
 *
 * Field names and the plist shape below are taken from UxPlay's `lib/fcup_request.h` and
 * `lib/http_handlers.h` (`http_handler_action`), which implement this against real iOS YouTube
 * senders. UxPlay is GPL-3.0: nothing here is copied from it — only the wire contract (field
 * names, types, which side sends which message) is implemented, in Kotlin, from that description.
 */
internal object FcupCodec {

    /** Reverse-channel request that asks the sender to fetch one URL. */
    const val EVENT_PATH = "/event"

    /** `Content-Type` of an [EVENT_PATH] request body (UxPlay `http_response_add_header`). */
    const val EVENT_CONTENT_TYPE = "text/x-apple-plist+xml"

    /** Forward AirPlay request the sender uses to answer an [EVENT_PATH] request. */
    const val ACTION_PATH = "/action"

    /** `type` value the receiver sends in an FCUP request. */
    const val REQUEST_TYPE = "unhandledURLRequest"

    /** `type` value the sender uses in its `POST /action` reply. */
    const val RESPONSE_TYPE = "unhandledURLResponse"

    /** `type` values the sender may use for playlist bookkeeping (advertisement insert/remove). */
    const val TYPE_PLAYLIST_INSERT = "playlistInsert"
    const val TYPE_PLAYLIST_REMOVE = "playlistRemove"

    /** Fixed values the reference implementation sends; they are receiver-side identifiers. */
    const val SESSION_ID_VALUE = 1L
    const val CLIENT_INFO = 1L
    const val CLIENT_REF = 40030004L

    /**
     * The `User-Agent` the reference implementation puts in the nested headers dictionary. It is
     * part of the request the sender validates loosely (it is replayed onto the fetch the sender
     * performs), and the value seen in the wild is an old Apple TV user agent.
     */
    const val CLIENT_USER_AGENT =
        "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us"

    /** Bounds for values that arrive from the sender. */
    const val MAX_URL_CHARS = 8 * 1024
    const val MAX_SESSION_ID_CHARS = 128
    const val MAX_DATA_BYTES = 8 * 1024 * 1024

    /**
     * Builds the XML plist body of an FCUP request: "fetch [mediaUrl] with request id [requestId]
     * under playback session [sessionId] and answer with `POST /action`".
     *
     * @param sessionId the sender's `X-Apple-Session-ID` for this playback session — echoed back in
     *   both the reverse request's header and the nested `FCUP_Response_Headers` dictionary.
     * @param mediaUrl the URL the receiver wants the sender to fetch (a playlist URL, or a media
     *   segment the playlist named by a reference only the sender can resolve).
     * @param requestId receiver-assigned correlation id; the sender echoes it in its reply.
     */
    fun buildEventRequest(sessionId: String, mediaUrl: String, requestId: Long): ByteArray =
        PlistCodec.encodeXml(
            mapOf(
                "sessionID" to SESSION_ID_VALUE,
                "type" to REQUEST_TYPE,
                "request" to mapOf(
                    "FCUP_Response_ClientInfo" to CLIENT_INFO,
                    "FCUP_Response_ClientRef" to CLIENT_REF,
                    "FCUP_Response_RequestID" to requestId,
                    "FCUP_Response_URL" to mediaUrl,
                    "sessionID" to SESSION_ID_VALUE,
                    "FCUP_Response_Headers" to mapOf(
                        "X-Playback-Session-Id" to sessionId,
                        "User-Agent" to CLIENT_USER_AGENT,
                    ),
                ),
            )
        )

    /** What a decoded `POST /action` body turned out to be. */
    internal sealed interface ActionParse {
        /** A reply to one of our FCUP requests. */
        data class Response(val response: FcupResponse) : ActionParse

        /** Playlist bookkeeping (advertisement insert/remove) — acknowledged, not acted on. */
        data class Playlist(val type: String, val uuidPresent: Boolean) : ActionParse

        /** A plist we understand structurally but not semantically. */
        data class Unsupported(val type: String?) : ActionParse

        /** Not a usable FCUP reply at all. */
        data class Invalid(val reason: String) : ActionParse
    }

    /** Decoded `FCUP_Response_*` fields of a reply. [data] is the fetched body itself. */
    internal data class FcupResponse(
        val requestId: Long?,
        val url: String,
        val statusCode: Int?,
        val data: ByteArray,
    ) {
        // ByteArray in a data class: equality by content, so tests can compare decoded replies.
        override fun equals(other: Any?): Boolean =
            other is FcupResponse && requestId == other.requestId && url == other.url &&
                statusCode == other.statusCode && data.contentEquals(other.data)

        override fun hashCode(): Int =
            ((requestId?.hashCode() ?: 0) * 31 + url.hashCode()) * 31 + data.contentHashCode()
    }

    /**
     * Decodes the binary (or XML) plist body of `POST /action`.
     *
     * Never throws; every rejection path returns [ActionParse.Invalid] with a short, URL-free
     * reason so the caller can answer 400 and record *why* in the trace.
     */
    fun parseAction(body: ByteArray): ActionParse {
        if (body.isEmpty()) return ActionParse.Invalid("empty body")
        if (body.size > MAX_DATA_BYTES + MAX_URL_CHARS) return ActionParse.Invalid("body exceeds limit")
        val fields = runCatching { PlistCodec.decode(body) }.getOrElse {
            return ActionParse.Invalid("plist decode failed (${it.javaClass.simpleName})")
        }
        val type = stringField(fields, "type")
        val params = mapField(fields, "params")
            ?: return ActionParse.Invalid("params dictionary missing")
        return when (type?.lowercase(Locale.US)) {
            RESPONSE_TYPE.lowercase(Locale.US) -> parseResponse(params)
            TYPE_PLAYLIST_INSERT, TYPE_PLAYLIST_REMOVE -> ActionParse.Playlist(
                type = type,
                uuidPresent = params.keys.any { it.equals("uuid", true) } ||
                    (params["item"] as? Map<*, *>)?.keys?.any { it.equals("uuid", true) } == true,
            )
            null -> ActionParse.Invalid("type missing")
            else -> ActionParse.Unsupported(type.take(64))
        }
    }

    private fun parseResponse(params: Map<String, Any?>): ActionParse {
        val requestId = numberField(params, "FCUP_Response_RequestID")?.toLong()
        val url = stringField(params, "FCUP_Response_URL")
            ?: return ActionParse.Invalid("FCUP_Response_URL missing")
        if (url.length > MAX_URL_CHARS) return ActionParse.Invalid("FCUP_Response_URL exceeds limit")
        if (url.isBlank()) return ActionParse.Invalid("FCUP_Response_URL is blank")
        val statusCode = numberField(params, "FCUP_Response_StatusCode")?.toInt()
        val dataValue = field(params, "FCUP_Response_Data")
            ?: return ActionParse.Invalid("FCUP_Response_Data missing")
        val data: ByteArray = when (dataValue) {
            is ByteArray -> dataValue
            // Some senders answer with a plain string body; accept it rather than dropping a
            // playlist, but never log the content (it can carry signed URLs).
            is String -> dataValue.toByteArray(Charsets.UTF_8)
            else -> return ActionParse.Invalid("FCUP_Response_Data has an unsupported type")
        }
        if (data.size > MAX_DATA_BYTES) return ActionParse.Invalid("FCUP_Response_Data exceeds limit")
        if (data.isEmpty()) return ActionParse.Invalid("FCUP_Response_Data is empty")
        return ActionParse.Response(FcupResponse(requestId, url, statusCode, data))
    }

    /** Case-insensitive field lookup that also tolerates the exact-name form senders use. */
    private fun field(map: Map<String, Any?>, name: String): Any? =
        map[name] ?: map.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun stringField(map: Map<String, Any?>, name: String): String? = field(map, name) as? String

    private fun numberField(map: Map<String, Any?>, name: String): Number? = field(map, name) as? Number

    @Suppress("UNCHECKED_CAST")
    private fun mapField(map: Map<String, Any?>, name: String): Map<String, Any?>? =
        field(map, name) as? Map<String, Any?>

    /** One-line, URL-free description for the trace. */
    fun describe(parse: ActionParse): String = when (parse) {
        is ActionParse.Response -> "response requestIdPresent=${parse.response.requestId != null} " +
            "urlChars=${parse.response.url.length} bytes=${parse.response.data.size} " +
            "status=${parse.response.statusCode ?: 0}"
        is ActionParse.Playlist -> "playlist ${parse.type} uuidPresent=${parse.uuidPresent}"
        is ActionParse.Unsupported -> "unsupported type '${parse.type?.take(32)}'"
        is ActionParse.Invalid -> "invalid (${parse.reason})"
    }.also { Logger.d("FCUP action: $it") }
}
