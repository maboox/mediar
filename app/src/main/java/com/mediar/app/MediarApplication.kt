package com.mediar.app

import android.app.Application
import com.mediar.app.data.Store

class MediarApplication : Application(), androidx.work.Configuration.Provider {
    val store: Store by lazy { Store(this) }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
    override fun onCreate(){super.onCreate();com.mediar.app.reminder.RepairWorker.ensure(this)}
}
fun android.content.Context.store(): Store = (applicationContext as MediarApplication).store
