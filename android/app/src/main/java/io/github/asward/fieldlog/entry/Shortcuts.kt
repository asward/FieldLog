package io.github.asward.fieldlog.entry

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.asward.fieldlog.R
import io.github.asward.fieldlog.session.SessionActivity
import io.github.asward.fieldlog.vaults

/** Launcher shortcuts: one per recent project, each opening a session there. */
object Shortcuts {
    private const val MAX = 4

    fun publish(context: Context, vaultId: String) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val vaults = app.vaults
                val vault = vaults.open(vaultId)
                val titles = vault.projects().associate { it.slug to it.title }
                val recent = (vaults.recentProjects(vaultId) + titles.keys)
                    .distinct().filter { it in titles }.take(MAX)
                val shortcuts = recent.mapIndexed { rank, slug ->
                    val intent = SessionActivity.intent(app, vaultId, slug).setAction(Intent.ACTION_VIEW)
                    ShortcutInfoCompat.Builder(app, "project:$vaultId:$slug")
                        .setShortLabel(titles.getValue(slug).take(25))
                        .setLongLabel("Log: ${titles.getValue(slug)}".take(45))
                        .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_mic))
                        .setIntent(intent)
                        .setRank(rank)
                        .build()
                }
                ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)
            }
        }.start()
    }
}
