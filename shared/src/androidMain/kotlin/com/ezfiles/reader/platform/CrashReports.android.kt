package com.ezfiles.reader.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ezfiles.reader.crash.CRASH_REPORT_EMAIL
import com.ezfiles.reader.crash.CrashBreadcrumbs
import com.ezfiles.reader.crash.CrashReport
import com.ezfiles.reader.crash.CrashReportFiles
import com.ezfiles.reader.crash.CrashReportFormatter
import com.ezfiles.reader.crash.CrashScrubber
import com.ezfiles.reader.crash.DeviceDescription
import com.ezfiles.reader.crash.FeedbackTemplate
import com.ezfiles.reader.crash.MAX_CRASH_REPORT_BYTES
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "EzFilesCrash"

/** Reports live in app-private storage, which no other app can read. */
private fun crashDir(context: Context) = File(context.filesDir, "crash")

/** The app version and device, read once. */
internal fun deviceDescription(context: Context): DeviceDescription {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val code = when {
        info == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
        else -> @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
    val model = Build.MODEL.orEmpty()
    return DeviceDescription(
        versionName = info?.versionName ?: "unknown",
        versionCode = code,
        system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        model = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim(),
    )
}

/**
 * Installs the crash handler: call first thing in `Application.onCreate`. An
 * uncaught exception on any thread, including engine errors that escape as
 * Kotlin exceptions and coroutine failures, is written to `filesDir/crash/`,
 * then handed to the previous handler, which closes the app as before.
 *
 * Native crashes (a signal in the engine's Rust code) kill the process
 * without reaching here, so they are not reported.
 */
fun installCrashReporter(context: Context): AndroidCrashRecorder =
    AndroidCrashRecorder(context.applicationContext, Thread.getDefaultUncaughtExceptionHandler()).also {
        Thread.setDefaultUncaughtExceptionHandler(it)
    }

/** Keep the local recorder outermost, including when the optional SDK installs its handler. */
class AndroidCrashRecorder internal constructor(
    private val app: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val device = deviceDescription(app)
    private val forwarding = ThreadLocal<Boolean>()
    private var cloud: Thread.UncaughtExceptionHandler? = null
    private var cloudAllowed: () -> Boolean = { false }

    fun captureCloudHandler(allowed: () -> Boolean) {
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        if (installed !== this && installed !== previous) cloud = installed
        cloudAllowed = allowed
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Crashlytics delegates to this recorder after processing; do not write twice or loop.
        if (forwarding.get() == true) {
            previous?.uncaughtException(thread, throwable) ?: kotlin.system.exitProcess(10)
            return
        }
        forwarding.set(true)
        try {
            try { writeCrashReport(app, device, thread.name, throwable) } catch (_: Throwable) { }
            val target = cloud.takeIf { cloudAllowed() } ?: previous
            val forwarded = if (target != null && target === cloud) {
                try { scrubCrashThrowable(throwable) } catch (_: Throwable) { RuntimeException("Crash details unavailable") }
            } else throwable
            if (target != null) target.uncaughtException(thread, forwarded) else kotlin.system.exitProcess(10)
        } finally {
            forwarding.remove()
        }
    }
}

/** Copy messages/causes with strict bounds; never give the SDK the original exception graph. */
internal fun scrubCrashThrowable(error: Throwable): Throwable {
    val seen = mutableListOf<Throwable>()
    fun copy(current: Throwable, depth: Int): Throwable {
        if (depth >= 8 || seen.size >= 16 || seen.any { it === current }) return RuntimeException("Further crash details omitted")
        seen += current
        val result = RuntimeException(CrashScrubber.scrub("${current.javaClass.name}: ${current.message.orEmpty()}").take(500))
        result.stackTrace = current.stackTrace.take(128).toTypedArray()
        current.cause?.let { result.initCause(copy(it, depth + 1)) }
        current.suppressed.take(3).forEach { result.addSuppressed(copy(it, depth + 1)) }
        return result
    }
    return copy(error, 0)
}

private fun writeCrashReport(context: Context, device: DeviceDescription, threadName: String, throwable: Throwable) {
    val now = System.currentTimeMillis()
    val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    val text = CrashReportFormatter.format(device, time, threadName, CrashBreadcrumbs.screen, throwable)
    val dir = crashDir(context)
    if (!dir.isDirectory && !dir.mkdirs()) return
    val existing = dir.list()?.toList().orEmpty()
    CrashReportFiles.toDeleteBeforeWrite(existing).forEach { File(dir, it).delete() }
    var sequence = 0
    var file = File(dir, CrashReportFiles.nameFor(now))
    while (file.exists() && sequence < 50) file = File(dir, CrashReportFiles.nameFor(now, ++sequence))
    // Written whole, then renamed, so a half-written report is never shown.
    val partial = File(dir, file.name + ".part")
    partial.writeText(text)
    if (!partial.renameTo(file)) partial.delete()
}

class AndroidCrashReports(context: Context) : CrashReports {
    private val app = context.applicationContext
    private val dir = crashDir(app)

    override fun pending(): CrashReport? {
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        // Leftovers of a write cut short.
        names.filter { it.endsWith(".part") }.forEach { File(dir, it).delete() }
        for (name in CrashReportFiles.newestFirst(names)) {
            val file = File(dir, name)
            val text = runCatching {
                // Reports are capped when written; anything bigger is not ours.
                if (file.length() > MAX_CRASH_REPORT_BYTES * 2L) null else file.readText()
            }.getOrNull()
            if (text.isNullOrBlank()) {
                file.delete()
                continue
            }
            val version = text.lineSequence().firstOrNull { it.startsWith("App: ") }?.removePrefix("App: ")?.substringBefore(" (") ?: ""
            return CrashReport(id = name, text = text, versionName = version)
        }
        return null
    }

    override fun dismiss(id: String) {
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        CrashReportFiles.toDeleteOnDismiss(names, id).forEach { runCatching { File(dir, it).delete() } }
    }

    override fun share(report: CrashReport): Boolean =
        shareText(app, CrashReportFormatter.subject(report.versionName), report.text, "Send crash report")
}

class AndroidAppInfo(context: Context) : AppInfo {
    private val app = context.applicationContext
    private val device by lazy { deviceDescription(app) }

    override val versionName: String get() = device.versionName
    override val versionCode: Long get() = device.versionCode

    override fun icon(sizePx: Int): ImageBitmap? = runCatching {
        val drawable = app.packageManager.getApplicationIcon(app.packageName)
        val size = sizePx.coerceIn(16, 512)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()

    override fun sendFeedback(): Boolean =
        shareText(app, FeedbackTemplate.subject(device.versionName), FeedbackTemplate.body(device), "Send feedback")

    override fun openLink(url: String): Boolean {
        if (!isOpenableLink(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not open link", e)
            false
        }
    }
}

/**
 * The share sheet with [text], addressed to [CRASH_REPORT_EMAIL] for mail apps.
 * Xfiles accepts shared text itself, so it is left out of the list.
 */
private fun shareText(context: Context, subject: String, text: String, title: String): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(CRASH_REPORT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    val ownTargets = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(send).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())
    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (ownTargets.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownTargets.toTypedArray<Parcelable>())
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
