package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.airplay.AirPlayIdentity
import com.phairplay.util.NetworkUtils
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class MirrorCapabilitiesTest {
    @Test fun `rotation is enabled in discovery and full capability response`() {
        val context = mockk<Context>()
        mockkObject(NetworkUtils)
        mockkObject(PairingKeys.Companion)
        try {
            every { NetworkUtils.getMacAddress() } returns "02:00:00:00:00:01"
            every { NetworkUtils.getPersistentUuid(context) } returns "receiver-test"
            every { PairingKeys.get(context) } returns PairingKeys.create(ByteArray(32))
            val info = PlistCodec.decode(InfoResponder.build(context))
            val mask = (info["features"] as Number).toLong()
            assertTrue("ScreenRotate must be enabled before iOS chooses a stream", mask and (1L shl 8) != 0L)
            val display = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals(true, display["rotation"])
            for (txt in listOf(AirPlayIdentity.airPlayTxt(context)["features"], AirPlayIdentity.raopTxt(context)["ft"])) {
                assertEquals(mask, txt!!.removePrefix("0x").toLong(16))
            }
            // Preserve the existing negotiated transport and pairing profile.
            assertEquals(0L, mask and ((1L shl 2) or (1L shl 12) or (1L shl 42)))
            assertTrue(mask and (1L shl 27) != 0L)
        } finally {
            unmockkObject(PairingKeys.Companion)
            unmockkObject(NetworkUtils)
        }
    }
}
