package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** iOS is parked: items can still be copied, but not handed to other apps. */
@Composable
actual fun rememberQuickActions(): QuickActions? = null
