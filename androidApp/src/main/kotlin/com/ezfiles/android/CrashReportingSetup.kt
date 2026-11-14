package com.ezfiles.android

import com.ezfiles.reader.platform.AndroidCrashRecorder
import com.ezfiles.reader.platform.CloudCrashReporting

interface CrashReportingSetup : CloudCrashReporting {
    fun start(recorder: AndroidCrashRecorder)
}
