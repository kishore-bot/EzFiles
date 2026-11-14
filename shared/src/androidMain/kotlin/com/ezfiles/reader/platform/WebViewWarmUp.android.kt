package com.ezfiles.reader.platform

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

private var warmed = false

@Composable
internal actual fun WarmUpWebView() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (warmed) return@LaunchedEffect
        warmed = true
        // After the progress screen has drawn, so it isn't held up.
        withFrameNanos { }
        try {
            // Loading the web view's code is the slow part; one throwaway view does it.
            WebView(context.applicationContext).destroy()
        } catch (_: RuntimeException) {
            // Missing, disabled or updating: the viewer reports that when it needs one.
        }
    }
}
