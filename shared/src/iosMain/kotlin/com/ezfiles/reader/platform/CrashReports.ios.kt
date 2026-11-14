package com.ezfiles.reader.platform

import androidx.compose.ui.graphics.ImageBitmap
import platform.Foundation.NSBundle

/** iOS is parked: no crash reports ([NoCrashReports]), no feedback and no links yet. */
object IosAppInfo : AppInfo {
    override val versionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
    override val versionCode: Long
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull() ?: 0L

    override val canHandOff: Boolean get() = false

    override fun icon(sizePx: Int): ImageBitmap? = null

    override fun sendFeedback(): Boolean = false

    override fun openLink(url: String): Boolean = false
}
