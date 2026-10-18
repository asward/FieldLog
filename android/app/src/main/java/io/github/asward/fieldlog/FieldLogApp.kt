package io.github.asward.fieldlog

import android.app.Application
import android.content.Context
import io.github.asward.fieldlog.data.Vaults
import io.github.asward.fieldlog.log.LogEngine
import io.github.asward.fieldlog.log.LogNotifications
import io.github.asward.fieldlog.sync.SyncWorker

class FieldLogApp : Application() {
    val vaults by lazy { Vaults(this) }
    val logEngine by lazy { LogEngine(this) }

    override fun onCreate() {
        super.onCreate()
        LogNotifications.createChannels(this)
        SyncWorker.schedulePeriodic(this)
    }
}

val Context.vaults: Vaults get() = (applicationContext as FieldLogApp).vaults

/** The open log, shared by the log screen, notifications and entry points. */
val Context.logEngine: LogEngine get() = (applicationContext as FieldLogApp).logEngine
