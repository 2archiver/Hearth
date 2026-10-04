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

    private class Fixture(surfaceProvider: () -> UrlVideoSurface?) {
        val scheduler = FakeScheduler()
        val factory = FakeBackendFactory()
        var endedCount = 0
        val controller = UrlVideoPlaybackController(
            surfaceProvider = surfaceProvider,
            backendFactory = factory,
            scheduler = scheduler,
            clockMillis = { scheduler.nowMillis },
            onEnded = { endedCount++ },
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
        override fun create(): UrlVideoBackend {
            val player = FakeBackend()
            players += player
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
