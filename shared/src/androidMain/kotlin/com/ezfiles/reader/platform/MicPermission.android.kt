package com.ezfiles.reader.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberMicPermission(onResult: (Boolean) -> Unit): MicPermissionState {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return remember(context) {
        object : MicPermissionState {
            override val granted: Boolean
                get() = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

            override fun request() {
                if (granted) onResult(true)
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
