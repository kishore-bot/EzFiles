package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

interface MicPermissionState {
    val granted: Boolean
    fun request()
}

@Composable
expect fun rememberMicPermission(onResult: (Boolean) -> Unit): MicPermissionState
