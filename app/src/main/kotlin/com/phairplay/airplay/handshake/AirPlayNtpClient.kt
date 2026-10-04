package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.net.SocketTimeoutException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * AirPlayNtpClient — the receiver side of AirPlay 2 NTP timing.
 *
 * Unlike legacy AirPlay (where the sender probes the receiver), AirPlay 2 mirroring requires
 * the RECEIVER to actively poll the sender's timing port. macOS waits for this timing exchange
 * to begin before it will send the video stream SETUP, so without it the session stalls right
 * after the key exchange.
 *
 * We open a local UDP socket (its port is returned as `timingPort` in the SETUP response) and
 * periodically send a 32-byte NTP request to the sender, draining the replies. Precise clock
 * sync isn't required to start mirroring (frames render on arrival), so we keep this minimal —
 * the goal is to satisfy macOS that timing is live.
 *
 * Reference: RPiPlay lib/raop_ntp.c (raop_ntp_thread).
 */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    private val socket = DatagramSocket()      // OS-assigned local port
    @Volatile private var running = false
    private var timingJob: Job? = null
    @Volatile var clockOffsetMicros: Long = 0L
        private set

    /** Local UDP port to advertise to macOS as the receiver's timingPort. */
    val localPort: Int get() = socket.localPort

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (running || socket.isClosed) return
        if (remoteTimingPort !in 1..65535) {
            Logger.i("NTP client: remoteTimingPort=$remoteTimingPort (timing disabled by sender)")
            return
        }
        running = true
        socket.soTimeout = BURST_RECV_TIMEOUT_MS
        timingJob = scope.launch(Dispatchers.IO) { loop() }
        Logger.i("NTP client → [$remoteAddress]:$remoteTimingPort, local timing port $localPort")
    }

    fun stop() {
        running = false
        timingJob?.cancel()
        timingJob = null
        runCatching { socket.close() }
    }

    private suspend fun loop() {
        // request: [0]=0x80 (RTP), [1]=0xd2 (timing request), [2-3]=0x0007,
        // [8-15]=prev client transmit timestamp, [16-23]=prev server receive timestamp,
        // [24-31]=current send NTP time (UxPlay lib/raop_ntp.c raop_ntp_thread).
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var rxCount = 0
        var burstCount = 0
        while (running && currentCoroutineContext().isActive) {
            val isBurst = burstCount < NTP_BURST_LIMIT
            burstCount++
            try {
                socket.soTimeout = if (isBurst) BURST_RECV_TIMEOUT_MS else STEADY_RECV_TIMEOUT_MS
                val sendTimeMs = System.currentTimeMillis()
                putNtpTimestamp(request, 24, sendTimeMs)
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent to sender"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    val recvTimeMs = System.currentTimeMillis()
                    if (rx.address == remoteAddress && rx.port == remoteTimingPort &&
                        NtpReplyValidator.matchesRequest(response, rx.length, request)) {
                        val t1Us = readNtpMicros(response, 8)
                        val t2Us = readNtpMicros(response, 16)
                        val t3Us = readNtpMicros(response, 24)
                        val t4Us = recvTimeMs * 1000L
                        clockOffsetMicros = ((t2Us - t1Us) + (t3Us - t4Us)) / 2L
                        // Copy client's transmit timestamp (response[24..31]) into request[8..15]
                        // and record our arrival timestamp in request[16..23] for the next request
                        // (matches UxPlay lib/raop_ntp.c lines 693-697).
                        System.arraycopy(response, 24, request, 8, 8)
                        putNtpTimestamp(request, 16, recvTimeMs)
                    }
                    if (rxCount < 4) {
                        Logger.i("NTP RX[$rxCount] ${rx.length}B type=0x${(response[1].toInt() and 0xFF).toString(16)}: " +
                            (0 until minOf(rx.length, 32)).joinToString(" ") { "%02x".format(response[it]) })
                        rxCount++
                    }
                } catch (_: SocketTimeoutException) {
                    // A missed timing reply is expected on a busy network; retry next tick.
                }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            val sleepMs = if (isBurst) BURST_POLL_INTERVAL_MS else STEADY_POLL_INTERVAL_MS
            delay(sleepMs)
        }
    }

    /** Writes a 64-bit NTP timestamp (seconds since 1900 + 32-bit fraction) big-endian. */
    internal fun putNtpTimestamp(buf: ByteArray, off: Int, epochMillis: Long) {
        val seconds = epochMillis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (epochMillis % 1000) * (1L shl 32) / 1000
        writeUint32(buf, off, seconds)
        writeUint32(buf, off + 4, fraction)
    }

    /** Reads a 64-bit big-endian NTP timestamp as Unix epoch microseconds. */
    internal fun readNtpMicros(buf: ByteArray, off: Int): Long {
        val seconds = readUint32(buf, off) - NTP_EPOCH_OFFSET
        val fraction = readUint32(buf, off + 4)
        val micros = (fraction * 1_000_000L) ushr 32
        return seconds * 1_000_000L + micros
    }

    private fun readUint32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun writeUint32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    companion object {
        internal const val NTP_EPOCH_OFFSET = 2208988800L   // seconds between 1900 and 1970
        internal const val NTP_BURST_LIMIT = 8              // UxPlay raop_ntp.c: 8 fast initial polls
        internal const val BURST_POLL_INTERVAL_MS = 250L    // 0.25s during initial NTP burst
        internal const val STEADY_POLL_INTERVAL_MS = 3000L  // 3.0s steady-state interval
        private const val BURST_RECV_TIMEOUT_MS = 300
        private const val STEADY_RECV_TIMEOUT_MS = 1000
    }
}
