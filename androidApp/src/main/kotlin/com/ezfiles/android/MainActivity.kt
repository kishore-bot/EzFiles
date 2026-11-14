package com.ezfiles.android

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import com.ezfiles.app.App
import com.ezfiles.app.clearScanCacheOnLaunch
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.MemoryPressure
import com.ezfiles.reader.platform.AndroidMediaThumbnails
import com.ezfiles.reader.platform.LocalMediaThumbnails
import com.ezfiles.reader.platform.LocalCloudCrashReporting
import com.ezfiles.reader.platform.documentFromIntent

class MainActivity : ComponentActivity() {
    /** A document handed over by another app, until the UI has taken it. */
    private var openRequest by mutableStateOf<DocumentRef?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Bar icons follow the system until the app's theme setting is applied (SystemBarAppearance).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled its launch intent.
        if (savedInstanceState == null) {
            clearScanCacheOnLaunch(this)
            openRequest = documentFromIntent(this, intent)
            if (openRequest == null && intent.isHandover()) {
                // Nothing to show behind the sender's app, so return to it.
                explainNoFile()
                finish()
                return
            }
        }
        setContent {
            val thumbnails = remember { AndroidMediaThumbnails(applicationContext) }
            DisposableEffect(thumbnails) {
                val unregister = MemoryPressure.register(thumbnails::trimMemory)
                onDispose { unregister(); thumbnails.trimMemory() }
            }
            CompositionLocalProvider(LocalMediaThumbnails provides thumbnails,
                LocalCloudCrashReporting provides remember { (application as EzFilesApplication).cloudCrashReporting }) {
            App(
                dependencies = remember { (application as EzFilesApplication).dependencies },
                openRequest = openRequest,
                onOpenRequestHandled = { openRequest = null },
            )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest = documentFromIntent(this, intent)
        if (openRequest == null && intent.isHandover()) explainNoFile()
    }

    /**
     * In the background, cached pages and pictures are dropped so the app is
     * less likely to be killed for memory; on return, the visible ones render
     * again. Pages still on screen keep their bitmaps.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) MemoryPressure.trim()
    }

    private fun Intent.isHandover() = action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND

    /**
     * Sharing a link or some text also uses text/plain, which Android can't
     * tell apart from sharing a .txt file, so EzFiles is offered for it too.
     */
    private fun explainNoFile() {
        Toast.makeText(this, R.string.share_needs_file, Toast.LENGTH_LONG).show()
    }
}
