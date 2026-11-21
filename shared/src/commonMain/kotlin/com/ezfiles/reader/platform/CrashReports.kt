package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.crash.CrashReport

/**
 * Crash reports saved on this device (see `crash/CrashReporting.kt`). Nothing
 * is ever sent by the app: [share] hands the text to an app the user picks.
 */
interface CrashReports {
    /** The newest report not yet sent or dismissed, or `null`. Reads a small file. */
    fun pending(): CrashReport?

    /** Deletes the report [id] and every older one. */
    fun dismiss(id: String)

    /** Opens the share sheet with the report as text; `false` if nothing can take it. */
    fun share(report: CrashReport): Boolean
}

/** The app itself: its version, the feedback message and outside links. */
interface AppInfo {
    val versionName: String
    val versionCode: Long

    /** Whether [sendFeedback] and [openLink] work here; the UI hides them otherwise. */
    val canHandOff: Boolean get() = true

    /** The launcher icon at [sizePx], or `null` where the platform has none to give. */
    fun icon(sizePx: Int): ImageBitmap?

    /** Opens a blank feedback message (app version and device only) in an app the user picks. */
    fun sendFeedback(): Boolean

    /**
     * Opens an https or mailto [url] in the system browser or mail app; Xfiles
     * does not load that link itself. `false` for any other scheme or when nothing can open it.
     */
    fun openLink(url: String): Boolean
}

/** Links [AppInfo.openLink] accepts: https web pages and mail addresses, nothing else. */
fun isOpenableLink(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.length > 2048 || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return false
    return trimmed.startsWith("https://", ignoreCase = true) && trimmed.length > "https://".length ||
        trimmed.startsWith("mailto:", ignoreCase = true) && trimmed.length > "mailto:".length
}

/** Platforms without crash reports (iOS for now). */
object NoCrashReports : CrashReports {
    override fun pending(): CrashReport? = null

    override fun dismiss(id: String) {}

    override fun share(report: CrashReport): Boolean = false
}

val LocalCrashReports = staticCompositionLocalOf<CrashReports> { NoCrashReports }
val LocalAppInfo = staticCompositionLocalOf<AppInfo> { error("AppInfo not provided") }
