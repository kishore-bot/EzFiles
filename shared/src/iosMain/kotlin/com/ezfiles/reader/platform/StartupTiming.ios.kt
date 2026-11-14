package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

@Composable
actual fun ReportStartupReady(ready: Boolean) = Unit

actual fun <T> traceAppWork(section: String, block: () -> T): T = block()
