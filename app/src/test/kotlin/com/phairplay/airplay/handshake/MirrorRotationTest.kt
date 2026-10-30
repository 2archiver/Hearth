package com.phairplay.airplay.handshake

import java.net.ServerSocket
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Exercises the receiver's actual configuration handover without starting a decoder. */
class MirrorRotationTest {
    @Test
    fun `rotation config is adopted at next IDR and retained for later frames`() = withServer { server ->
        val portrait = MirrorCodecConfig(byteArrayOf(1), byteArrayOf(2))
        val landscape = MirrorCodecConfig(byteArrayOf(3), byteArrayOf(4))
        field(server, "activeCodecConfig", portrait)
        // Config arrival precedes the frame; no identical config/frame timestamp is required.
        field(server, "pendingConfig", PendingMirrorConfig(landscape, 1_000_000_000L))
        assertSame(landscape, select(server, 1_033_000_000L))
        assertNull(read(server, "pendingConfig"))
        assertSame(landscape, select(server, 1_500_000_000L))
        // Rotating back uses the same handover path.
        field(server, "pendingConfig", PendingMirrorConfig(portrait, 2_000_000_000L))
        assertSame(portrait, select(server, 2_033_000_000L))
    }

    @Test
    fun `rotation config survives an idle screen until the next IDR`() = withServer { server ->
        val active = MirrorCodecConfig(byteArrayOf(1), byteArrayOf(2))
        field(server, "activeCodecConfig", active)
        val rotated = MirrorCodecConfig(byteArrayOf(3), byteArrayOf(4))
        field(server, "pendingConfig", PendingMirrorConfig(rotated, 0L))
        assertSame(rotated, select(server, 5_000_000_001L))
        assertNull(read(server, "pendingConfig"))
    }

    @Test
    fun `rekey discards configuration from the old stream`() = withServer { server ->
        val active = MirrorCodecConfig(byteArrayOf(1), byteArrayOf(2))
        field(server, "activeCodecConfig", active)
        field(server, "pendingConfig", PendingMirrorConfig(MirrorCodecConfig(byteArrayOf(3), byteArrayOf(4)), 0L))
        server.rekey(2L)
        assertNull(read(server, "pendingConfig"))
        assertNull(select(server, 10_000_000_000L))
    }

    private fun withServer(block: (MirrorStreamServer) -> Unit) {
        val server = MirrorStreamServer(ByteArray(16), ByteArray(32), 1L, { null })
        try { block(server) } finally { (read(server, "serverSocket") as ServerSocket).close() }
    }

    private fun select(server: MirrorStreamServer, time: Long): Any? =
        MirrorStreamServer::class.java.getDeclaredMethod("configForKeyframe", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(server, time)

    private fun field(server: MirrorStreamServer, name: String, value: Any) {
        MirrorStreamServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(server, value)
    }

    private fun read(server: MirrorStreamServer, name: String): Any? =
        MirrorStreamServer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(server)
}
