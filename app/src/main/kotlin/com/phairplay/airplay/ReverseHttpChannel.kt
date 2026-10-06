package com.phairplay.airplay

import com.phairplay.airplay.handshake.FcupCodec
import com.phairplay.util.Logger
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** A sender-mediated fetch that failed. The message is URL-free and safe for the player/trace. */
internal class HlsBridgeException(message: String) : IOException(message)

/**
 * The PTTH reverse-HTTP channel: how the receiver asks the *sender* to fetch a URL on its behalf.
 *
 * The sender upgraded one of its connections to `PTTH/1.0` with `POST /reverse`; the receiver then
 * writes plain HTTP requests (`POST /event`) to that socket. The sender answers with `POST /action`
 * on an ordinary AirPlay connection. Request ids are process-wide, so a delayed answer from a closed
 * or replaced channel cannot collide with a new channel's first request.
 *
 * The channel binds ids to bounded waiters, coalesces simultaneous fetches of the same URL, serializes
 * lifecycle changes, and releases every waiter on close. It never logs a URL or response body.
 */
internal class ReverseHttpChannel(
    /** Receiver-generated connection label of the connection that carried `POST /reverse`. */
    val connectionId: String,
    /** The sender's `X-Apple-Session-ID`, echoed in every request header. */
    val senderSessionId: String,
    /** Writes one complete request frame to the upgraded socket; false when the socket is gone. */
    private val writeFrame: (ByteArray) -> Boolean,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
    private val maxBodyBytes: Int = FcupCodec.MAX_DATA_BYTES,
) {
    private data class Pending(
        val requestId: Long,
        val url: String,
        val kind: String,
        val future: CompletableFuture<ByteArray>,
    )

    /** What one `POST /action` body did to this channel, plus what to answer the sender. */
    data class Delivery(val accepted: Boolean, val httpStatus: Int, val summary: String)

    /** All fields below are protected by [lock]. */
    private val pending = HashMap<Long, Pending>()
    private val inFlightByUrl = HashMap<String, Pending>()
    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var closeReason: String = "session ended"

    private val requestsSent = AtomicLong(0L)
    private val repliesMatched = AtomicLong(0L)
    private val repliesIgnored = AtomicLong(0L)

    init {
        require(requestTimeoutMs > 0L) { "request timeout must be positive" }
        require(maxPendingRequests > 0) { "pending request limit must be positive" }
        require(maxBodyBytes >= 0) { "body limit must not be negative" }
        require(senderSessionId.length <= FcupCodec.MAX_SESSION_ID_CHARS) { "sender session id exceeds limit" }
        require(senderSessionId.all { it.code in 0x20..0x7E }) { "sender session id contains a control character" }
    }

    val isWritable: Boolean get() = !closed

    /** Ids and counters for the connection log — never URLs. */
    fun stats(): String = synchronized(lock) {
        "sent=${requestsSent.get()} matched=${repliesMatched.get()} ignored=${repliesIgnored.get()} " +
            "pending=${pending.size}"
    }

    /** True while this channel is waiting for [requestId], used to route replies across bridge generations. */
    fun hasPending(requestId: Long?): Boolean = requestId != null && synchronized(lock) {
        pending.containsKey(requestId)
    }

    /**
     * Asks the sender to fetch [url] and blocks (bounded) for the answer.
     *
     * A duplicate concurrent fetch joins the original future and does not put a second `/event` on
     * the socket. Request ids remain unique across every [ReverseHttpChannel] in this process.
     */
    @Throws(HlsBridgeException::class)
    fun fetch(url: String, kind: String): ByteArray {
        if (url.isBlank() || url.length > FcupCodec.MAX_URL_CHARS) {
            throw HlsBridgeException("media URL is missing or exceeds the limit")
        }

        var entryToSend: Pending? = null
        val entry = synchronized(lock) {
            if (closed) throw HlsBridgeException("reverse channel closed ($closeReason)")
            inFlightByUrl[url] ?: run {
                if (pending.size >= maxPendingRequests) {
                    throw HlsBridgeException("too many unanswered reverse requests")
                }
                val requestId = NEXT_REQUEST_ID.incrementAndGet()
                if (requestId !in 1L..Int.MAX_VALUE.toLong()) {
                    throw HlsBridgeException("reverse request id space exhausted")
                }
                val created = Pending(url = url, kind = kind, requestId = requestId, future = CompletableFuture())
                pending[requestId] = created
                inFlightByUrl[url] = created
                entryToSend = created
                created
            }
        }

        val created = entryToSend
        if (created != null) {
            val frame = try {
                val body = FcupCodec.buildEventRequest(
                    mediaUrl = url,
                    requestId = created.requestId,
                    sessionId = senderSessionId,
                )
                buildRequestFrame(body).also {
                    Logger.d("Reverse channel: ${created.kind} request prepared (id=${created.requestId}, ${body.size} B)")
                }
            } catch (error: Exception) {
                failPending(created, "reverse request could not be built")
                throw HlsBridgeException("reverse request could not be built")
            }

            // Serialize the final liveness check and write with close/delivery bookkeeping. A close
            // racing a fetch therefore cannot put a new, stale request onto the socket afterwards.
            val written = synchronized(lock) {
                if (closed || pending[created.requestId] !== created) {
                    false
                } else {
                    runCatching { writeFrame(frame) }.getOrDefault(false)
                }
            }
            if (!written) {
                failPending(created, "reverse channel write failed")
                throw HlsBridgeException("reverse channel write failed")
            }
            requestsSent.incrementAndGet()
            Logger.d("Reverse channel: ${created.kind} request sent (id=${created.requestId})")
        }
        return await(entry)
    }

    private fun buildRequestFrame(body: ByteArray): ByteArray {
        val head = buildString {
            append("POST ").append(FcupCodec.EVENT_PATH).append(" HTTP/1.1\r\n")
            append("X-Apple-Session-ID: ").append(sanitizeHeaderValue(senderSessionId)).append("\r\n")
            append("Content-Type: ").append(FcupCodec.EVENT_CONTENT_TYPE).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(head.size + body.size)
        System.arraycopy(head, 0, frame, 0, head.size)
        System.arraycopy(body, 0, frame, head.size, body.size)
        return frame
    }

    private fun sanitizeHeaderValue(value: String): String {
        val safe = value.take(FcupCodec.MAX_SESSION_ID_CHARS).filter { it.code in 0x20..0x7E }
        return safe.ifEmpty { "unknown" }
    }

    private fun await(entry: Pending): ByteArray = try {
        entry.future.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        val failure = HlsBridgeException("sender did not answer within ${requestTimeoutMs}ms")
        val removed = synchronized(lock) {
            if (pending[entry.requestId] === entry) {
                pending.remove(entry.requestId)
                inFlightByUrl.remove(entry.url, entry)
                true
            } else {
                false
            }
        }
        // Completing the shared future releases every loader joined to this URL. A late `/action`
        // then finds no request id and is acknowledged as ignored rather than satisfying a retry.
        if (removed) entry.future.completeExceptionally(failure)
        throw failure
    } catch (e: ExecutionException) {
        throw (e.cause as? HlsBridgeException) ?: HlsBridgeException("sender fetch failed")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw HlsBridgeException("fetch interrupted")
    }

    /**
     * Delivers one decoded `POST /action` reply to the request it answers.
     *
     * Session id, request id and URL are checked before bytes are handed to a waiter. A successful
     * fetch status may be omitted/zero by a sender or use an ordinary HTTP 2xx status (including
     * 200); non-2xx statuses fail the fetch.
     */
    fun deliver(
        requestId: Long?,
        url: String?,
        statusCode: Int?,
        data: ByteArray?,
        senderSessionId: String?,
    ): Delivery {
        if (this.senderSessionId.isNotEmpty() && senderSessionId != this.senderSessionId) {
            return Delivery(false, 400, "session mismatch")
        }
        if (requestId == null) return Delivery(false, 400, "request id missing")

        var completion: Pending? = null
        var completedData: ByteArray? = null
        var completionFailure: HlsBridgeException? = null
        val result = synchronized(lock) {
            val entry = pending[requestId]
            if (entry == null) {
                repliesIgnored.incrementAndGet()
                return@synchronized Delivery(false, 200, "unknown or already-answered request id ignored")
            }
            if (url != null && url != entry.url) {
                repliesIgnored.incrementAndGet()
                return@synchronized Delivery(false, 200, "answer URL does not match the request; ignored")
            }

            pending.remove(requestId)
            inFlightByUrl.remove(entry.url, entry)
            completion = entry
            when {
                statusCode != null && statusCode != 0 && statusCode !in 200..299 -> {
                    completionFailure = HlsBridgeException("sender reported fetch status $statusCode")
                    Delivery(true, 200, "sender reported fetch status $statusCode")
                }
                data == null -> {
                    completionFailure = HlsBridgeException("answer carried no data")
                    Delivery(true, 200, "answer carried no data")
                }
                data.size > maxBodyBytes -> {
                    completionFailure = HlsBridgeException("answer exceeds the body limit")
                    Delivery(true, 200, "answer exceeds the body limit")
                }
                else -> {
                    completedData = data
                    repliesMatched.incrementAndGet()
                    Delivery(true, 200, "${entry.kind} delivered (${data.size} B)")
                }
            }
        }

        completion?.let { entry ->
            val failure = completionFailure
            if (failure != null) entry.future.completeExceptionally(failure)
            else completedData?.let(entry.future::complete)
        }
        return result
    }

    private fun failPending(entry: Pending, reason: String) {
        val removed = synchronized(lock) {
            if (pending[entry.requestId] === entry) {
                pending.remove(entry.requestId)
                inFlightByUrl.remove(entry.url, entry)
                true
            } else {
                false
            }
        }
        if (removed) entry.future.completeExceptionally(HlsBridgeException(reason))
    }

    /** Releases every waiter with [reason] and refuses further requests. Idempotent. */
    fun close(reason: String) {
        val abandoned: List<Pending>
        synchronized(lock) {
            if (closed) return
            closed = true
            closeReason = reason
            abandoned = pending.values.toList()
            pending.clear()
            inFlightByUrl.clear()
        }
        abandoned.forEach { it.future.completeExceptionally(HlsBridgeException("reverse channel closed: $reason")) }
        if (abandoned.isNotEmpty()) {
            Logger.d("Reverse channel closed with ${abandoned.size} unanswered request(s) ($reason)")
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
        const val DEFAULT_MAX_PENDING_REQUESTS = 12

        /** Shared across channels so a stale response id can never alias a new channel's waiter. */
        private val NEXT_REQUEST_ID = AtomicLong(0L)
    }
}
