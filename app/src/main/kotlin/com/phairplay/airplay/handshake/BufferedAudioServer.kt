package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * BufferedAudioServer — receives the AirPlay 2 *buffered* (audio-only) stream, i.e. AirPlaying from
 * Apple Music / Control Center → TV WITHOUT screen mirroring (stream type 103).
 *
 * Unlike the realtime mirroring audio (type 96, UDP), buffered audio arrives over a TCP data
 * connection: the sender streams ahead and the receiver plays from a buffer, scheduled against an
 * anchor time provided by SETRATEANCHORTIME.
 *
 * STATUS: transport inspection only: no buffered-audio decryption, anchors, or playback.
 * Shairport Sync demonstrates open-source AirPlay 2 audio on supported Linux hosts;
 * that does not establish Android compatibility. Never report this socket as audible playback.

 */
class BufferedAudioServer {

    private val serverSocket = ServerSocket(0)   // OS-assigned port, returned in the SETUP response
    @Volatile private var running = false
    @Volatile private var client: Socket? = null

    /** TCP port macOS connects to for the buffered audio stream (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReceive() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        Logger.i("BufferedAudioServer stopped")
    }

    private fun runReceive() {
        try {
            Logger.i("BufferedAudioServer (audio-only) listening on TCP $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.w("Buffered audio transport connected; type 103 playback is unsupported")
            com.phairplay.diagnostics.ActivityLog.record(
                com.phairplay.diagnostics.Severity.WARNING, "audio",
                "Buffered AirPlay audio is not supported",
                details = "Type 103 transport connected; no audio decoder or presentation clock is active.",
            )
            val input = socket.getInputStream()
            val buf = ByteArray(16384)
            var reads = 0
            var totalBytes = 0L
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                totalBytes += n
                reads++
            }
            Logger.i("Buffered audio connection ended (total ${totalBytes}B over ${reads}+ reads)")
        } catch (e: Exception) {
            if (running) Logger.e("Buffered audio stream error", e)
        }
    }

}
