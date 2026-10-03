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

    /** Wall-clock time (ms) of the last completed update check, 0 = never. */
    var lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

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

    /** versionName of the newest build the app has ever seen published (for the UI). */
    var latestSeenVersionName: String?
        get() = prefs.getString(KEY_LATEST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LATEST_NAME, value).apply()

    /** versionCode of the newest build the app has ever seen published. */
    var latestSeenVersionCode: Int
        get() = prefs.getInt(KEY_LATEST_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_LATEST_CODE, value).apply()

    /** Remembers a staged download, so "Install" can be offered again after a restart. */
    fun stage(info: UpdateInfo, path: String) {
        prefs.edit()
            .putInt(KEY_STAGED_CODE, info.versionCode)
            .putString(KEY_STAGED_PATH, path)
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
            .apply()
    }

    /** True when [versionCode] is newer than the installed build and was not dismissed. */
    fun isInteresting(versionCode: Int, installedVersionCode: Int): Boolean =
        versionCode > installedVersionCode && versionCode != skippedVersionCode

    companion object {
        private const val PREFS_NAME = "phairplay_updates"
        private const val KEY_LAST_CHECK = "last_check_millis"
        private const val KEY_SKIPPED_CODE = "skipped_version_code"
        private const val KEY_STAGED_CODE = "staged_version_code"
        private const val KEY_STAGED_PATH = "staged_apk_path"
        private const val KEY_LATEST_NAME = "latest_seen_version_name"
        private const val KEY_LATEST_CODE = "latest_seen_version_code"

        /** How long a successful check stays fresh. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** How long to wait before retrying after a failed check. */
        const val RETRY_INTERVAL_MS = 30 * 60 * 1000L
    }
}
