package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** iOS is parked: there is no supported way to block screenshots, so this does nothing. */
@Composable
actual fun SecureWindow(enabled: Boolean) {
}
