package io.github.asward.fieldlog.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.asward.fieldlog.log.LogActivity
import io.github.asward.fieldlog.vaults

/** Quick Settings tile: one tap starts a log (or resumes the open one), including from the lock screen. */
class LogTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val vaults = applicationContext.vaults
        val project = vaults.currentId?.let { vaults.activeProject(it) }
        tile.state = if (project != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = "FieldLog"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = project ?: "No project"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = LogActivity.newLogIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
