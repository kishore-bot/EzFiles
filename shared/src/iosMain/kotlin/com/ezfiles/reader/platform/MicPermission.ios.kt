package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberMicPermission(onResult: (Boolean) -> Unit): MicPermissionState {
    return remember {
        object : MicPermissionState {
            override val granted: Boolean get() = false
            override fun request() { onResult(false) }
        }
    }
}
