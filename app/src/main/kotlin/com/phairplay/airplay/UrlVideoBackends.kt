package com.phairplay.airplay

/**
 * Where the Android URL-video backend comes from.
 *
 * WHY INDIRECTION: the backend is built on Media3 (ExoPlayer) with a custom `DataSource`, and that
 * code must stay in its own file — the offline JVM test runner compiles this module without Media3
 * on its classpath, so anything importing `androidx.media3.*` is excluded from that compilation.
 * [AirPlayVideoPlayer] therefore asks this holder, and the Android entry point
 * ([com.phairplay.PhairPlayApp]) installs the real factory at process start:
 *
 * ```
 *   UrlVideoBackends.factory = { ExoUrlVideoBackendFactory(applicationContext) }
 * ```
 *
 * When nothing is installed — a JVM unit test, or a build that somehow dropped the install — the
 * player reports a plain "video backend unavailable" failure instead of silently doing nothing.
 */
internal object UrlVideoBackends {

    /** Installed once at process start. Null until then. */
    @Volatile
    var factory: (() -> UrlVideoBackendFactory)? = null

    /** True when a player can be created in this process. */
    val isAvailable: Boolean get() = factory != null

    /** The installed factory, or a factory that fails loudly when it is asked for a player. */
    fun resolve(): UrlVideoBackendFactory = factory?.invoke() ?: MISSING

    private val MISSING = UrlVideoBackendFactory {
        throw IllegalStateException("no URL-video backend is installed in this process")
    }
}
