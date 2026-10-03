package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AirPlayTrace — a short, human-readable history of what a sender actually did when it tried to
 * connect, kept in memory and shown on the TV.
 *
 * WHY: "the iPhone can't connect" is a report with no information in it, and the receiver's log
 * lives in `adb logcat`, which is exactly the thing a person sitting in front of a Google TV does
 * not have. The steps that matter are short and few — a connection arrived, discovery was answered,
 * pairing succeeded or was rejected, the key exchange ran, a stream was set up, a frame arrived —
 * so recording them costs nothing and turns a one-line bug report into a protocol trace.
 *
 * It is deliberately *not* a debug log: entries are written for the person reading the TV, name no
 * secrets (no keys, no signatures, no PINs) and are capped so the list stays readable. Anything
 * verbose stays in [Logger].
 *
 * The receiver's service exposes [entries] as a StateFlow so the UI can show them live.
 */
object AirPlayTrace {

    /** One step: when it happened (wall clock) and what happened, in plain language. */
    data class Entry(val atMillis: Long, val message: String) {
        /** `14:32:07` — the clock face a user can line up with the moment they tapped the phone. */
        fun timeLabel(): String = TIME_FORMAT.format(Date(atMillis))
    }

    /** Newest last, so the list reads like the connection itself. */
    private val buffer = ArrayDeque<Entry>()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** The recorded steps, oldest first. Observable — the UI collects this. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * Records one step. Called from receiver, mDNS and RTSP threads, so it takes a lock; the work
     * inside is an append and a StateFlow write.
     */
    fun record(message: String) {
        Logger.d("AirPlay trace: $message")
        val entry = Entry(System.currentTimeMillis(), message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    /** The most recent step, or null when nothing has happened yet. */
    fun latest(): String? = entries.value.lastOrNull()?.message

    /** Empties the history — used when the receivers restart, so a stale trace is not shown. */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private const val MAX_ENTRIES = 40

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
}
