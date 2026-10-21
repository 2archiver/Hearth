package com.phairplay.diagnostics

import com.phairplay.airplay.AirPlayTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Severity of one activity-log entry.
 *
 * The definitions are deliberately narrow, because "ERROR" that also means "the user pressed Stop"
 * is the reason the 2.0 connection log was unreadable:
 *
 *  - [DEBUG]    — detail useful while diagnosing; dropped unless [ActivityLog.debugEnabled].
 *  - [INFO]     — something happened as designed (receiver started, sender connected, casting began).
 *  - [WARNING]  — degraded but still running: a dropped frame burst, a decoder rebuilt, a reconnect.
 *  - [ERROR]    — one operation failed; the receiver can continue (a `/play` that could not start,
 *                 a failed discovery registration).
 *  - [CRITICAL] — the receiver crashed or is unrecoverable and needs the user to intervene
 *                 (receiver thread died, service failed to start). A user-requested Stop is INFO.
 *
 * [label] is what the UI prints, so WARNING and CRITICAL are distinguishable by their text alone
 * and never by colour only.
 */
enum class Severity(val label: String, val rank: Int) {
    DEBUG("DEBUG", 0),
    INFO("INFO", 1),
    WARNING("WARN", 2),
    ERROR("ERROR", 3),
    CRITICAL("CRIT", 4);
}

/** One immutable, already-redacted activity entry. */
data class ActivityEntry(
    /** Wall-clock milliseconds since the epoch, for the local timestamp shown to the user. */
    val atMillis: Long,
    val severity: Severity,
    val subsystem: String,
    val message: String,
    /** Optional technical detail (stack frame, negotiated codec, port) shown when expanded. */
    val details: String? = null,
) {
    // SimpleDateFormat is not thread-safe; the formatter is shared, so access is synchronized.
    fun timeLabel(): String = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(atMillis)) }

    /** One line for the Home footer and the collapsed dialog row. */
    fun summaryLine(): String = "${timeLabel()}  ${severity.label}  $subsystem  $message"

    companion object {
        // Bounded and locale-fixed: the label is a timestamp, not a formatted number.
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/**
 * ActivityLog — the readable, chronological, severity-based activity view.
 *
 * WHY A SECOND LOG: [AirPlayTrace] records the protocol handshake in the order it happened and is
 * optimised for "what did the AirPlay request do". That is not an activity log: it has no severities,
 * no non-AirPlay subsystems, and its entries are request-shaped. This object is the user-facing
 * history: receiver start/stop, discovery, client connect/disconnect, session negotiation, casting,
 * decoder fallback, buffering, desync, network loss and fatal failures.
 *
 * GUARANTEES
 *  - **Bounded:** at most [MAX_ENTRIES] entries in memory and on disk. No storage growth.
 *  - **Sanitised:** every message and detail is passed through the same redaction as the exported
 *    AirPlay trace (URLs, IPs, MACs, tokens, identifiers), both when recorded and again on export.
 *  - **Asynchronous:** disk writes happen on one daemon thread, debounced by [FLUSH_DELAY_MS];
 *    ERROR and CRITICAL flush immediately so a crash still leaves the cause on disk. No caller ever
 *    blocks on I/O, and no per-frame event is recorded.
 *  - **Survives a restart:** the bounded history is reloaded by [init] before the first session.
 *
 * The object works without Android, which is what lets the severity/filter/redaction behaviour be
 * covered by plain JVM tests: pass a [Store] to [init] instead of a Context.
 */
object ActivityLog {

    /** How many entries the in-memory ring and the on-disk history keep. */
    const val MAX_ENTRIES = 300

    /** Coalescing delay for non-critical disk writes. */
    private const val FLUSH_DELAY_MS = 2_000L

    /** DEBUG entries are stored only when this is on (set from `BuildConfig.DEBUG`). */
    @Volatile
    var debugEnabled: Boolean = false

    private val buffer = ArrayDeque<ActivityEntry>()
    private val lock = Any()

    /** Persistence backend. `null` means memory-only (unit tests, or before [init]). */
    @Volatile
    private var store: Store? = null

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /** Guards against queuing several flushes for the same burst. */
    private val flushQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Storage seam so the log can be exercised without an Android runtime. */
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    /** Plain-file backend: one tab-separated entry per line in the app's private files dir. */
    class FileStore(private val file: File) : Store {
        override fun read(): String? = try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        }

        override fun write(text: String) {
            try {
                val parent = file.parentFile
                if (parent != null && !parent.isDirectory) parent.mkdirs()
                val temp = File(parent, file.name + ".tmp")
                temp.writeText(text)
                // Atomic replace keeps a crash mid-write from truncating the history.
                if (!temp.renameTo(file)) {
                    file.writeText(text)
                    temp.delete()
                }
            } catch (e: Exception) {
                // Diagnostics must never take the receiver down.
            }
        }
    }

    /**
     * Loads the persisted history and starts the writer. Safe to call more than once (the service
     * and the Application both do).
     */
    fun init(context: android.content.Context) {
        if (store != null) return
        init(FileStore(File(context.filesDir, "hearth-activity.log")))
    }

    /** Test/advanced entry point. Loads [store] and replaces any in-memory history. */
    @Synchronized
    fun init(store: Store) {
        this.store = store
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "hearth-activity-log").apply { isDaemon = true }
            }
        }
        val restored = parse(store.read().orEmpty())
        synchronized(lock) {
            buffer.clear()
            restored.takeLast(MAX_ENTRIES).forEach { buffer.addLast(it) }
        }
    }

    /**
     * Records one event. [subsystem] is a short stable token (`receiver`, `airplay`, `mirror`,
     * `decoder`, `audio`, `update`, `ui`); [message] is a short sentence a user can read.
     *
     * Never pass media URLs, headers or pairing values here — [redact] is a safety net, not a
     * licence to log secrets.
     */
    fun record(
        severity: Severity,
        subsystem: String,
        message: String,
        details: String? = null,
        cause: Throwable? = null,
    ) {
        if (severity == Severity.DEBUG && !debugEnabled) return
        val detailText = buildString {
            if (!details.isNullOrBlank()) append(details.trim())
            if (cause != null) {
                if (isNotEmpty()) append('\n')
                append(cause.javaClass.simpleName)
                cause.message?.let { append(": ").append(it) }
                cause.stackTrace.firstOrNull()?.let { frame ->
                    append("\n at ").append(frame.className).append('.').append(frame.methodName)
                    if (frame.lineNumber > 0) append(':').append(frame.lineNumber)
                }
            }
        }.takeIf { it.isNotBlank() }

        val entry = ActivityEntry(
            atMillis = System.currentTimeMillis(),
            severity = severity,
            subsystem = subsystem.take(MAX_SUBSYSTEM_CHARS),
            message = redact(message).take(MAX_MESSAGE_CHARS),
            details = detailText?.let { redact(it).take(MAX_DETAIL_CHARS) },
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        scheduleFlush(immediate = severity.rank >= Severity.ERROR.rank)
    }

    /** Chronological (oldest first) snapshot, optionally filtered. */
    fun entries(minSeverity: Severity? = null, subsystem: String? = null): List<ActivityEntry> {
        val snapshot = synchronized(lock) { buffer.toList() }
        return snapshot.filter { entry ->
            (minSeverity == null || entry.severity.rank >= minSeverity.rank) &&
                (subsystem == null || entry.subsystem == subsystem)
        }
    }

    /** Distinct subsystems present in the history, sorted for a stable filter row. */
    fun subsystems(): List<String> =
        synchronized(lock) { buffer.map { it.subsystem }.distinct().sorted() }

    /** Newest entry, for the Home footer. */
    fun latest(): ActivityEntry? = synchronized(lock) { buffer.lastOrNull() }

    /** Empties the in-memory history and rewrites the persisted file empty. */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        scheduleFlush(immediate = true)
    }

    /**
     * Sanitised, plain-text export. Copies the same protections as the AirPlay trace: no URLs,
     * addresses, tokens or identifiers leave the device unless the caller explicitly shares the
     * returned text.
     */
    fun exportText(metadata: Map<String, String> = emptyMap()): String {
        val snapshot = entries()
        val generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Hearth activity log (${snapshot.size}/$MAX_ENTRIES entries)")
            appendLine("Generated: $generated (local time)")
            appendLine("Severity: DEBUG < INFO < WARN (recoverable degradation) < ERROR (failed " +
                "operation) < CRIT (receiver crash, needs action)")
            metadata.toSortedMap().forEach { (key, value) ->
                appendLine("${redact(key.take(64))}: ${redact(value.take(MAX_DETAIL_CHARS))}")
            }
            appendLine("---")
            if (snapshot.isEmpty()) {
                appendLine("(no activity recorded)")
            }
            snapshot.forEach { entry ->
                append("${entry.timeLabel()}  ${entry.severity.label.padEnd(5)}  ")
                append(entry.subsystem.padEnd(8)).append("  ").append(redact(entry.message))
                append('\n')
                entry.details?.lineSequence()?.forEach { line ->
                    append("        ").append(redact(line)).append('\n')
                }
            }
        }
    }

    /** Public for privacy regression tests: the same filter the exported report uses. */
    fun redact(text: String): String = AirPlayTrace.redact(text)

    /**
     * Queues an immediate write without blocking the caller.
     *
     * Used by shutdown paths (service destruction): the main thread must not wait on disk I/O,
     * and the write is coalesced with any debounce already in flight.
     */
    fun flushAsync() {
        scheduleFlush(immediate = true)
    }

    /** Waits (bounded) for a queued write to land. Used by tests and by shutdown paths. */
    fun flushBlocking(timeoutMillis: Long = 2_000L) {
        val executor = scheduler ?: run { flushNow(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            try {
                flushNow()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Drops memory + persistence state. Test-only. */
    @Synchronized
    fun resetForTests() {
        scheduler?.shutdownNow()
        scheduler = null
        store = null
        debugEnabled = false
        synchronized(lock) { buffer.clear() }
        flushQueued.set(false)
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private fun scheduleFlush(immediate: Boolean) {
        val executor = scheduler ?: return
        if (!flushQueued.compareAndSet(false, true)) return
        try {
            executor.schedule(
                {
                    flushQueued.set(false)
                    flushNow()
                },
                if (immediate) 0L else FLUSH_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: Exception) {
            flushQueued.set(false)
        }
    }

    private fun flushNow() {
        val backend = store ?: return
        val snapshot = synchronized(lock) { buffer.toList() }
        backend.write(snapshot.joinToString("\n") { encode(it) })
    }

    private fun encode(entry: ActivityEntry): String =
        listOf(
            entry.atMillis.toString(),
            entry.severity.name,
            entry.subsystem,
            entry.message,
            entry.details.orEmpty(),
        ).joinToString("\t") { escape(it) }

    private fun parse(text: String): List<ActivityEntry> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 5)
            if (parts.size < 4) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val severity = runCatching { Severity.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            ActivityEntry(
                atMillis = at,
                severity = severity,
                subsystem = unescape(parts[2]),
                message = unescape(parts[3]),
                details = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
            )
        }.toList()
    }

    // Tab and newline are the record separators, so they are escaped inside field values.
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> { out.append('\n'); index += 2; continue }
                    't' -> { out.append('\t'); index += 2; continue }
                    '\\' -> { out.append('\\'); index += 2; continue }
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }

    private const val MAX_MESSAGE_CHARS = 400
    private const val MAX_DETAIL_CHARS = 2_000
    private const val MAX_SUBSYSTEM_CHARS = 16
}
