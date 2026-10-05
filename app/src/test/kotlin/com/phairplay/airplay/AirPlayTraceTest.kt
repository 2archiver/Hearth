package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AirPlayTraceTest — privacy and bounds regressions for the diagnostics trace.
 *
 * WHY: the trace is the thing a person reads off the TV (and can export into a bug report), so it
 * must never carry a signed media URL, a keystream/device identifier, an address or a raw token —
 * and it must stay bounded so a long session cannot grow it without limit.
 */
class AirPlayTraceTest {

    @Before
    fun clearTrace() {
        AirPlayTrace.clear()
    }

    @Test
    fun `media URLs and query strings are never stored in a trace entry`() {
        AirPlayTrace.record("URL video failed: https://cdn.example.test/movie.mp4?token=secret-value")

        val message = AirPlayTrace.entries.value.single().message
        assertTrue("the location must be redacted: $message", "redacted" in message)
        assertFalse("the host must not survive: $message", "cdn.example.test" in message)
        assertFalse("the query must not survive: $message", "secret-value" in message)
    }

    @Test
    fun `addresses, identifiers and token pairs are redacted`() {
        val redacted = AirPlayTrace.redact(
            "from 192.168.1.57 device 3f2504e0-4f89-11d3-9a0c-0305e82c3301 " +
                "mac aa:bb:cc:dd:ee:ff token=abc123"
        )

        assertFalse("IPv4 must be redacted: $redacted", "192.168.1.57" in redacted)
        assertFalse("UUID must be redacted: $redacted", "3f2504e0-4f89-11d3-9a0c-0305e82c3301" in redacted)
        assertFalse("MAC must be redacted: $redacted", "aa:bb:cc:dd:ee:ff" in redacted)
        assertFalse("token value must be redacted: $redacted", "abc123" in redacted)
    }

    @Test
    fun `entries are bounded and long messages are truncated`() {
        val long = "word ".repeat(400) // over MAX_MESSAGE_CHARS, and no 40-char opaque run
        AirPlayTrace.record(long)
        val message = AirPlayTrace.entries.value.single().message
        assertEquals(512, message.length)

        repeat(300) { index -> AirPlayTrace.record("step $index") }
        assertEquals(256, AirPlayTrace.entries.value.size)
        // The oldest entries fall out first, so the newest message is still the last one.
        assertEquals("step 299", AirPlayTrace.latest())
    }

    @Test
    fun `exportText is UTC-stamped, redacted and reports the last failure`() {
        AirPlayTrace.record("Media https://cdn.example.test/movie.mp4?token=x failed", kind = AirPlayTrace.Kind.FAILURE)
        AirPlayTrace.record(
            "Session claim accepted",
            sessionId = "S2",
            connectionId = "C7",
            role = AirPlayConnectionRole.CONTROL.name,
        )

        val export = AirPlayTrace.exportText(mapOf("media url" to "https://cdn.example.test/movie.mp4"))

        assertTrue(export.startsWith("Hearth AirPlay diagnostics"))
        assertTrue("export must be UTC labelled", "UTC" in export)
        assertTrue("session label should be kept", "session=S2" in export)
        assertFalse("no media location may leave the device", "cdn.example.test" in export)
        assertTrue(export.contains("Last failure: Media https://[media location redacted]"))
        assertNotNull(AirPlayTrace.lastFailure())
    }
}
