// Only API 26/27 use platform EXIF; modern decodes use ImageDecoder's orientation handling.
// Keep the existing platform reader instead of adding another image dependency for this fallback.
@file:Suppress("ExifInterface")

package com.ezfiles.reader.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Point
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.core.graphics.scale
import com.ezfiles.reader.domain.DocumentRef
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Activity-owned memory cache: no copied media, disk cache, network client or playback. */
class AndroidMediaThumbnails(context: Context) : MediaThumbnails {
    private val resolver = context.applicationContext.contentResolver
    private val slots = Semaphore(2)
    private val lock = Any()
    private var generation = 0
    private data class Key(val uri: String, val bytes: Long?, val modified: Long?, val video: Boolean, val side: Int)
    private data class Entry(val bitmap: Bitmap, val preview: MediaThumbnail)
    private val cache = object : LruCache<Key, Entry>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Entry): Int = value.bitmap.allocationByteCount
    }
    private val failures = LruCache<Key, Long>(128)

    fun trimMemory() = synchronized(lock) {
        generation++
        // Visible Compose images still own their bitmaps: eviction must not recycle them.
        cache.evictAll()
        failures.evictAll()
    }

    override suspend fun load(ref: DocumentRef, video: Boolean, sizePx: Int): MediaThumbnail? {
        val key = Key(ref.uri, ref.sizeBytes, ref.modifiedAt, video, sizePx.coerceIn(64, 256))
        return slots.withPermit {
            runInterruptible(Dispatchers.IO) {
                val epoch = synchronized(lock) {
                    cache.get(key)?.let { return@runInterruptible it.preview }
                    failures.get(key)?.let { if (SystemClock.elapsedRealtime() - it < 30_000L) return@runInterruptible null }
                    generation
                }
                val entry = try {
                    val uri = ref.uri.toUri()
                    if (uri.scheme != "content") null else {
                        val decoded = if (video) videoPreview(ref, key.side) else imagePreview(ref, key.side)?.let { it to null }
                        decoded?.let { (bitmap, duration) ->
                            val bounded = bound(bitmap, key.side)
                            Entry(bounded, MediaThumbnail(bounded.asImageBitmap(), duration))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                } catch (_: ImageTooLargeException) {
                    null
                } catch (_: OutOfMemoryError) {
                    trimMemory()
                    null
                }
                synchronized(lock) {
                    if (generation == epoch) {
                        if (entry != null) cache.put(key, entry) else failures.put(key, SystemClock.elapsedRealtime())
                    }
                }
                entry?.preview
            }
        }
    }

    private fun imagePreview(ref: DocumentRef, side: Int): Bitmap? {
        val uri = ref.uri.toUri()
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                checkDeclaredSize(info.size.width, info.size.height)
                val ratio = minOf(1.0, side.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, side)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try {
            resolver.openFileDescriptor(uri, "r")?.use {
                ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL
        } catch (_: RuntimeException) { ExifInterface.ORIENTATION_NORMAL }
        val turn = exifTransform(orientation) ?: return bitmap
        val matrix = Matrix().apply {
            setValues(floatArrayOf(turn.xx.toFloat(), turn.xy.toFloat(), 0f, turn.yx.toFloat(), turn.yy.toFloat(), 0f, 0f, 0f, 1f))
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun videoPreview(ref: DocumentRef, side: Int): Pair<Bitmap, Long?>? {
        val uri = ref.uri.toUri()
        var bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(side, side), null)
            else DocumentsContract.getDocumentThumbnail(resolver, uri, Point(side, side), null)
        } catch (_: IOException) { null } catch (_: RuntimeException) { null }
        var duration: Long? = null
        try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }?.div(1000)
                    if (bitmap == null && Build.VERSION.SDK_INT >= 27) {
                        bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
                    }
                } finally {
                    retriever.release()
                }
            }
        } catch (_: IOException) { /* A provider thumbnail can still be used without duration. */
        } catch (_: RuntimeException) { }
        return bitmap?.let { it to duration }
    }

    private fun bound(bitmap: Bitmap, side: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= side) return bitmap
        val ratio = side.toDouble() / longest
        return bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
