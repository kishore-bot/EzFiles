package com.ezfiles.android

import android.content.Context
import com.ezfiles.reader.platform.AndroidCrashRecorder
import com.ezfiles.reader.platform.CloudCrashReporting
import com.ezfiles.reader.platform.NoCloudCrashReporting

fun createCrashReporting(context: Context): CrashReportingSetup = object : CrashReportingSetup,
    CloudCrashReporting by NoCloudCrashReporting {
    override fun start(recorder: AndroidCrashRecorder) {}
}
