package io.github.asward.fieldlog.log

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.asward.fieldlog.R

/** Notifications for an open log: the ongoing one, "Unlock to snap" and idle stop. */
object LogNotifications {
    const val ONGOING_ID = 41
    private const val ALERT_ID = 42
    private const val CH_ONGOING = "logging"
    private const val CH_ALERTS = "log_alerts"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Logging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a log is listening"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Log alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Unlock to snap, and the mic stopping after a quiet spell"
            },
        )
    }

    private fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openLog(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 20, LogActivity.resumeIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun endLog(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 21,
        Intent(context, LogActionReceiver::class.java).setAction(LogActionReceiver.ACTION_END_LOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Logging")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openLog(context))
            .addAction(0, "End log", endLog(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("MissingPermission")
    fun updateOngoing(context: Context, text: String) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(ONGOING_ID, ongoing(context, text))
    }

    /** Voice "snap" while the log screen isn't visible (spec §9). */
    @SuppressLint("MissingPermission")
    fun unlockToSnap(context: Context) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_widget_camera)
            .setContentTitle("Unlock to snap")
            .setContentText("Open the log screen to take the photo.")
            .setContentIntent(openLog(context))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setSilent(true)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_ID, n)
    }

    /** The mic stopped after the idle timeout. */
    @SuppressLint("MissingPermission")
    fun idle(context: Context, project: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Log open: resume or end")
            .setContentText("$project · the mic stopped after a quiet spell")
            .setContentIntent(openLog(context))
            .addAction(0, "Resume", openLog(context))
            .addAction(0, "End log", endLog(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_ID, n)
    }

    fun cancelAlerts(context: Context) {
        NotificationManagerCompat.from(context).cancel(ALERT_ID)
    }
}
