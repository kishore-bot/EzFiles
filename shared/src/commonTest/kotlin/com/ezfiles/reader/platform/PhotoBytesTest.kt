package com.ezfiles.reader.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PhotoBytesTest {
    @Test
    fun accurateLengthDoesNotDuplicateTheFullEncodedBufferAtEof() = runTest {
        val source = ByteArray(10) { it.toByte() }
        var original: ByteArray? = null
        var position = 0
        val result = collectPhotoBytes(10, 10) { target, offset, length ->
            if (target.size == source.size) original = target
            val count = minOf(length, source.size - position)
            if (count == 0) -1 else { source.copyInto(target, offset, position, position + count); position += count; count }
        }
        assertContentEquals(source, result)
        assertSame(original, result)
    }

    @Test
    fun samplingBoundsTheIntermediateBitmapBeforeAllocation() {
        assertEquals(4, photoSampleSize(8000, 6000, 2560))
        assertEquals(8, photoSampleSize(10_001, 9999, 1600))
        assertEquals(1, photoSampleSize(1280, 720, 1600))
        assertFailsWith<ImageTooLargeException> { photoSampleSize(Int.MAX_VALUE, 100, 1600) }
    }

    @Test
    fun declaredOversizeIsRefusedBeforeAnyRead() = runTest {
        var reads = 0
        assertFailsWith<ImageTooLargeException> { collectPhotoBytes(11, 10) { _, _, _ -> reads++; -1 } }
        assertEquals(0, reads)
    }

    @Test
    fun unknownSizeCanFillTheBudgetExactlyButCannotExceedIt() = runTest {
        val source = ByteArray(10) { it.toByte() }
        var position = 0
        val result = collectPhotoBytes(null, 10) { target, offset, length ->
            val count = minOf(length, source.size - position)
            if (count == 0) -1 else { source.copyInto(target, offset, position, position + count); position += count; count }
        }
        assertContentEquals(source, result)
        assertFailsWith<ImageTooLargeException> { collectPhotoBytes(1, 10) { target, offset, length -> target.fill(1, offset, offset + length); length } }
    }

    @Test
    fun aCancelledReadStopsBetweenBoundedChunks() = runTest {
        var reads = 0
        val request = async {
            val context = currentCoroutineContext()
            collectPhotoBytes(null, 200_000) { _, _, length -> reads++; context.cancel(); length }
        }
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(1, reads)
    }
}
