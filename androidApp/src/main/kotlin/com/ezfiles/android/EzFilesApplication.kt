package com.ezfiles.android

import android.app.Application
import com.ezfiles.app.androidAppDependencies
import com.ezfiles.app.androidAssistantAccess
import com.ezfiles.reader.platform.installCrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EzFilesApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val assistantAccess by lazy { androidAssistantAccess(this) }
    val dependencies by lazy {
        androidAppDependencies(this, applicationScope, assistantAccess)
    }
    val cloudCrashReporting by lazy { createCrashReporting(this) }
    override fun onCreate() {
        super.onCreate()
        val recorder = installCrashReporter(this)
        cloudCrashReporting.start(recorder)
    }
}
