package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.AnimatedImage
import com.ezfiles.reader.platform.PhotoBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class PhotoSource : PhotoBytes {
    val reads = mutableListOf<String>()
    var readable = true
    var onRead: suspend (DocumentRef) -> ByteArray = { byteArrayOf(it.uri.last().code.toByte()) }
    override suspend fun read(ref: DocumentRef, maxBytes: Int): ByteArray { reads += ref.uri; return onRead(ref) }
    override suspend fun isReadable(ref: DocumentRef) = readable
}

private class TestPhotoAnimation : AnimatedImage {
    override val width = 10
    override val height = 10
    var closes = 0
    override fun start() = Unit
    override fun stop() = Unit
    override fun draw(scope: DrawScope) = Unit
    override fun close() { closes++ }
}

class PhotoImageLoaderTest {
    @Test
    fun onlyOneAdjacentPhotoIsKeptAndEqualNamesNeverShareBytes() = runTest {
        val source = PhotoSource()
        val loader = PhotoImageLoader(source, StandardTestDispatcher(testScheduler), still = { _, _ -> null }, animated = { _, _ -> null })
        val first = DocumentRef("first", "same.jpg", 1)
        val second = DocumentRef("second", "same.jpg", 1)
        loader.prefetch(first)
        loader.prefetch(second)
        loader.view(first)
        assertEquals(listOf("first", "second", "first"), source.reads)
        assertEquals(PhotoKey(second), loader.preview.value?.key)
        loader.close()
        assertEquals(null, loader.preview.value)
    }

    @Test
    fun revokedAccessCannotUseAlreadyPrefetchedBytes() = runTest {
        val source = PhotoSource()
        var decodes = 0
        val loader = PhotoImageLoader(source, StandardTestDispatcher(testScheduler), still = { _, _ -> null }, animated = { _, _ -> decodes++; null })
        val ref = DocumentRef("first", "photo.gif", 1)
        loader.prefetch(ref)
        source.readable = false
        assertEquals(ViewedImage.Unsupported, loader.view(ref))
        assertEquals(0, decodes)
    }

    @Test
    fun cancelledPrefetchCannotReplaceTheNewNeighbor() = runTest {
        val source = PhotoSource()
        val started = CompletableDeferred<Unit>()
        source.onRead = { ref -> if (ref.uri == "old") { started.complete(Unit); awaitCancellation() } else byteArrayOf(1) }
        val loader = PhotoImageLoader(source, StandardTestDispatcher(testScheduler), still = { _, _ -> null }, animated = { _, _ -> null })
        val old = launch { loader.prefetch(DocumentRef("old", "same.jpg")) }
        started.await()
        old.cancelAndJoin()
        loader.prefetch(DocumentRef("new", "same.jpg"))
        assertEquals("new", loader.preview.value?.key?.uri)
    }

    @Test
    fun cancellationAfterDecoderCreationClosesTheUnpublishedAnimation() = runTest {
        val animation = TestPhotoAnimation()
        lateinit var request: Job
        val loader = PhotoImageLoader(PhotoSource(), StandardTestDispatcher(testScheduler), still = { _, _ -> null }, animated = { _, _ -> request.cancel(); animation })
        request = launch(start = CoroutineStart.LAZY) { loader.view(DocumentRef("photo", "photo.gif")) }
        request.start()
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, animation.closes)
    }

    @Test
    fun aPublishedAnimationCanBeDisposedByProducerAndCompositionOnlyOnce() = runTest {
        val animation = TestPhotoAnimation()
        val loader = PhotoImageLoader(PhotoSource(), StandardTestDispatcher(testScheduler), still = { _, _ -> null }, animated = { _, _ -> animation })
        val image = loader.view(DocumentRef("photo", "photo.gif")) as ViewedImage.Animated
        image.animation.close()
        image.animation.close()
        assertEquals(1, animation.closes)
    }
}
