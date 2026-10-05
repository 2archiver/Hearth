package com.phairplay.airplay

import com.phairplay.airplay.handshake.FcupCodec
import com.phairplay.util.Logger
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** A sender-mediated fetch that failed. The message is URL-free and safe for the player/trace. */
internal class HlsBridgeException(message: String) : IOException(message)

/**
 * The PTTH reverse-HTTP channel: how the receiver asks the *sender* to fetch a URL on its behalf.
 *
 * WHY IT IS A CLASS OF ITS OWN: this channel is the transport half of the FCUP exchange. The
 * sender upgraded one of its connections to `PTTH/1.0` with `POST /reverse`; from then on the
 * receiver may write plain HTTP requests to that socket:
 *
 * ```
 *   POST /event HTTP/1.1
 *   X-Apple-Session-ID: <sender session id>
 *   Content-Type: text/x-apple-plist+xml
 *   Content-Length: N
 *
 *   <plist: unhandledURLRequest with FCUP_Response_RequestID and FCUP_Response_URL>
 * ```
 *
 * The sender answers out of band with `POST /action` on another connection, carrying the request id
 * it is answering. That means the transport has to:
 *
 *  - **bind ids to waiters** and hand each answer to the right waiter (out-of-order and duplicate
 *    answers are normal, an answer for a URL we did not ask for is not);
 *  - **bound every wait** — a player loader is blocked on this call, so a silent sender must become
 *    a stated failure, not a hang;
 *  - **serialize writes** — the channel shares its socket with the connection's own request loop;
 *  - **die cleanly** — when the session ends, every waiter is released with a reason instead of
 *    holding a player thread.
 *
 * Nothing here copies URL bytes into logs: the id, the byte count and the request kind are the only
 * facts recorded (the URL itself is already in the FCUP body, which is never logged).
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
    private data class Pending(val url: String, val kind: String, val future: CompletableFuture<ByteArray>)

    /** What one `POST /action` body did to this channel, plus what to answer the sender. */
    data class Delivery(val accepted: Boolean, val httpStatus: Int, val summary: String)

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val inFlightByUrl = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val nextRequestId = AtomicLong(0L)
    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var closeReason: String = "session ended"

    private val requestsSent = AtomicLong(0L)
    private val repliesMatched = AtomicLong(0L)
    private val repliesIgnored = AtomicLong(0L)

    val isWritable: Boolean get() = !closed

    /** Ids and counters for the connection log — never URLs. */
    fun stats(): String =
        "sent=${requestsSent.get()} matched=${repliesMatched.get()} ignored=${repliesIgnored.get()} " +
            "pending=${pending.size}"

    /**
     * Asks the sender to fetch [url] and blocks (bounded) for the answer.
     *
     * @param kind short label for diagnostics ("master playlist", "media playlist", "segment")
     * @throws HlsBridgeException when the channel is closed, the write fails, the sender answers with
     *   an error status, the answer exceeds [maxBodyBytes], or the wait times out
     */
    @Throws(HlsBridgeException::class)
    fun fetch(url: String, kind: String): ByteArray {
        // One round trip per URL even when two loaders ask at once: a player that races a playlist
        // refresh must not double the request load on the sender.
        inFlightByUrl[url]?.let { return await(it, url) }

        val requestId: Long
        val entry: Pending
        synchronized(lock) {
            if (closed) throw HlsBridgeException("reverse channel closed ($closeReason)")
            if (pending.size >= maxPendingRequests) {
                throw HlsBridgeException("too many unanswered reverse requests")
            }
            requestId = nextRequestId.incrementAndGet()
            entry = Pending(url, kind, CompletableFuture())
            pending[requestId] = entry
            inFlightByUrl[url] = entry.future
        }
        val body = FcupCodec.buildEventRequest(url = url, requestId = requestId, sessionId = senderSessionId)
        val frame = buildRequestFrame(body)
        val written = runCatching { writeFrame(frame) }.getOrDefault(false)
        if (!written) {
            forget(requestId, url)
            throw HlsBridgeException("reverse channel write failed")
        }
        requestsSent.incrementAndGet()
        Logger.d("Reverse channel: $kind request sent (id=$requestId, ${body.size} B)")
        return await(entry.future, url)
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
        // The session id reaches us from a request header; a value carrying CR/LF would let the
        // sender's own header split our request frame. Keep the printable, non-CR/LF prefix.
        val safe = value.take(FcupCodec.MAX_SESSION_ID_CHARS).filter { it.code in 0x20..0x7E }
        return safe.ifEmpty { "unknown" }
    }

    private fun await(future: CompletableFuture<ByteArray>, url: String): ByteArray = try {
        future.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        forgetUrl(url)
        throw HlsBridgeException("sender did not answer within ${requestTimeoutMs}ms")
    } catch (e: ExecutionException) {
        throw (e.cause as? HlsBridgeException) ?: HlsBridgeException("sender fetch failed")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw HlsBridgeException("fetch interrupted")
    }

    /**
     * Delivers one decoded `POST /action` reply to the request it answers.
     *
     * The sender's own `X-Apple-Session-ID` must match — a reply for another playback session is
     * refused, never applied. Ids are matched exactly; an answer whose URL differs from the request
     * it claims to answer is ignored (and counted) rather than completed with the wrong bytes.
     */
    fun deliver(
        requestId: Long?,
        url: String?,
        statusCode: Int?,
        data: ByteArray?,
        senderSessionId: String?,
    ): Delivery {
        if (senderSessionId != null && senderSessionId != this.senderSessionId) {
            return Delivery(false, 400, "session mismatch")
        }
        if (requestId == null) return Delivery(false, 400, "request id missing")
        val entry = pending[requestId]
        if (entry == null) {
            repliesIgnored.incrementAndGet()
            // Late answer after a timeout, or a duplicate: legitimate traffic, so acknowledge it.
            return Delivery(false, 200, "unknown or already-answered request id ignored")
        }
        if (url != null && url != entry.url) {
            repliesIgnored.incrementAndGet()
            return Delivery(false, 200, "answer URL does not match the request; ignored")
        }
        if (statusCode != null && statusCode != 0) {
            pending.remove(requestId)
            inFlightByUrl.remove(entry.url)
            entry.future.completeExceptionally(HlsBridgeException("sender reported fetch status $statusCode"))
            return Delivery(true, 200, "sender reported fetch status $statusCode")
        }
        if (data == null) {
            pending.remove(requestId)
            inFlightByUrl.remove(entry.url)
            entry.future.completeExceptionally(HlsBridgeException("answer carried no data"))
            return Delivery(true, 200, "answer carried no data")
        }
        if (data.size > maxBodyBytes) {
            pending.remove(requestId)
            inFlightByUrl.remove(entry.url)
            entry.future.completeExceptionally(HlsBridgeException("answer exceeds the body limit"))
            return Delivery(true, 200, "answer exceeds the body limit")
        }
        pending.remove(requestId)
        inFlightByUrl.remove(entry.url)
        repliesMatched.incrementAndGet()
        entry.future.complete(data)
        return Delivery(true, 200, "${entry.kind} delivered (${data.size} B)")
    }

    private fun forget(requestId: Long, url: String) {
        pending.remove(requestId)
        inFlightByUrl.remove(url)
    }

    private fun forgetUrl(url: String) {
        val entry = pending.entries.firstOrNull { it.value.url == url } ?: return
        pending.remove(entry.key)
        inFlightByUrl.remove(url)
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
    }
}
