package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MediaSuccessLogTest {
    @Test fun `first read is visible then repeated reads are summarized every thirty seconds`() {
        val log = MediaSuccessLog()
        assertNotNull(log.completed("direct-https", "playlist", 100, 50, 0))
        assertNull(log.completed("direct-https", "segment", 200, 80, 1000))
        assertNull(log.completed("direct-https", "playlist", 100, 30, 29999))
        val summary = log.completed("direct-https", "segment", 300, 60, 30000)!!
        assertTrue(summary.contains("completed=3"))
        assertTrue(summary.contains("readBytes=600"))
        assertTrue(summary.contains("maxRead=80ms"))
        assertNull(log.completed("direct-https", "segment", 100, 20, 30001))
        val next = log.completed("direct-https", "segment", 100, 20, 60000)!!
        assertTrue(next.contains("completed=2"))
        assertTrue(next.contains("readBytes=200"))
    }
}
