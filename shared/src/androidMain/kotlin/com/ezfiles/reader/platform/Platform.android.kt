package com.ezfiles.reader.platform

import android.media.ExifInterface
import android.graphics.Matrix
import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalContext
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.Date

actual fun formatDate(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

actual fun decodeImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    val kind = sniffImage(bytes)
    val bitmap = if (kind == ImageKind.Heif || kind == ImageKind.Avif) {
        decodeWithImageDecoder(bytes, maxDimension, kind)
    } else {
        decodeWithBitmapFactory(bytes, maxDimension) ?: decodeWithImageDecoder(bytes, maxDimension, kind)
    }
    bitmap?.onGpu()?.asImageBitmap()
} catch (_: OutOfMemoryError) {
    throw ImageTooLargeException()
}

private fun decodeWithBitmapFactory(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    checkDeclaredSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return sampled.fittedWithin(maxDimension).upright(bytes)
}

/**
 * The same picture in GPU memory, copied on the calling (background) thread.
 * An ordinary bitmap is copied to the GPU the first time it's drawn, on the
 * thread that draws frames, which stalls scrolling for a large photo. A GPU
 * bitmap can't be read back or drawn into, which nothing here needs. Keeps
 * the ordinary one if the GPU refuses (too big for a texture, or out of memory).
 */
internal fun Bitmap.onGpu(): Bitmap {
    val copy = try {
        copy(Bitmap.Config.HARDWARE, false)
    } catch (_: RuntimeException) {
        null
    } ?: return this
    recycle()
    return copy
}

/** The first release to try each kind on; [ImageDecoder] itself arrived in API 28. */
private fun firstApiFor(kind: ImageKind): Int =
    if (kind == ImageKind.Avif) Build.VERSION_CODES.S else Build.VERSION_CODES.P

/** Applies EXIF orientation by itself. Works out what this device can do by trying. */
private fun decodeWithImageDecoder(bytes: ByteArray, maxDimension: Int, kind: ImageKind): Bitmap? {
    if (Build.VERSION.SDK_INT < firstApiFor(kind) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            limitImageDecoderSize(decoder, info, maxDimension)
            // The same kind of bitmap as BitmapFactory gives, free of GPU texture limits.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: ImageTooLargeException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Refuses a picture over the declared limit before any pixel is decoded, and
 * sets the size to decode at.
 *
 * @throws ImageTooLargeException which [ImageDecoder] passes on to its caller
 */
@RequiresApi(Build.VERSION_CODES.P)
internal fun limitImageDecoderSize(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, maxDimension: Int) {
    checkDeclaredSize(info.size.width, info.size.height)
    val (width, height) = fitWithin(info.size.width, info.size.height, maxDimension)
    decoder.setTargetSize(width, height)
}

/** Sampling works in powers of two, so it can leave a picture up to twice too big. */
private fun Bitmap.fittedWithin(maxDimension: Int): Bitmap {
    val (fittedWidth, fittedHeight) = fitWithin(width, height, maxDimension)
    if (fittedWidth == width && fittedHeight == height) return this
    return Bitmap.createScaledBitmap(this, fittedWidth, fittedHeight, true).also { if (it !== this) recycle() }
}

/** BitmapFactory ignores EXIF orientation, which is how phones mark rotated photos. */
private fun Bitmap.upright(encoded: ByteArray): Bitmap {
    val orientation = try {
        ExifInterface(encoded.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) { // Damaged metadata shouldn't cost the picture.
        ExifInterface.ORIENTATION_NORMAL
    }
    val transform = exifTransform(orientation) ?: return this
    val matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                transform.xx.toFloat(), transform.xy.toFloat(), 0f,
                transform.yx.toFloat(), transform.yy.toFloat(), 0f,
                0f, 0f, 1f,
            ),
        )
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

@Composable
actual fun PresentationMode() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@Composable
actual fun LockOrientation(locked: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(locked) {
        val previous = activity.requestedOrientation
        if (locked) {
            val current = activity.resources.configuration.orientation
            activity.requestedOrientation = if (current == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose { if (locked) activity.requestedOrientation = previous }
    }
}

@Composable
actual fun HiddenSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
actual fun rememberEnterPip(): (Float) -> Unit {
    val activity = LocalContext.current.findActivity()
    return remember(activity) {
        { aspect: Float ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                val rational = android.util.Rational(
                    (aspect * 1000).toInt().coerceIn(1, 2390),
                    1000.coerceIn(1, 2390),
                )
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .build()
                try { activity.enterPictureInPictureMode(params) } catch (_: Exception) {}
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
