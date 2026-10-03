package com.phairplay.update

import android.content.Context
import com.phairplay.util.Logger

/**
 * UpdateFlow — the one place that decides what "check for updates" means.
 *
 * WHY: the Settings screen, the background service and the Home-screen badge all need the
 * same sequence and the same rules, and a rule expressed twice is a rule that drifts — one
 * caller would end up installing without verifying while the other verified without
 * installing. Keeping the policy here means every entry point behaves identically.
 *
 * The rules:
 *   1. Ask GitHub whether a newer build exists.
 *   2. Download it only if [autoDownload] is on (otherwise just announce it).
 *   3. Verify the download against the published SHA-256 *and* against this app's own
 *      signing certificate — an APK signed with a different key is refused, because that is
 *      exactly what "App not installed as package conflicts with an existing package" means.
 *   4. Install immediately if [autoInstall] is on; if Android declines, keep it staged and
 *      announce that it is ready. A signing-key mismatch gets its own migration notification.
 */
object UpdateFlow {

    /**
     * Runs the whole flow.
     *
     * @param context      any context; the application context is used internally
     * @param autoDownload download the APK without waiting for the user to say so
     * @param autoInstall  install a verified APK without waiting for the user to say so
     * @param forceCheck   ignore the background-check throttle (used by the manual button)
     * @param onNotifyAvailable called when an update exists but was not downloaded
     * @param onNotifyReady     called when an update is downloaded and waiting to install
     * @param onNotifyKeyMismatch called when Android requires a one-time signing-key migration
     * @return what the check found, so the caller can render it
     */
    suspend fun run(
        context: Context,
        autoDownload: Boolean,
        autoInstall: Boolean,
        forceCheck: Boolean = false,
        onNotifyAvailable: (UpdateInfo) -> Unit = {},
        onNotifyReady: (UpdateInfo) -> Unit = {},
        onNotifyKeyMismatch: ((UpdateInfo) -> Unit)? = null
    ): UpdateCheck {
        val manager = UpdateManager.get(context)
        val result = manager.check(force = forceCheck)
        if (result !is UpdateCheck.Available) return result
        val info = result.info

        if (result.skipped) {
            // The user said "skip this version". Keep it in the result so Settings can still show
            // and un-skip it, but do not notify, badge or download it — that is what skipping means.
            Logger.i("Update ${info.versionName} available but skipped by the user — not announcing")
            return result
        }

        if (!autoDownload) {
            Logger.i("Update ${info.versionName} available — announcing, auto-download is off")
            onNotifyAvailable(info)
            return result
        }

        return when (val staged = manager.downloadAndStage(info)) {
            is StageResult.Staged -> {
                Logger.i("Update ${info.versionName} downloaded and verified")
                val installQueued = autoInstall && manager.installStaged()
                if (!installQueued) {
                    // Keep the staged APK discoverable when silent installation is disabled
                    // or the platform rejects the install request (for example, missing
                    // "install unknown apps" permission).
                    onNotifyReady(info)
                }
                result
            }

            is StageResult.Failed -> {
                Logger.w("Update staging failed (${staged.reason}): ${staged.message}")
                if (staged.reason == UpdateFailureReason.SIGNATURE_MISMATCH) {
                    val notifyMigration = onNotifyKeyMismatch
                    if (notifyMigration != null) notifyMigration(info) else onNotifyAvailable(info)
                } else {
                    onNotifyAvailable(info)
                }
                UpdateCheck.Failed(staged.message, staged.reason, info)
            }
        }
    }
}
