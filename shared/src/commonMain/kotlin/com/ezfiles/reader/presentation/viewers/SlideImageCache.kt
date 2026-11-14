package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.platform.AnimatedImage
import com.ezfiles.reader.platform.ImageTooLargeException
import com.ezfiles.reader.platform.decodeAnimatedImage
import com.ezfiles.reader.platform.decodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Decoded pictures kept per document, by their pixels: about eight at the
 * widest reading size (1600 x 1200), more than a screen shows plus the way
 * back, or one full-screen 4096 x 4096 picture. A fixed ceiling rather than a
 * share of the heap: Android keeps bitmap pixels outside the Java heap, so its
 * size says little about what the device can spare.
 */
internal const val DEFAULT_MAX_PICTURE_BYTES = 64L * 1024 * 1024

/** Only a guard against endless tiny entries (a failure costs no bytes); the byte limit is the real one. */
internal const val DEFAULT_MAX_PICTURES = 256

// Pictures decode as ARGB_8888, so width x height x 4 is their pixel memory.
private const val BYTES_PER_PIXEL = 4L

/** What the full-screen viewer has to show. */
sealed interface ViewedImage {
    data class Still(val bitmap: ImageBitmap) : ViewedImage

    /** Not shared: the viewer that receives it closes it. */
    data class Animated(val animation: AnimatedImage) : ViewedImage

    data object TooLarge : ViewedImage

    /** A format this device can't decode, or a damaged file. */
    data object Unsupported : ViewedImage
}

/**
 * An LRU of results limited by their size in bytes, and loosely by count.
 * Requests for the same key share one computation, which belongs to [scope]
 * rather than to a caller.
 *
 * A running computation counts for nothing until it finishes, and the byte limit
 * doesn't evict one, so waiting callers go on sharing it. A result bigger than
 * the whole limit goes to its callers but isn't kept: keeping it would push out
 * everything else and still break the limit. Eviction only forgets a result:
 * whoever holds it can still use it. Call [get] from the thread [scope] runs on.
 */
internal class DecodeCache<T : Any>(
    private val scope: CoroutineScope,
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
) {
    private class Entry<T> {
        lateinit var result: Deferred<T?>
        var finished = false
        var bytes = 0L
    }

    private val entries = LinkedHashMap<String, Entry<T>>()
    private var totalBytes = 0L

    val heldBytes: Long get() = totalBytes

    suspend fun get(key: String, compute: suspend () -> T?): T? {
        val existing = entries.remove(key)
        val entry = existing ?: Entry()
        entries[key] = entry
        if (existing == null) {
            entry.result = scope.async {
                val value = try {
                    compute()
                } catch (_: Exception) {
                    null
                }
                entry.finished = true
                // It may have been evicted, and even replaced, while it ran.
                if (entries[key] === entry) {
                    val bytes = value?.let(sizeOf) ?: 0L
                    if (bytes > maxBytes) {
                        entries.remove(key)
                    } else {
                        entry.bytes = bytes
                        totalBytes += bytes
                        trim(keep = key)
                    }
                }
                value
            }
        }
        trim(keep = key)
        return entry.result.await()
    }

    /** Forgets every finished result, for memory pressure. Running ones stay shared with their callers. */
    fun clearFinished() {
        val all = entries.values.iterator()
        while (all.hasNext()) {
            val entry = all.next()
            if (entry.finished) {
                all.remove()
                totalBytes -= entry.bytes
            }
        }
    }

    private fun trim(keep: String) {
        val eldestFirst = entries.iterator()
        while (eldestFirst.hasNext() && (entries.size > maxEntries || totalBytes > maxBytes)) {
            val (key, entry) = eldestFirst.next()
            if (key == keep || (!entry.finished && entries.size <= maxEntries)) continue
            eldestFirst.remove()
            totalBytes -= entry.bytes
        }
    }
}

/**
 * Pictures of one open document, decoded off the main thread and kept in a
 * small LRU limited by their decoded size, so paging back and forth rarely
 * decodes twice. Requests for the same picture share one decode, which belongs
 * to the cache rather than to a caller: a thumbnail scrolling away must not
 * cancel the decode the slide is also waiting for. Call [load] and [view] from
 * the main thread.
 */
class SlideImageCache(
    private val bytes: suspend (String) -> ByteArray?,
    maxEntries: Int = DEFAULT_MAX_PICTURES,
    maxBytes: Long = DEFAULT_MAX_PICTURE_BYTES,
) : SlideImages {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = DecodeCache<ViewedImage>(scope, maxEntries, maxBytes) { result ->
        if (result is ViewedImage.Still) result.bitmap.width.toLong() * result.bitmap.height * BYTES_PER_PIXEL else 0L
    }

    /** Frees decoded pictures nobody is waiting for; on memory pressure. */
    fun trimMemory() = cache.clearFinished()

    /** Stops decodes still running for a document that has closed. */
    fun close() = scope.cancel()

    override suspend fun bytes(path: String): ByteArray? = bytes.invoke(path)

    override suspend fun load(path: String, maxDimension: Int): ImageBitmap? =
        (still(path, maxDimension) { bytes(path) } as? ViewedImage.Still)?.bitmap

    override suspend fun view(path: String, maxDimension: Int, maxAnimatedDimension: Int): ViewedImage {
        val encoded = try {
            bytes(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ViewedImage.Unsupported
        val animation = withContext(Dispatchers.Default) { decodeAnimatedImage(encoded, maxAnimatedDimension) }
        if (animation != null) return ViewedImage.Animated(animation)
        return still(path, maxDimension) { encoded }
    }

    private suspend fun still(path: String, maxDimension: Int, encoded: suspend () -> ByteArray?): ViewedImage =
        cache.get("$path@$maxDimension") {
            val data = encoded() ?: return@get ViewedImage.Unsupported
            try {
                withContext(Dispatchers.Default) { decodeImage(data, maxDimension) }
                    ?.let { ViewedImage.Still(it) }
                    ?: ViewedImage.Unsupported
            } catch (_: ImageTooLargeException) {
                ViewedImage.TooLarge
            }
        } ?: ViewedImage.Unsupported
}
