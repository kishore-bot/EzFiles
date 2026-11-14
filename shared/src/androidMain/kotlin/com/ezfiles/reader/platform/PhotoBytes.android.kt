package com.ezfiles.reader.platform

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@Composable
actual fun rememberPhotoBytes(): PhotoBytes {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val constrained = Build.VERSION.SDK_INT < 28 || manager?.isLowRamDevice == true || (manager?.memoryClass ?: 256) <= 192
        object : PhotoBytes {
            override val limits = if (constrained) PhotoLoadLimits(2560, 1536, 768, MAX_PHOTO_BYTES, 2 * 1024 * 1024) else PhotoLoadLimits()
            override suspend fun isReadable(ref: DocumentRef): Boolean = withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(ref.uri)
                    uri.scheme == "content" && context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }

            override suspend fun read(ref: DocumentRef, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(ref.uri)
                    if (uri.scheme != "content") return@withContext null
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.createInputStream().use { input ->
                            collectPhotoBytes(descriptor.length.takeIf { it >= 0 } ?: ref.sizeBytes, maxBytes, input::read)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (large: ImageTooLargeException) {
                    throw large
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

actual fun decodePhotoImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        decodeTargetedPhoto(bytes, maxDimension)?.asImageBitmap()
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            checkDeclaredSize(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { sampled ->
                val orientation = try {
                    ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
                val transform = exifTransform(orientation)
                val upright = if (transform == null) sampled else {
                    val matrix = Matrix().apply {
                        setValues(floatArrayOf(transform.xx.toFloat(), transform.xy.toFloat(), 0f, transform.yx.toFloat(), transform.yy.toFloat(), 0f, 0f, 0f, 1f))
                    }
                    Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true).also { if (it !== sampled) sampled.recycle() }
                }
                upright.onGpu().asImageBitmap()
            }
        }
    }
} catch (_: OutOfMemoryError) {
    throw ImageTooLargeException()
}

@RequiresApi(28)
private fun decodeTargetedPhoto(bytes: ByteArray, maxDimension: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        limitImageDecoderSize(decoder, info, maxDimension)
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
} catch (large: ImageTooLargeException) {
    throw large
} catch (_: Exception) {
    null
}
