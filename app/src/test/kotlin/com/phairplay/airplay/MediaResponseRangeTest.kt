package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** HTTP seek/byte-range regressions for direct Safari MP4 and HLS loading. */
class MediaResponseRangeTest {
    @Test fun acceptsSeekAndShortFinalRange() {
        assertEquals(100L, MediaResponseRange.partialLength("bytes 900-999/1000", 900L))
        assertEquals(100L, MediaResponseRange.readLength(1024L, 100L))
        assertEquals(100L, MediaResponseRange.readLength(-1L, 100L))
        assertEquals(1024L, MediaResponseRange.readLength(1024L, -1L))
        assertEquals(-1L, MediaResponseRange.readLength(-1L, -1L))
    }
    @Test fun acceptsUnknownTotalAndZeroWindow() {
        assertEquals(10L, MediaResponseRange.partialLength("bytes 0-9/*", 0L))
        assertEquals(0L, MediaResponseRange.readLength(1024L, 0L))
    }
    @Test fun rejectsWrongOffsetsTruncationAndOverflow() {
        listOf(null, "", "bytes 0-99/1000", "bytes 900-899/1000",
            "bytes 900-1000/1000", "bytes 900-9223372036854775807/*",
            "bytes 900-999/999999999999999999999").forEach { value ->
            try {
                MediaResponseRange.partialLength(value, 900L)
                throw AssertionError("Invalid partial response was accepted")
            } catch (_: IOException) { /* Fail before decoding misleading bytes. */ }
        }
    }
}
