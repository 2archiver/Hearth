package com.phairplay.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Durable automatic check; timing eligibility lives in UpdateManager, not the worker's wake time. */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settingsFlow.first()
        if (!settings.autoCheckForUpdates) {
            UpdateWorkScheduler.cancelRetry(applicationContext)
            return Result.success()
        }

        val manager = UpdateManager.get(applicationContext)
        return try {
            val outcome = UpdateFlow.run(
                context = applicationContext,
                autoDownload = settings.autoDownloadUpdates,
                autoInstall = settings.autoInstallUpdates,
                forceCheck = false,
                onNotifyAvailable = { UpdateNotifications.available(applicationContext, it) },
                onNotifyReady = { UpdateNotifications.ready(applicationContext, it) },
                onNotifyKeyMismatch = { UpdateNotifications.keyMigrationRequired(applicationContext, it) },
            )
            when {
                outcome is UpdateCheck.Failed && outcome.reason == UpdateFailureReason.CHECK_FAILED ->
                    UpdateWorkScheduler.scheduleRetry(applicationContext, manager.nextEligibleCheckAtMillis())

                outcome is UpdateCheck.Skipped ->
                    UpdateWorkScheduler.scheduleRetry(applicationContext, manager.nextEligibleCheckAtMillis())

                else -> UpdateWorkScheduler.cancelRetry(applicationContext)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w("Automatic update worker failed (${error.javaClass.simpleName}); retry remains scheduled")
            val next = manager.nextEligibleCheckAtMillis()
                .coerceAtLeast(System.currentTimeMillis() + UpdatePreferences.RETRY_INTERVAL_MS)
            UpdateWorkScheduler.scheduleRetry(applicationContext, next)
            Result.success()
        }
    }
}
