package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.dateWithTimeIntervalSince1970

private val dateFormatter = NSDateFormatter().apply {
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterNoStyle
}

actual fun formatDate(epochMillis: Long): String =
    dateFormatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))

/** iOS has no system back button; screens provide their own back control. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun decodeImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (e: IllegalArgumentException) {
    null
}

@Composable
actual fun LockOrientation(locked: Boolean) = Unit

@Composable
actual fun rememberEnterPip(): (Float) -> Unit = { _ -> }

/** Presenting fills the window already; orientation follows the device. */
@Composable
actual fun PresentationMode() = Unit
