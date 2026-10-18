package io.github.asward.fieldlog.log

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Microphone foreground service (spec §9). It holds no logic: [LogEngine]
 * owns the log and speech. Running in the foreground keeps listening alive
 * after the screen locks. Started only while the log screen is visible.
 */
class LogService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Listening"
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching {
            ServiceCompat.startForeground(this, LogNotifications.ONGOING_ID, LogNotifications.ongoing(this, text), type)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private var running = false

        fun start(context: Context, text: String = "Listening") {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, LogService::class.java).putExtra(EXTRA_TEXT, text),
                )
                running = true
            }
        }

        /** Update the ongoing notification's line (e.g. the mode). */
        fun update(context: Context, text: String) {
            if (running) LogNotifications.updateOngoing(context, text)
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, LogService::class.java))
        }
    }
}
