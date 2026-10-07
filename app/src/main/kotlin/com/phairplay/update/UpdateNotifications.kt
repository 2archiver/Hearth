package com.phairplay.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phairplay.MainActivity
import com.phairplay.R
import com.phairplay.service.PhairPlayService
import com.phairplay.util.Logger

/** Shared low-interruption notifications for checks run with or without the foreground service. */
object UpdateNotifications {
    fun available(context: Context, info: UpdateInfo) = post(
        context,
        context.getString(R.string.update_notification_title),
        context.getString(R.string.update_notification_available, info.shortLabel()),
    )

    fun ready(context: Context, info: UpdateInfo) = post(
        context,
        context.getString(R.string.update_notification_title),
        context.getString(R.string.update_notification_ready, info.shortLabel()),
    )

    fun keyMigrationRequired(context: Context, info: UpdateInfo) = post(
        context,
        context.getString(R.string.update_notification_migration_title),
        context.getString(R.string.update_notification_migration, info.shortLabel()),
    )

    private fun post(context: Context, title: String, message: String) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PhairPlayService.CHANNEL_ID,
                    appContext.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = appContext.getString(R.string.notification_channel_description)
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            appContext,
            UPDATE_PENDING_INTENT_ID,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, PhairPlayService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        try {
            manager.notify(PhairPlayService.UPDATE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Logger.w("Update notification not shown (notification permission unavailable)")
        }
    }

    private const val UPDATE_PENDING_INTENT_ID = 20
}
