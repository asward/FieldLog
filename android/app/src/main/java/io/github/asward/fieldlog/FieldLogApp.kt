package io.github.asward.fieldlog

import android.app.Application
import android.content.Context
import io.github.asward.fieldlog.data.Vaults
import io.github.asward.fieldlog.sync.SyncWorker

class FieldLogApp : Application() {
    val vaults by lazy { Vaults(this) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
    }
}

val Context.vaults: Vaults get() = (applicationContext as FieldLogApp).vaults
