package io.github.asward.fieldlog.entry

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.asward.fieldlog.R
import io.github.asward.fieldlog.session.SessionActivity
import io.github.asward.fieldlog.vaults

/** Home-screen widget: sticky project name, "Log" and "Snap" buttons. */
class SessionWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context.applicationContext, manager, ids)
    }

    companion object {
        /** Re-render all widgets, e.g. after the sticky project changes. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, SessionWidget::class.java))
            if (ids.isNotEmpty()) render(app, manager, ids)
        }

        private fun render(app: Context, manager: AppWidgetManager, ids: IntArray) {
            Thread {
                val vaults = app.vaults
                val vaultId = vaults.currentId
                val slug = vaultId?.let { vaults.stickyProject(it) }
                val title = runCatching {
                    vaults.open(vaultId!!).projects().firstOrNull { it.slug == slug }?.title
                }.getOrNull() ?: slug ?: "Set up FieldLog"

                val log = PendingIntent.getActivity(
                    app, 1, SessionActivity.intent(app),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val snap = PendingIntent.getActivity(
                    app, 2, SessionActivity.intent(app, snap = true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val views = RemoteViews(app.packageName, R.layout.widget_session).apply {
                    setTextViewText(R.id.widget_project, title)
                    setOnClickPendingIntent(R.id.widget_root, log)
                    setOnClickPendingIntent(R.id.widget_log, log)
                    setOnClickPendingIntent(R.id.widget_snap, snap)
                }
                manager.updateAppWidget(ids, views)
            }.start()
        }
    }
}
