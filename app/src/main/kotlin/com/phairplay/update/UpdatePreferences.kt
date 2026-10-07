package com.phairplay.update

import android.content.Context
import android.content.SharedPreferences
import com.phairplay.util.Logger

/**
 * UpdatePreferences — small, synchronous store for updater state.
 *
 * WHY separate from [com.phairplay.settings.SettingsRepository]: that uses DataStore
 * (coroutines, async), while the updater is also driven from a background service and a
 * BroadcastReceiver where a plain synchronous read is simpler and cannot race. These are
 * bookkeeping values (timestamps, cached paths), not user-facing settings — the user-facing
 * toggles live in AppSettings so they are managed in one place in the UI.
 *
 * Not a secret store: it holds no credentials.
 */
internal class UpdatePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Wall-clock time (ms) of the last check attempt, 0 = never. */
    var lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** Last request start, successful completion, failure, and persisted retry/rate-limit deadlines. */
    val lastAttemptMillis: Long get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
    val lastSuccessfulCheckMillis: Long get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
    val lastFailureMillis: Long get() = prefs.getLong(KEY_LAST_FAILURE, 0L)
    val retryAfterMillis: Long get() = prefs.getLong(KEY_RETRY_AFTER, 0L)
    val rateLimitUntilMillis: Long get() = prefs.getLong(KEY_RATE_LIMIT_UNTIL, 0L)
    val lastFailureMessage: String? get() = prefs.getString(KEY_LAST_FAILURE_MESSAGE, null)

    /** True once the new schedule schema has been written; false permits a legacy timestamp fallback. */
    private val hasScheduleState: Boolean get() = prefs.getBoolean(KEY_SCHEDULE_STATE_INITIALIZED, false)

    /** Reads timing state while preserving the old last-check throttle after an upgrade. */
    fun scheduleStatus(): UpdateScheduleStatus {
        val legacyLastCheck = lastCheckMillis
        return if (hasScheduleState) {
            UpdateScheduleStatus(
                lastCheckMillis = legacyLastCheck,
                lastAttemptMillis = lastAttemptMillis,
                lastSuccessMillis = lastSuccessfulCheckMillis,
                lastFailureMillis = lastFailureMillis,
                retryAfterMillis = retryAfterMillis,
                rateLimitUntilMillis = rateLimitUntilMillis,
                lastFailureMessage = lastFailureMessage,
            )
        } else {
            // Previous builds encoded retry timing by backdating this value. We cannot infer
            // whether it represents success or failure, so preserve its conservative cadence once.
            UpdateScheduleStatus(
                lastCheckMillis = legacyLastCheck,
                lastAttemptMillis = legacyLastCheck,
                legacyTimestampOnly = legacyLastCheck > 0L,
            )
        }
    }

    /** Persist an attempt before network I/O, so process death still leaves a bounded retry. */
    fun recordCheckAttempt(nowMillis: Long) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_STATE_INITIALIZED, true)
            .putLong(KEY_LAST_CHECK, nowMillis)
            .putLong(KEY_LAST_ATTEMPT, nowMillis)
            .putLong(KEY_RETRY_AFTER, safeAdd(nowMillis, RETRY_INTERVAL_MS))
            .apply()
    }

    /** Persist a completed successful check and clear transient retry/rate-limit state. */
    fun recordCheckSuccess(nowMillis: Long) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_STATE_INITIALIZED, true)
            .putLong(KEY_LAST_CHECK, nowMillis)
            .putLong(KEY_LAST_ATTEMPT, nowMillis)
            .putLong(KEY_LAST_SUCCESS, nowMillis)
            .putLong(KEY_LAST_FAILURE, 0L)
            .putLong(KEY_RETRY_AFTER, 0L)
            .putLong(KEY_RATE_LIMIT_UNTIL, 0L)
            .remove(KEY_LAST_FAILURE_MESSAGE)
            .apply()
    }

    /** Persist a failure with a server-supplied rate-limit deadline when one is available. */
    fun recordCheckFailure(
        nowMillis: Long,
        retryAtMillis: Long,
        rateLimitUntilMillis: Long = 0L,
        safeMessage: String,
    ) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_STATE_INITIALIZED, true)
            .putLong(KEY_LAST_CHECK, nowMillis)
            .putLong(KEY_LAST_ATTEMPT, nowMillis)
            .putLong(KEY_LAST_FAILURE, nowMillis)
            .putLong(KEY_RETRY_AFTER, retryAtMillis.coerceAtLeast(nowMillis))
            .putLong(
                KEY_RATE_LIMIT_UNTIL,
                if (rateLimitUntilMillis > 0L) rateLimitUntilMillis.coerceAtLeast(nowMillis) else 0L,
            )
            .putString(KEY_LAST_FAILURE_MESSAGE, safeMessage.take(MAX_FAILURE_MESSAGE_CHARS))
            .apply()
    }

    /** versionCode the user dismissed with "not now"; 0 = nothing skipped. */
    var skippedVersionCode: Int
        get() = prefs.getInt(KEY_SKIPPED_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_SKIPPED_CODE, value).apply()

    /**
     * versionCode of an APK that has been downloaded and is waiting to be installed,
     * 0 = nothing staged. Paired with [stagedApkPath].
     */
    var stagedVersionCode: Int
        get() = prefs.getInt(KEY_STAGED_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_STAGED_CODE, value).apply()

    /** Absolute path of the staged APK, or null. */
    var stagedApkPath: String?
        get() = prefs.getString(KEY_STAGED_PATH, null)
        set(value) = prefs.edit().putString(KEY_STAGED_PATH, value).apply()

    /** Exact APK versionName observed when a newly downloaded staged file was verified. */
    val stagedApkVersionName: String?
        get() = prefs.getString(KEY_STAGED_VERSION_NAME, null)

    /** versionName of the newest build the app has ever seen published (for the UI). */
    var latestSeenVersionName: String?
        get() = prefs.getString(KEY_LATEST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LATEST_NAME, value).apply()

    /** versionCode of the newest build the app has ever seen published. */
    var latestSeenVersionCode: Int
        get() = prefs.getInt(KEY_LATEST_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_LATEST_CODE, value).apply()

    /**
     * Published versionCode whose APK turned out to be *not newer* than this install once it was
     * downloaded and its manifest read (the release notes over-stated the build). Remembered so
     * the same release is not downloaded and offered again every check — the 1.8.1 "update
     * prompt that never goes away". 0 = none.
     */
    var rejectedPublishedVersionCode: Int
        get() = prefs.getInt(KEY_REJECTED_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_REJECTED_CODE, value).apply()

    /** Last known installer state ([InstallState.name]); drives the visible Settings status. */
    var installState: String?
        get() = prefs.getString(KEY_INSTALL_STATE, null)
        set(value) = prefs.edit().putString(KEY_INSTALL_STATE, value).apply()

    /** Human-readable detail for [installState] (the platform's failure message, if any). */
    var installMessage: String?
        get() = prefs.getString(KEY_INSTALL_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_INSTALL_MESSAGE, value).apply()

    /** Records an installer state change in one write. */
    fun recordInstallState(state: InstallState, message: String? = null) {
        prefs.edit()
            .putString(KEY_INSTALL_STATE, state.name)
            .putString(KEY_INSTALL_MESSAGE, message)
            .putLong(KEY_INSTALL_STATE_AT, System.currentTimeMillis())
            .apply()
    }

    /** When [installState] was last written (ms), 0 = never. */
    val installStateAt: Long get() = prefs.getLong(KEY_INSTALL_STATE_AT, 0L)

    /**
     * Remembers a staged download, so "Install" can be offered again after a restart.
     *
     * [versionCode] is the code read from the APK's own manifest when available — the release
     * notes are only a hint, and a staged entry keyed on a wrong hint could never be recognised
     * as "already installed" afterwards.
     */
    fun stage(
        info: UpdateInfo,
        path: String,
        versionCode: Int = info.versionCode,
        apkVersionName: String,
    ) {
        prefs.edit()
            .putInt(KEY_STAGED_CODE, versionCode)
            .putString(KEY_STAGED_PATH, path)
            .putString(KEY_STAGED_VERSION_NAME, apkVersionName)
            .putString(KEY_LATEST_NAME, info.versionName)
            .putInt(KEY_LATEST_CODE, info.versionCode)
            .apply()
    }

    /** Forgets a staged download and deletes the file. */
    fun clearStaged() {
        stagedApkPath?.let { path ->
            try {
                val file = java.io.File(path)
                if (file.exists() && !file.delete()) {
                    Logger.w("Could not delete staged update $path")
                }
            } catch (e: Exception) {
                Logger.w("Could not delete staged update: ${e.message}")
            }
        }
        prefs.edit()
            .remove(KEY_STAGED_CODE)
            .remove(KEY_STAGED_PATH)
            .remove(KEY_STAGED_VERSION_NAME)
            .apply()
    }

    companion object {
        private fun safeAdd(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

        private const val PREFS_NAME = "phairplay_updates"
        private const val KEY_LAST_CHECK = "last_check_millis"
        private const val KEY_LAST_ATTEMPT = "last_attempt_millis"
        private const val KEY_LAST_SUCCESS = "last_successful_check_millis"
        private const val KEY_LAST_FAILURE = "last_failure_millis"
        private const val KEY_RETRY_AFTER = "retry_after_millis"
        private const val KEY_RATE_LIMIT_UNTIL = "rate_limit_until_millis"
        private const val KEY_LAST_FAILURE_MESSAGE = "last_failure_message"
        private const val KEY_SCHEDULE_STATE_INITIALIZED = "schedule_state_initialized"
        private const val MAX_FAILURE_MESSAGE_CHARS = 240
        private const val KEY_SKIPPED_CODE = "skipped_version_code"
        private const val KEY_STAGED_CODE = "staged_version_code"
        private const val KEY_STAGED_PATH = "staged_apk_path"
        private const val KEY_STAGED_VERSION_NAME = "staged_apk_version_name"
        private const val KEY_LATEST_NAME = "latest_seen_version_name"
        private const val KEY_LATEST_CODE = "latest_seen_version_code"
        private const val KEY_REJECTED_CODE = "rejected_published_version_code"
        private const val KEY_INSTALL_STATE = "install_state"
        private const val KEY_INSTALL_MESSAGE = "install_message"
        private const val KEY_INSTALL_STATE_AT = "install_state_at"

        /** Hourly best-effort cadence; Android may defer background work while the TV sleeps/Dozes. */
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L

        /** How long to wait before retrying after a failed check unless GitHub asks for longer. */
        const val RETRY_INTERVAL_MS = 30 * 60 * 1000L
    }
}
