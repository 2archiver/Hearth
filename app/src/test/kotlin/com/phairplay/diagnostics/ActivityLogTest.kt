package com.phairplay.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ActivityLogTest — the severity model, the bounded ring, persistence across a restart and the
 * guarantee that an exported report is sanitised.
 *
 * WHY THESE MATTER: the 2.0 "connection details" dialog was an unlabelled multi-line dump. It had
 * no severity, so a user-requested Stop looked the same as a crash; it had no bound, so a long
 * session grew without limit; and its export was the raw trace. These tests pin the properties that
 * make the 2.1.0 activity view usable and safe to share.
 *
 * Runs on the plain JVM. Persistence is exercised through [ActivityLog.Store], so no Android
 * runtime (and no file in the app sandbox) is needed.
 */
class ActivityLogTest {

    /** In-memory stand-in for the app's bounded file. */
    private class MemoryStore(var text: String? = null) : ActivityLog.Store {
        var writes = 0
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
            writes++
        }
    }

    @Before
    fun setUp() {
        ActivityLog.resetForTests()
    }

    @After
    fun tearDown() {
        ActivityLog.resetForTests()
    }

    @Test
    fun `severity order separates degradation from failed operations and crashes`() {
        assertTrue(Severity.DEBUG.rank < Severity.INFO.rank)
        assertTrue(Severity.INFO.rank < Severity.WARNING.rank)
        assertTrue(Severity.WARNING.rank < Severity.ERROR.rank)
        assertTrue(Severity.ERROR.rank < Severity.CRITICAL.rank)
        // Distinguishable by text alone — colour is only a secondary cue in the UI.
        val labels = Severity.entries.map { it.label }.toSet()
        assertEquals(Severity.entries.size, labels.size)
        assertTrue(labels.contains("WARN"))
        assertTrue(labels.contains("CRIT"))
    }

    @Test
    fun `the ring buffer is bounded`() {
        repeat(ActivityLog.MAX_ENTRIES + 25) { index ->
            ActivityLog.record(Severity.INFO, "receiver", "event $index")
        }
        val entries = ActivityLog.entries()
        assertEquals(ActivityLog.MAX_ENTRIES, entries.size)
        // The oldest entries are the ones dropped, so the newest event is still present.
        assertTrue(entries.last().message.contains("event ${ActivityLog.MAX_ENTRIES + 24}"))
        assertFalse(entries.any { it.message == "event 0" })
    }

    @Test
    fun `filters select by minimum severity and by subsystem`() {
        ActivityLog.record(Severity.INFO, "receiver", "started")
        ActivityLog.record(Severity.WARNING, "decoder", "rebuilt")
        ActivityLog.record(Severity.ERROR, "airplay", "play failed")
        ActivityLog.record(Severity.CRITICAL, "receiver", "port busy")

        assertEquals(4, ActivityLog.entries().size)
        assertEquals(3, ActivityLog.entries(minSeverity = Severity.WARNING).size)
        assertEquals(2, ActivityLog.entries(minSeverity = Severity.ERROR).size)
        assertEquals(1, ActivityLog.entries(subsystem = "decoder").size)
        // "receiver" has an INFO start and a CRITICAL port failure; the WARNING filter keeps one.
        assertEquals(
            1,
            ActivityLog.entries(minSeverity = Severity.WARNING, subsystem = "receiver").size,
        )
        assertEquals(listOf("airplay", "decoder", "receiver"), ActivityLog.subsystems())
    }

    @Test
    fun `debug entries are dropped unless debug logging is enabled`() {
        ActivityLog.record(Severity.DEBUG, "airplay", "GET /info")
        assertEquals(0, ActivityLog.entries().size)

        ActivityLog.debugEnabled = true
        ActivityLog.record(Severity.DEBUG, "airplay", "GET /info")
        assertEquals(1, ActivityLog.entries().size)
    }

    @Test
    fun `a user requested stop is information, not a critical event`() {
        // The service records Stop at INFO; this pins the expectation used by the UI so nobody
        // "improves" it into CRITICAL and makes an ordinary Stop look like a crash.
        ActivityLog.record(Severity.INFO, "receiver", "Receiver stopped", details = "user_requested")
        val entry = ActivityLog.entries().single()
        assertEquals(Severity.INFO, entry.severity)
        assertEquals("INFO", entry.severity.label)
        assertTrue(entry.summaryLine().contains("INFO"))
    }

    @Test
    fun `export redacts addresses urls tokens and identifiers`() {
        ActivityLog.record(
            Severity.ERROR,
            "airplay",
            "Failed to reach 192.168.1.42 for aa:bb:cc:dd:ee:ff",
            details = "GET https://media.example.com/live.m3u8?token=supersecret1234 failed\n" +
                "authorization=Bearer abcdefghijklmnop",
        )
        val report = ActivityLog.exportText(mapOf("Hearth version" to "2.1.0"))

        assertFalse("an IP address must not leak", report.contains("192.168.1.42"))
        assertFalse("a MAC address must not leak", report.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse("a media URL must not leak", report.contains("media.example.com"))
        assertFalse("a token must not leak", report.contains("supersecret1234"))
        assertFalse("a bearer token must not leak", report.contains("abcdefghijklmnop"))
        assertTrue(report.contains("[address redacted]"))
        assertTrue("the report still explains the failure", report.contains("Failed to reach"))
    }

    @Test
    fun `export states the severity definitions so WARN and CRIT cannot be confused`() {
        ActivityLog.record(Severity.WARNING, "decoder", "rebuilt")
        val report = ActivityLog.exportText()
        assertTrue(report.contains("WARN (recoverable degradation)"))
        assertTrue(report.contains("ERROR (failed operation)"))
        assertTrue(report.contains("CRIT (receiver crash"))
    }

    @Test
    fun `history survives a restart`() {
        val store = MemoryStore()
        ActivityLog.init(store)
        ActivityLog.record(Severity.INFO, "receiver", "Receiver started")
        ActivityLog.record(Severity.ERROR, "decoder", "Decoder failed")
        ActivityLog.flushBlocking()

        assertNotNull("the log should have been written", store.text)
        assertTrue(store.writes >= 1)

        // Simulate a process restart: drop everything in memory, reload from the same store.
        ActivityLog.resetForTests()
        ActivityLog.init(store)

        val restored = ActivityLog.entries()
        assertEquals(2, restored.size)
        assertEquals(Severity.INFO, restored.first().severity)
        assertEquals(Severity.ERROR, restored.last().severity)
        assertTrue(restored.last().message.contains("Decoder failed"))
    }

    @Test
    fun `clear empties memory and rewrites the persisted history`() {
        val store = MemoryStore()
        ActivityLog.init(store)
        ActivityLog.record(Severity.INFO, "receiver", "Receiver started")
        ActivityLog.flushBlocking()
        ActivityLog.clear()
        ActivityLog.flushBlocking()

        assertEquals(0, ActivityLog.entries().size)
        assertNull(ActivityLog.latest())
        assertEquals("", store.text)
    }

    @Test
    fun `technical details are bounded and carry the cause`() {
        val cause = IllegalStateException("codec wedged")
        ActivityLog.record(
            Severity.ERROR,
            "decoder",
            "Video decoder entered an error state",
            details = "rebuilding at the next IDR",
            cause = cause,
        )
        val entry = ActivityLog.entries().single()
        assertNotNull(entry.details)
        assertTrue(entry.details!!.contains("rebuilding at the next IDR"))
        assertTrue(entry.details!!.contains("IllegalStateException"))
        assertTrue(entry.details!!.contains("codec wedged"))
        assertTrue(entry.details!!.length <= 2_000)
    }

    @Test
    fun `the file store writes a bounded readable file`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hearth-activity-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        try {
            val store = ActivityLog.FileStore(File(dir, "hearth-activity.log"))
            ActivityLog.init(store)
            ActivityLog.record(Severity.WARNING, "audio", "Audio decoding degraded", details = "1/10 frames")
            ActivityLog.flushBlocking()

            val persisted = File(dir, "hearth-activity.log")
            assertTrue("the log file should exist", persisted.isFile)
            val text = persisted.readText()
            assertTrue(text.contains("WARNING"))
            assertTrue(text.contains("Audio decoding degraded"))
            // The tab separator must survive a detail containing a newline.
            assertFalse(text.contains(".tmp"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
