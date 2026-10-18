package io.github.asward.fieldlog.log

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.asward.fieldlog.logEngine

/** "End log" from a notification. */
class LogActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_END_LOG) return
        val pending = goAsync()
        context.logEngine.endFromNotification { pending.finish() }
    }

    companion object {
        const val ACTION_END_LOG = "io.github.asward.fieldlog.END_LOG"
    }
}
