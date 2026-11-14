package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Preserve the existing Rust image reader's 64 MiB input limit.
internal const val MAX_PHOTO_BYTES = 64 * 1024 * 1024

data class PhotoLoadLimits(
    val stillDimension: Int = 4096,
    val animationDimension: Int = 2048,
    val previewDimension: Int = 1024,
    val encodedBytes: Int = MAX_PHOTO_BYTES,
    val prefetchBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(stillDimension in 1..4096 && animationDimension in 1..2048 && previewDimension in 1..1024)
        require(encodedBytes in 1..MAX_PHOTO_BYTES && prefetchBytes in 1..minOf(encodedBytes, 8 * 1024 * 1024))
    }
}

interface PhotoBytes {
    val limits: PhotoLoadLimits get() = PhotoLoadLimits()
    /** Reads only this reference's grant; failures return null and oversized sources are refused. */
    suspend fun read(ref: DocumentRef, maxBytes: Int): ByteArray?

    /** Recheck the grant even when the adjacent photo's bytes have already been prefetched. */
    suspend fun isReadable(ref: DocumentRef): Boolean
}

@Composable
expect fun rememberPhotoBytes(): PhotoBytes

/** Gallery decoding bounds intermediate allocations, not only the final scaled bitmap. */
expect fun decodePhotoImage(bytes: ByteArray, maxDimension: Int): ImageBitmap?

internal fun photoSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    require(width > 0 && height > 0 && maxDimension > 0)
    checkDeclaredSize(width, height)
    var sample = 1
    while ((width.toLong() + sample - 1) / sample > maxDimension || (height.toLong() + sample - 1) / sample > maxDimension) sample *= 2
    return sample
}

/** Shared bounded collector; platforms retain ownership of their stream/descriptor. */
internal suspend fun collectPhotoBytes(knownSize: Long?, maxBytes: Int, read: (ByteArray, Int, Int) -> Int): ByteArray {
    require(maxBytes in 1..MAX_PHOTO_BYTES)
    if (knownSize != null && knownSize > maxBytes) throw ImageTooLargeException()
    var data = ByteArray((knownSize?.takeIf { it > 0 }?.toInt() ?: 16_384).coerceIn(1, maxBytes))
    val probe = ByteArray(1)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (total == data.size) {
            // Probe EOF before growing: accurate metadata must not cause a second full-size allocation.
            val extra = read(probe, 0, 1)
            if (extra < 0) return data
            check(extra == 1) { "This image could not be read." }
            currentCoroutineContext().ensureActive()
            if (total == maxBytes) throw ImageTooLargeException()
            data = data.copyOf((data.size.toLong() * 2).coerceAtLeast(total + 1L).coerceAtMost(maxBytes.toLong()).toInt())
            data[total++] = probe[0]
            continue
        }
        val requested = minOf(65_536, data.size - total)
        val count = read(data, total, requested)
        if (count < 0) return if (total == data.size) data else data.copyOf(total)
        check(count > 0 && count <= requested) { "This image could not be read." }
        total += count
        if (total > maxBytes) throw ImageTooLargeException()
    }
}
