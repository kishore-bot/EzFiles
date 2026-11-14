package com.ezfiles.android

import android.app.Application
import com.ezfiles.app.androidAppDependencies
import com.ezfiles.app.androidAssistantAccess
import com.ezfiles.reader.platform.installCrashReporter

class EzFilesApplication : Application() {
    val assistantAccess by lazy { androidAssistantAccess(this) }
    val dependencies by lazy { androidAppDependencies(this, assistantAccess) }
    val cloudCrashReporting by lazy { createCrashReporting(this) }
    override fun onCreate() {
        super.onCreate()
        val recorder = installCrashReporter(this)
        cloudCrashReporting.start(recorder)
    }
}
