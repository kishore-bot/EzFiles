package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** Report the first usable screen model to the platform startup instrumentation. */
@Composable
expect fun ReportStartupReady(ready: Boolean)

/** Fixed section names only; callers must not put filenames, text or URIs into traces. */
expect fun <T> traceAppWork(section: String, block: () -> T): T
