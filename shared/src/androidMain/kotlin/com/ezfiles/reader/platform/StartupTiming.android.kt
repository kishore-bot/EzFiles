package com.ezfiles.reader.platform

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable

@Composable
actual fun ReportStartupReady(ready: Boolean) { ReportDrawnWhen { ready } }

actual fun <T> traceAppWork(section: String, block: () -> T): T {
    // Host JVM tests have no Android platform (SDK_INT=0); tracing must not prevent real work.
    if (android.os.Build.VERSION.SDK_INT < 18) return block()
    android.os.Trace.beginSection(section)
    return try { block() } finally { android.os.Trace.endSection() }
}
