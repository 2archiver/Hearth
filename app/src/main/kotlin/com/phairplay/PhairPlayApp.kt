package com.phairplay

import android.app.Application
import timber.log.Timber

/**
 * PhairPlayApp — The Application class for Hearth.
 *
 * WHY: Android requires an Application subclass to run initialization code before
 * any Activity or Service starts. We use this to set up logging (Timber) once
 * at startup.
 *
 * HOW: This class is referenced in AndroidManifest.xml via android:name=".PhairPlayApp".
 * It is instantiated automatically by the Android framework when the app process starts.
 *
 * Example (automatic — no user code needed):
 *   The app starts → Android creates PhairPlayApp → onCreate() runs → Timber is ready.
 */
class PhairPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging()
        installUrlVideoBackend()
    }

    /**
     * Installs the Android URL-video backend (Media3/ExoPlayer) for this process.
     *
     * NOTE: [com.phairplay.airplay.ExoUrlVideoBackendFactory] is marked @UnstableApi by Media3, so
     * this one call site opts in explicitly (per-class opt-in, no project-wide suppression).
     *
     * WHY HERE: [com.phairplay.airplay.AirPlayVideoPlayer] must stay compilable by the offline JVM
     * test runner, which has no Media3 on its classpath — so it asks
     * [com.phairplay.airplay.UrlVideoBackends] for a factory instead of naming ExoPlayer itself.
     * Installing it once, at process start, is what makes URL video (direct URLs *and* the
     * sender-mediated HLS bridge) actually playable; without it a `/play` fails with
     * "no URL-video backend is installed in this process" and the AirPlay audio path is untouched.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun installUrlVideoBackend() {
        com.phairplay.airplay.UrlVideoBackends.factory = {
            com.phairplay.airplay.ExoUrlVideoBackendFactory(applicationContext)
        }
    }

    /**
     * Sets up Timber logging.
     *
     * In debug builds: logs to Android logcat with full file/line info.
     * In release builds: only logs warnings and errors (no verbose/debug output).
     *
     * WHY: Timber is a tiny wrapper around android.util.Log that adds:
     * - Automatic class name as a log tag (no more TAG constants everywhere)
     * - Easy filtering by log level in release builds
     * - The ability to swap the log backend (useful for crash reporting in future)
     */
    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            // Debug tree: logs everything, shows file names and line numbers
            Timber.plant(Timber.DebugTree())
        }
        // In release builds, we deliberately plant no tree to avoid
        // exposing debug information. Critical errors use android.util.Log directly.
    }
}
