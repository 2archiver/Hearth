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
    private val requestHeaders: (Uri) -> Map<String, String> = { emptyMap() },
    private val successLog: MediaSuccessLog = MediaSuccessLog(),
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
    private var requestFailed = false

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
        requestFailed = false

        if (isBridgeUri(dataSpec.uri)) {
            activeRoute = "sender-reverse"
            val source = sessionSource
                ?: throw IOException("sender-mediated media was requested without a session bridge")
            try {
                val bytes = source.open(dataSpec.uri.toString())
                if (dataSpec.position < 0L || dataSpec.position > bytes.size.toLong() ||
                    dataSpec.length < C.LENGTH_UNSET.toLong()
                ) throw IOException("sender-mediated byte range is outside the resource")
                val from = dataSpec.position.toInt()
                val available = bytes.size - from
                val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available.toLong()
                    else minOf(dataSpec.length, available.toLong())
                readPosition = from
                readEnd = from + requested.toInt()
                body = bytes
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
            val length = delegate.open(dataSpec, requestHeaders(dataSpec.uri))
            activeResponseCode = delegate.getResponseCode()
            activeContentType = contentType(delegate.getResponseHeaders())
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
        if (length == 0) return 0
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
            try {
                delegate.read(buffer, offset, length)
            } catch (error: IOException) {
                trace("Media request id=$activeRequestId route=$activeRoute read failed (${error.javaClass.simpleName})", AirPlayTrace.Kind.FAILURE)
                throw error
            }
        }
        if (result > 0) bytesRead += result
        return result
    }

    override fun getUri(): Uri? {
        val requestedUri = uri ?: return null
        // ExoPlayer resolves relative HLS segments against the final redirected playlist URI.
        // Sender-mediated bridge URIs are virtual and must remain rooted at their original URI.
        return if (isBridgeUri(requestedUri)) requestedUri else delegate.getUri() ?: requestedUri
    }

    // Media3 uses response metadata for HLS loads; the wrapper must preserve the HTTP contract.
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (body != null) emptyMap() else delegate.getResponseHeaders()

    override fun close() {
        val requestId = activeRequestId
        if (requestId != 0L && !requestFailed && bytesRead > 0L) {
            successLog.completed(activeRoute, activeResourceType, bytesRead, elapsedMillis(), System.nanoTime() / 1_000_000L)
                ?.let { trace(it, AirPlayTrace.Kind.INFO) }
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
        if (kind == AirPlayTrace.Kind.FAILURE) requestFailed = true
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
    private val requestHeaders: (Uri) -> Map<String, String> = { emptyMap() },
) : DataSource.Factory {
    private val successLog = MediaSuccessLog()
    override fun createDataSource(): DataSource =
        HearthUrlVideoDataSource(sessionSource, traceContext, requestSequence, requestHeaders, successLog)
}
