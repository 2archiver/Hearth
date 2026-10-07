package com.phairplay.update

import android.content.Context
import com.phairplay.util.Logger
import kotlinx.coroutines.sync.Mutex

/**
 * One policy for manual and scheduled update checks, downloads, verification and installation.
 *
 * A process-wide single-flight mutex prevents a Settings tap, a periodic worker and a retry worker
 * from issuing duplicate GitHub requests or staging the same APK concurrently. WorkManager is
 * best-effort and may be delayed by Doze/sleep; manual checks bypass cadence but still honor an
 * in-flight operation and GitHub's explicit rate-limit deadline.
 */
object UpdateFlow {
    private val operationMutex = Mutex()

    suspend fun run(
        context: Context,
        autoDownload: Boolean,
        autoInstall: Boolean,
        forceCheck: Boolean = false,
        onNotifyAvailable: (UpdateInfo) -> Unit = {},
        onNotifyReady: (UpdateInfo) -> Unit = {},
        onNotifyKeyMismatch: ((UpdateInfo) -> Unit)? = null
    ): UpdateCheck {
        if (!operationMutex.tryLock()) {
            return UpdateCheck.Skipped("An update check or download is already in progress.")
        }
        try {
            val manager = UpdateManager.get(context)
            val result = manager.check(force = forceCheck)
            if (result !is UpdateCheck.Available) return result
            val info = result.info

            if (result.skipped) {
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
                    // UpdateManager is the final guard for both scheduled and user-triggered installs.
                    // It declines while AirPlay media is active; the verified APK remains staged.
                    val installStart = if (autoInstall) manager.installStaged() else null
                    if (installStart != InstallStart.QUEUED) onNotifyReady(info)
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
        } finally {
            operationMutex.unlock()
        }
    }
}
