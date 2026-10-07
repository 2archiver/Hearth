package com.phairplay.airplay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * HTTP data source that validates each redirect before following it. The stock
 * DefaultHttpDataSource follows same-protocol redirects inside HttpURLConnection, so a local
 * cleartext sender could otherwise redirect the request to a public HTTP host without this app
 * policy seeing the second URL.
 */
@UnstableApi
internal class HearthRedirectGuardedHttpDataSource : DataSource {
    private val transferListeners = CopyOnWriteArraySet<TransferListener>()
    private var connection: HttpURLConnection? = null
    private var inputStream: java.io.InputStream? = null
    private var activeDataSpec: DataSpec? = null
    private var currentUri: Uri? = null
    private var remainingBytes = C.LENGTH_UNSET.toLong()
    private var transferStarted = false

    private var responseCode = -1
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    fun getResponseCode(): Int = responseCode

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        close()
        responseCode = -1
        responseHeaders = emptyMap()
        activeDataSpec = dataSpec
        currentUri = dataSpec.uri
        notifyListeners { it.onTransferInitializing(this, dataSpec, true) }

        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) {
            throw IOException("direct media requests must use GET")
        }
        if (dataSpec.position < 0L || dataSpec.length < C.LENGTH_UNSET.toLong()) {
            throw IOException("direct media byte range is invalid")
        }

        val originalUri = try {
            URI(dataSpec.uri.toString())
        } catch (_: Exception) {
            throw IOException("direct media URL is invalid")
        }
        val originalScheme = originalUri.scheme?.lowercase(Locale.ROOT)
        MediaRedirectPolicy.rejectionReason(originalUri)?.let { reason ->
            throw IOException("direct media URL blocked: $reason")
        }
        var url = try {
            URL(dataSpec.uri.toString())
        } catch (_: Exception) {
            throw IOException("direct media URL is invalid")
        }
        var requestHeaders = dataSpec.httpRequestHeaders.toMutableMap().apply {
            keys.removeAll { it.equals("Range", ignoreCase = true) }
            keys.removeAll { it.equals("Accept-Encoding", ignoreCase = true) }
        }

        try {
            for (redirectCount in 0..MAX_REDIRECTS) {
                val nextConnection = url.openConnection() as? HttpURLConnection
                    ?: throw IOException("direct media connection is unsupported")
                connection = nextConnection
                nextConnection.instanceFollowRedirects = false
                nextConnection.connectTimeout = CONNECT_TIMEOUT_MS
                nextConnection.readTimeout = READ_TIMEOUT_MS
                nextConnection.requestMethod = "GET"
                requestHeaders.forEach { (name, value) ->
                    nextConnection.setRequestProperty(name, value)
                }
                nextConnection.setRequestProperty("Accept-Encoding", "identity")
                rangeHeader(dataSpec.position, dataSpec.length)?.let { range ->
                    nextConnection.setRequestProperty("Range", range)
                }

                val code = nextConnection.responseCode
                responseCode = code
                responseHeaders = headerMap(nextConnection)
                currentUri = Uri.parse(url.toString())

                if (code in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw IOException("direct media request exceeded the redirect limit")
                    }
                    val location = nextConnection.getHeaderField("Location")
                        ?: throw IOException("direct media redirect has no target")
                    val redirectedUrl = try {
                        URL(url, location)
                    } catch (_: Exception) {
                        throw IOException("direct media redirect target is invalid")
                    }
                    val redirectedUri = try {
                        URI(redirectedUrl.toString())
                    } catch (_: Exception) {
                        throw IOException("direct media redirect target is invalid")
                    }
                    MediaRedirectPolicy.rejectionReason(redirectedUri, originalScheme)?.let { reason ->
                        throw IOException("direct media redirect blocked: $reason")
                    }
                    if (!sameOrigin(url, redirectedUrl)) {
                        requestHeaders = requestHeaders.filterKeysNotSensitive()
                    }
                    nextConnection.disconnect()
                    connection = null
                    url = redirectedUrl
                    responseCode = -1
                    responseHeaders = emptyMap()
                    continue
                }

                if (code == HTTP_RANGE_NOT_SATISFIABLE &&
                    dataSpec.position == unsatisfiedRangeTotal(nextConnection)
                ) {
                    remainingBytes = 0L
                    nextConnection.disconnect()
                    connection = null
                    startTransfer(dataSpec)
                    return 0L
                }
                if (code !in 200..299) {
                    throw IOException("direct media request failed (HTTP $code)")
                }

                val skipped = if (code == HttpURLConnection.HTTP_OK && dataSpec.position > 0L) {
                    dataSpec.position
                } else {
                    0L
                }
                val rawStream = nextConnection.inputStream
                inputStream = rawStream
                if (skipped > 0L) skipFully(rawStream, skipped)
                val declaredLength = nextConnection.contentLengthLong
                remainingBytes = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    declaredLength >= 0L -> (declaredLength - skipped).coerceAtLeast(0L)
                    else -> C.LENGTH_UNSET.toLong()
                }
                startTransfer(dataSpec)
                return remainingBytes
            }
            throw IOException("direct media request exceeded the redirect limit")
        } catch (error: IOException) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw IOException("direct media request failed", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remainingBytes == 0L) return C.RESULT_END_OF_INPUT
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val allowed = if (remainingBytes == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), remainingBytes).toInt()
        }
        val read = stream.read(buffer, offset, allowed)
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (remainingBytes != C.LENGTH_UNSET.toLong()) remainingBytes -= read
        val spec = activeDataSpec
        if (spec != null && read > 0) {
            notifyListeners { it.onBytesTransferred(this, spec, true, read) }
        }
        return read
    }

    override fun close() {
        val spec = activeDataSpec
        val notifyEnd = transferStarted
        transferStarted = false
        try {
            runCatching { inputStream?.close() }
            runCatching { connection?.disconnect() }
        } finally {
            inputStream = null
            connection = null
            activeDataSpec = null
            currentUri = null
            responseHeaders = emptyMap()
            remainingBytes = C.LENGTH_UNSET.toLong()
            if (notifyEnd && spec != null) {
                notifyListeners { it.onTransferEnd(this, spec, true) }
            }
        }
    }

    private fun startTransfer(dataSpec: DataSpec) {
        transferStarted = true
        notifyListeners { it.onTransferStart(this, dataSpec, true) }
    }

    private inline fun notifyListeners(block: (TransferListener) -> Unit) {
        transferListeners.forEach { listener -> runCatching { block(listener) } }
    }

    private fun skipFully(stream: java.io.InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("direct media ended before requested byte range")
                remaining -= read
            }
        }
    }

    private fun rangeHeader(position: Long, length: Long): String? {
        if (length == 0L) return null
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        if (length == C.LENGTH_UNSET.toLong()) return "bytes=$position-"
        val end = try {
            Math.addExact(position, length - 1L)
        } catch (_: ArithmeticException) {
            throw IOException("direct media byte range is too large")
        }
        return "bytes=$position-$end"
    }

    private fun headerMap(connection: HttpURLConnection): Map<String, List<String>> =
        TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER).apply {
            connection.headerFields.forEach { (name, values) ->
                if (!name.isNullOrBlank()) put(name, values ?: emptyList())
            }
        }

    private fun unsatisfiedRangeTotal(connection: HttpURLConnection): Long? =
        UNSATISFIED_RANGE_REGEX.find(connection.getHeaderField("Content-Range").orEmpty())
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int = when {
        url.port >= 0 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun Map<String, String>.filterKeysNotSensitive(): MutableMap<String, String> =
        filterKeys { name -> name.lowercase(Locale.ROOT) !in SENSITIVE_HEADERS }.toMutableMap()

    private companion object {
        const val MAX_REDIRECTS = 20
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val SKIP_BUFFER_SIZE = 4 * 1024
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MULT_CHOICE,
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "cookie2",
            "x-api-key",
            "x-auth-token",
            "x-access-token",
        )
        val UNSATISFIED_RANGE_REGEX = Regex("(?i)^bytes\\s+\\*/(\\d+)$")
    }
}
