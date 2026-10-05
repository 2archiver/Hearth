package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlVideoPlaybackControllerTest {
    @Test
    fun `seconds seek and a late surface start video only after preparation and attachment`() {
        var surface: UrlVideoSurface? = null
        val fixture = Fixture { surface }

        fixture.controller.play("https://media.example/video.mp4", 42.5, seconds = true)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        assertEquals("https://media.example/video.mp4", backend.dataSource)
        assertEquals(0, backend.startCount)

        backend.firePrepared()
        fixture.scheduler.runCurrent()
        assertEquals(listOf(42_500), backend.seeks)
        assertEquals(0, backend.startCount)

        val lateSurface = FakeSurface()
        surface = lateSurface
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        assertEquals(1, backend.startCount)
        assertSame(lateSurface, backend.surfaces.last())
        assertTrue(fixture.controller.info()!!.readyToPlay)
        assertEquals(1.0, fixture.controller.info()!!.rate, 0.0)
    }

    @Test
    fun `pause and scrub received before prepare are retained`() {
        val surface = FakeSurface()
        val fixture = Fixture { surface }
        fixture.controller.play("https://media.example/video.mp4", 30.0, seconds = true)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()

        fixture.controller.setRate(0f)
        fixture.controller.scrub(5.25)
        fixture.scheduler.runCurrent()
        backend.firePrepared()
        fixture.scheduler.runCurrent()
        assertEquals(listOf(5_250), backend.seeks)

        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertEquals(0, backend.startCount)
        assertEquals(0.0, fixture.controller.info()!!.rate, 0.0)
        assertTrue(fixture.controller.info()!!.readyToPlay)

        fixture.controller.setRate(1f)
        fixture.scheduler.runCurrent()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertEquals(1, backend.startCount)
    }

    @Test
    fun `legacy fractional offset remains a fraction of duration`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.25)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.firePrepared()
        fixture.scheduler.runCurrent()

        assertEquals(listOf(22_500), backend.seeks)
    }

    @Test
    fun `surface loss pauses and replacement surfaces are attached`() {
        val firstSurface = FakeSurface()
        var surface: UrlVideoSurface? = firstSurface
        val fixture = Fixture { surface }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.firePrepared()
        fixture.scheduler.runCurrent()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertTrue(backend.playing)

        val replacement = FakeSurface()
        surface = replacement
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertSame(replacement, backend.surfaces.last())
        assertTrue(backend.playing)

        surface = null
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertNull(backend.surfaces.last())
        assertFalse(backend.playing)

        val resumedSurface = FakeSurface()
        surface = resumedSurface
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertSame(resumedSurface, backend.surfaces.last())
        assertTrue(backend.playing)
        assertEquals(2, backend.startCount)
    }

    @Test
    fun `callbacks from a replaced player are ignored`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/first.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val oldBackend = fixture.factory.players.single()
        val stalePrepared = oldBackend.savedPreparedListener!!
        val staleError = oldBackend.savedErrorListener!!
        val staleCompletion = oldBackend.savedCompletionListener!!

        fixture.controller.play("https://media.example/second.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val currentBackend = fixture.factory.players.last()
        assertTrue(oldBackend.released)

        stalePrepared()
        staleError(1, 2)
        staleCompletion()
        fixture.scheduler.runCurrent()

        assertFalse(currentBackend.preparedListenerWasCalled)
        assertEquals(0, currentBackend.startCount)
        assertEquals(0, fixture.endedCount)
        assertEquals("https://media.example/second.mp4", currentBackend.dataSource)
    }

    @Test
    fun `backend error releases player and ends the URL session once`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/broken.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        val stalePrepared = backend.savedPreparedListener!!

        assertTrue(backend.fireError(what = 100, extra = -2))
        fixture.scheduler.runCurrent()
        assertTrue(backend.released)
        assertEquals(1, fixture.endedCount)
        assertNull(fixture.controller.info())

        stalePrepared()
        fixture.scheduler.runCurrent()
        assertEquals(1, fixture.endedCount)
    }

    @Test
    fun `preparation timeout releases the backend`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/slow.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()

        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TIMEOUT_MS)

        assertTrue(backend.released)
        assertEquals(1, fixture.endedCount)
        assertNull(fixture.controller.info())
    }

    @Test
    fun `surface wait timeout releases a prepared player`() {
        val fixture = Fixture { null }
        fixture.controller.play("https://media.example/no-surface.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.firePrepared()
        fixture.scheduler.runCurrent()

        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TIMEOUT_MS)

        assertTrue(backend.released)
        assertEquals(1, fixture.endedCount)
        assertNull(fixture.controller.info())
    }

    @Test
    fun `rapid release and restart leaves only the newest backend active`() {
        var surface: UrlVideoSurface? = FakeSurface()
        val fixture = Fixture { surface }
        fixture.controller.play("https://media.example/old.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val oldBackend = fixture.factory.players.single()
        val stalePrepared = oldBackend.savedPreparedListener!!

        fixture.controller.release()
        fixture.controller.play("https://media.example/new.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val newBackend = fixture.factory.players.last()
        assertTrue(oldBackend.released)
        assertEquals(0, fixture.endedCount)

        stalePrepared()
        fixture.scheduler.runCurrent()
        assertFalse(newBackend.preparedListenerWasCalled)

        newBackend.firePrepared()
        fixture.scheduler.runCurrent()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertTrue(newBackend.playing)
        assertEquals("https://media.example/new.mp4", newBackend.dataSource)
        assertEquals(0, fixture.endedCount)
    }

    @Test
    fun `backend construction failure ends cleanly without leaving a snapshot`() {
        val scheduler = FakeScheduler()
        var ended = 0
        val controller = UrlVideoPlaybackController(
            surfaceProvider = { null },
            backendFactory = UrlVideoBackendFactory { error("native player unavailable") },
            scheduler = scheduler,
            clockMillis = { scheduler.nowMillis },
            onEnded = { ended++ },
        )

        controller.play("https://media.example/video.mp4", 0.0)
        scheduler.runCurrent()

        assertEquals(1, ended)
        assertNull(controller.info())
    }


    @Test
    fun `a prepared and started player is only PLAYING after a frame was rendered`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.firePrepared()
        fixture.scheduler.runCurrent()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        // Started, but nothing has been drawn: still LOADING, and no first-frame callback yet.
        assertTrue(backend.playing)
        assertEquals(AirPlayPlaybackState.LOADING, fixture.states.last())
        assertEquals(0, fixture.firstFrameCount)

        backend.fireFirstFrame()
        fixture.scheduler.runCurrent()
        assertEquals(AirPlayPlaybackState.PLAYING, fixture.states.last())
        assertEquals(1, fixture.firstFrameCount)
    }

    @Test
    fun `media that never renders a frame fails with a stated reason after the deadline`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.firePrepared()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertTrue(backend.playing)

        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TIMEOUT_MS)
        assertEquals(1, fixture.endedCount)
        assertFalse(backend.playing)
        assertEquals(listOf("video did not start"), fixture.failures)
        assertTrue(fixture.states.contains(AirPlayPlaybackState.FAILED))
    }

    @Test
    fun `media without a video track is AUDIO_ONLY not a stalled video`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/audio.m3u8", 0.0, seconds = true)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.hasVideoTrack = false
        backend.firePrepared()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        backend.fireFirstFrame()   // never happens for audio-only media; must not change the state
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        assertEquals(AirPlayPlaybackState.AUDIO_ONLY, fixture.states.last())
        // The deadline only turns "a video that never draws" into a failure.
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TIMEOUT_MS)
        assertEquals(AirPlayPlaybackState.AUDIO_ONLY, fixture.states.last())
        assertEquals(0, fixture.endedCount)
    }

    @Test
    fun `the player starts muted and hands the soundtrack over only when the media has audio`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        assertEquals(listOf(true), backend.muteCalls)

        backend.firePrepared()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        // Prepared alone proves nothing: no takeover, still muted.
        assertTrue(backend.muted)
        assertTrue(fixture.audioOwnership.isEmpty())

        backend.hasVideoTrack = true
        backend.hasAudioTrack = true
        backend.fireFirstFrame()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertEquals(listOf(true, false), backend.muteCalls)
        assertEquals(listOf(true), fixture.audioOwnership)
    }

    @Test
    fun `media without an audio track keeps the AirPlay audio stream and stays muted`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.hasAudioTrack = false
        backend.firePrepared()
        backend.fireFirstFrame()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        assertEquals(listOf(false), fixture.audioOwnership)
        assertTrue(backend.muted)
    }

    @Test
    fun `an audio track the backend cannot report is decided as keep-AirPlay after the grace`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/video.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val backend = fixture.factory.players.single()
        backend.hasAudioTrack = null
        backend.firePrepared()
        backend.fireFirstFrame()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        assertTrue(fixture.audioOwnership.isEmpty())
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.AUDIO_DECISION_GRACE_MS)
        assertEquals(listOf(false), fixture.audioOwnership)
        assertTrue(backend.muted)
    }

    @Test
    fun `the session source is passed to the backend and its failure reason reaches the failure`() {
        val fixture = Fixture { FakeSurface() }
        val source = object : UrlVideoSessionSource {
            override val sessionId: String = "S1"
            override fun open(uri: String): ByteArray = throw java.io.IOException("no")
            override fun failureReason(): String? = "the reverse channel closed"
        }
        val scheduler = fixture.scheduler
        val controller = UrlVideoPlaybackController(
            surfaceProvider = { FakeSurface() },
            backendFactory = fixture.factory,
            scheduler = scheduler,
            clockMillis = { scheduler.nowMillis },
            onEnded = { fixture.endedCount++ },
            onStateChanged = { _, failure -> failure?.let { fixture.failures += it } },
            sessionSource = source,
        )
        controller.play("hearth-hls://S1/master.m3u8", 0.0)
        scheduler.runCurrent()
        assertSame(source, fixture.factory.sources.last())
        val backend = fixture.factory.players.last()
        backend.firePrepared()
        scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        backend.fireError(1, 0)
        scheduler.runCurrent()
        assertEquals(1, fixture.endedCount)
        assertEquals(listOf("media playback error"), fixture.failures)
    }

    @Test
    fun `a first frame from a replaced player cannot report the new generation as playing`() {
        val fixture = Fixture { FakeSurface() }
        fixture.controller.play("https://media.example/one.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val first = fixture.factory.players.single()
        first.firePrepared()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)

        fixture.controller.play("https://media.example/two.mp4", 0.0)
        fixture.scheduler.runCurrent()
        val second = fixture.factory.players.last()
        assertTrue(first.released)

        first.fireFirstFrame()   // late callback from the released player
        fixture.scheduler.runCurrent()
        assertEquals(0, fixture.firstFrameCount)
        assertFalse(fixture.states.last() == AirPlayPlaybackState.PLAYING)

        second.firePrepared()
        second.fireFirstFrame()
        fixture.scheduler.advanceBy(UrlVideoPlaybackController.DEFAULT_TICK_MS)
        assertEquals(1, fixture.firstFrameCount)
        assertEquals(AirPlayPlaybackState.PLAYING, fixture.states.last())
    }

    private class Fixture(surfaceProvider: () -> UrlVideoSurface?) {
        val scheduler = FakeScheduler()
        val factory = FakeBackendFactory()
        var endedCount = 0
        var firstFrameCount = 0
        val audioOwnership = mutableListOf<Boolean>()
        val states = mutableListOf<AirPlayPlaybackState>()
        val failures = mutableListOf<String>()
        val controller = UrlVideoPlaybackController(
            surfaceProvider = surfaceProvider,
            backendFactory = factory,
            scheduler = scheduler,
            clockMillis = { scheduler.nowMillis },
            onEnded = { endedCount++ },
            onFirstFrame = { firstFrameCount++ },
            onAudioOwnership = { audioOwnership += it },
            onStateChanged = { state, failure ->
                states += state
                failure?.let { failures += it }
            },
        )
    }

    private class FakeSurface(override val isValid: Boolean = true) : UrlVideoSurface {
        override val identity: Any = Any()
    }

    private class FakeScheduler : UrlVideoScheduler {
        private data class Scheduled(val atMillis: Long, val order: Long, val task: Runnable)

        private val tasks = mutableListOf<Scheduled>()
        private var nextOrder = 0L
        var nowMillis = 0L
            private set

        override fun dispatch(action: () -> Unit) {
            enqueue(Runnable(action), 0)
        }

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            enqueue(task, delayMillis)
        }

        override fun removeCallbacks(task: Runnable) {
            tasks.removeAll { it.task === task }
        }

        fun runCurrent() {
            var executions = 0
            while (true) {
                val next = tasks
                    .filter { it.atMillis <= nowMillis }
                    .minWithOrNull(compareBy<Scheduled> { it.atMillis }.thenBy { it.order })
                    ?: return
                tasks.remove(next)
                next.task.run()
                check(++executions < 10_000) { "scheduler did not quiesce" }
            }
        }

        fun advanceBy(millis: Long) {
            nowMillis += millis
            runCurrent()
        }

        private fun enqueue(task: Runnable, delayMillis: Long) {
            tasks += Scheduled(nowMillis + delayMillis.coerceAtLeast(0), nextOrder++, task)
        }
    }

    private class FakeBackendFactory : UrlVideoBackendFactory {
        val players = mutableListOf<FakeBackend>()
        val sources = mutableListOf<UrlVideoSessionSource?>()
        override fun create(source: UrlVideoSessionSource?): UrlVideoBackend {
            val player = FakeBackend()
            players += player
            sources += source
            return player
        }
    }

    private class FakeBackend : UrlVideoBackend {
        private var preparedListener: (() -> Unit)? = null
        private var completionListener: (() -> Unit)? = null
        private var errorListener: ((Int, Int) -> Boolean)? = null
        var savedPreparedListener: (() -> Unit)? = null
            private set
        var savedCompletionListener: (() -> Unit)? = null
            private set
        var savedErrorListener: ((Int, Int) -> Boolean)? = null
            private set
        var preparedListenerWasCalled = false
            private set
        var dataSource: String? = null
            private set
        val surfaces = mutableListOf<UrlVideoSurface?>()
        val seeks = mutableListOf<Int>()
        var startCount = 0
            private set
        var playing = false
            private set
        var released = false
            private set
        private var firstFrameListener: (() -> Unit)? = null
        var savedFirstFrameListener: (() -> Unit)? = null
            private set
        /** What the media reports; [hasAudioTrack] null stands for "the backend cannot tell yet". */
        override var hasVideoTrack = true
        override var hasAudioTrack: Boolean? = null
        val muteCalls = mutableListOf<Boolean>()
        var muted = true
            private set
        override val isPlaying: Boolean get() = playing
        override val durationMs: Int = 90_000
        override val positionMs: Int = 12_000

        override fun setOnPreparedListener(listener: (() -> Unit)?) {
            preparedListener = listener
            if (listener != null) savedPreparedListener = listener
        }

        override fun setOnCompletionListener(listener: (() -> Unit)?) {
            completionListener = listener
            if (listener != null) savedCompletionListener = listener
        }

        override fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?) {
            errorListener = listener
            if (listener != null) savedErrorListener = listener
        }

        override fun setOnFirstFrameListener(listener: (() -> Unit)?) {
            firstFrameListener = listener
            if (listener != null) savedFirstFrameListener = listener
        }

        override fun setMuted(muted: Boolean) {
            muteCalls += muted
            this.muted = muted
        }

        override fun setDataSource(url: String) {
            dataSource = url
        }

        override fun prepareAsync() = Unit

        override fun setSurface(surface: UrlVideoSurface?) {
            surfaces += surface
        }

        override fun seekTo(positionMs: Int) {
            seeks += positionMs
        }

        override fun start() {
            startCount++
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun release() {
            released = true
            playing = false
        }

        fun fireFirstFrame() {
            (firstFrameListener ?: savedFirstFrameListener)?.invoke()
        }

        fun firePrepared() {
            preparedListenerWasCalled = true
            (preparedListener ?: savedPreparedListener)?.invoke()
        }

        fun fireError(what: Int, extra: Int): Boolean =
            (errorListener ?: savedErrorListener)?.invoke(what, extra) ?: false

        fun fireCompletion() {
            (completionListener ?: savedCompletionListener)?.invoke()
        }
    }
}
