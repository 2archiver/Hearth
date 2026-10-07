package com.phairplay.airplay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Data source for AirPlay video: sender-mediated `hearth-hls://` resources go through the existing
 * reverse-channel bridge; direct `http(s)` resources are fetched by Media3. Cleartext is permitted
 * only for a host that resolves exclusively to local-unicast addresses.
 *
 * Bridge bodies are fetched whole (FCUP has no byte-range request) and the requested window is
 * sliced locally, so seeks and `#EXT-X-BYTERANGE` still use the same player timeline.
 */
@UnstableApi
internal class HearthUrlVideoDataSource(
    private val sessionSource: UrlVideoSessionSource?,
    private val traceContext: () -> UrlVideoTraceContext?,
    private val requestSequence: AtomicLong,
) : DataSource {

    private val delegate = HearthRedirectGuardedHttpDataSource()
    private var uri: Uri? = null
    private var body: ByteArray? = null
    private var readPosition = 0
    private var readEnd = 0
    private var activeRequestId = 0L
    private var requestStartedNanos = 0L
    private var activeRoute = ""
    private var activeResourceType = "media"
    private var activeResponseCode: Int? = null
    private var activeContentType = "unknown"
    private var bytesRead = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        body = null
        readPosition = 0
        readEnd = 0
        activeRequestId = requestSequence.incrementAndGet()
        requestStartedNanos = System.nanoTime()
        activeResourceType = resourceType(dataSpec.uri)
        activeResponseCode = null
        activeContentType = "unknown"
        bytesRead = 0L

        if (isBridgeUri(dataSpec.uri)) {
            activeRoute = "sender-reverse"
            val source = sessionSource
                ?: throw IOException("sender-mediated media was requested without a session bridge")
            try {
                val bytes = source.open(dataSpec.uri.toString())
                val from = dataSpec.position.coerceIn(0L, bytes.size.toLong()).toInt()
                val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    (bytes.size - from).toLong()
                } else {
                    dataSpec.length
                }
                readPosition = from
                readEnd = (from + requested).coerceAtMost(bytes.size.toLong()).toInt()
                body = bytes
                trace(
                    "Media request id=$activeRequestId route=$activeRoute resource=$activeResourceType " +
                        "bodyBytes=${bytes.size} duration=${elapsedMillis()}ms",
                    AirPlayTrace.Kind.INFO,
                )
                return (readEnd - from).toLong()
            } catch (error: Exception) {
                trace(
                    "Media request id=$activeRequestId route=$activeRoute resource=$activeResourceType " +
                        "failed (${error.javaClass.simpleName}) duration=${elapsedMillis()}ms",
                    AirPlayTrace.Kind.FAILURE,
                )
                throw IOException("sender-mediated media request failed", error)
            }
        }

        val scheme = dataSpec.uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            activeRoute = "unsupported"
            trace(
                "Media request id=$activeRequestId blocked: unsupported source scheme",
                AirPlayTrace.Kind.FAILURE,
            )
            throw IOException("only HTTP(S) direct media sources are supported")
        }
        activeRoute = if (scheme == "http") "direct-http" else "direct-https"
        if (scheme == "http" && !LocalMediaAddressPolicy.allowsCleartextHost(dataSpec.uri.host)) {
            trace(
                "Media request id=$activeRequestId route=$activeRoute resource=$activeResourceType " +
                    "blocked: cleartext host is not a local sender",
                AirPlayTrace.Kind.FAILURE,
            )
            throw IOException("cleartext media host is not a local network sender")
        }

        return try {
            val length = delegate.open(dataSpec)
            activeResponseCode = delegate.getResponseCode()
            activeContentType = contentType(delegate.getResponseHeaders())
            trace(
                "Media request id=$activeRequestId route=$activeRoute resource=$activeResourceType " +
                    "status=${activeResponseCode ?: "unknown"} type=$activeContentType " +
                    "declaredBytes=$length duration=${elapsedMillis()}ms",
                if (activeResponseCode != null && activeResponseCode!! >= 400) {
                    AirPlayTrace.Kind.FAILURE
                } else {
                    AirPlayTrace.Kind.REQUEST
                },
            )
            length
        } catch (error: Exception) {
            val status = delegate.getResponseCode().takeIf { it in 100..599 }
            activeResponseCode = status
            trace(
                "Media request id=$activeRequestId route=$activeRoute resource=$activeResourceType " +
                    (status?.let { "status=$it " } ?: "") +
                    "failed (${error.javaClass.simpleName}) duration=${elapsedMillis()}ms",
                AirPlayTrace.Kind.FAILURE,
            )
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = body
        val result = if (bytes != null) {
            if (readPosition >= readEnd) C.RESULT_END_OF_INPUT
            else {
                val count = minOf(length, readEnd - readPosition)
                System.arraycopy(bytes, readPosition, buffer, offset, count)
                readPosition += count
                count
            }
        } else {
            delegate.read(buffer, offset, length)
        }
        if (result > 0) bytesRead += result
        return result
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val requestId = activeRequestId
        if (requestId != 0L) {
            trace(
                "Media request id=$requestId route=$activeRoute resource=$activeResourceType " +
                    "status=${activeResponseCode ?: "body"} type=$activeContentType " +
                    "readBytes=$bytesRead duration=${elapsedMillis()}ms",
                AirPlayTrace.Kind.INFO,
            )
        }
        body = null
        uri = null
        activeRequestId = 0L
        runCatching { delegate.close() }
    }

    private fun elapsedMillis(): Long =
        ((System.nanoTime() - requestStartedNanos).coerceAtLeast(0L) / 1_000_000L)

    private fun contentType(headers: Map<String, List<String>>): String {
        val raw = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return raw?.takeIf { MEDIA_TYPE_PATTERN.matches(it) }?.take(96) ?: "unknown"
    }

    private fun resourceType(value: Uri): String {
        val path = value.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
        return when {
            path.endsWith(".m3u8") -> "playlist"
            path.endsWith(".mpd") -> "manifest"
            path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".mp4") -> "segment"
            path.endsWith(".key") -> "key"
            else -> "media"
        }
    }

    private fun isBridgeUri(value: Uri): Boolean =
        value.scheme?.equals(SenderMediatedHlsBridge.SCHEME, ignoreCase = true) == true

    private fun trace(message: String, kind: AirPlayTrace.Kind) {
        val context = traceContext()
        AirPlayTrace.record(
            message = message,
            sessionId = context?.sessionId,
            connectionId = context?.connectionId,
            role = context?.role,
            kind = kind,
        )
    }

    private companion object {
        val MEDIA_TYPE_PATTERN = Regex("[a-z0-9.+_-]+/[a-z0-9.+_-]+")
    }
}

/** Factory of [HearthUrlVideoDataSource]; one data source per open, as Media3 expects. */
@UnstableApi
internal class HearthUrlVideoDataSourceFactory(
    private val sessionSource: UrlVideoSessionSource?,
    private val traceContext: () -> UrlVideoTraceContext?,
    private val requestSequence: AtomicLong,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        HearthUrlVideoDataSource(sessionSource, traceContext, requestSequence)
}
