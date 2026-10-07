package com.phairplay.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Keeps one best-effort hourly check and one replaceable retry in WorkManager. */
object UpdateWorkScheduler {
    const val PERIODIC_WORK_NAME = "hearth-hourly-update-check-v1"
    const val RETRY_WORK_NAME = "hearth-update-check-retry-v1"

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(RETRY_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UpdatePreferences.CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
            // The first automatic check is also delayed by one cadence; manual Check now is immediate.
            .setInitialDelay(UpdatePreferences.CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleRetry(context: Context, eligibleAtMillis: Long) {
        val delayMillis = (eligibleAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelRetry(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RETRY_WORK_NAME)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val TAG = "hearth-update-check"
}
