package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.AnimatedImage
import com.ezfiles.reader.platform.ImageTooLargeException
import com.ezfiles.reader.platform.PhotoBytes
import com.ezfiles.reader.platform.decodeAnimatedImage
import com.ezfiles.reader.platform.decodePhotoImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PhotoKey(val uri: String, val size: Long?, val modified: Long?) {
    constructor(ref: DocumentRef) : this(ref.uri, ref.sizeBytes, ref.modifiedAt)
}

internal data class PhotoPreview(val key: PhotoKey, val bitmap: ImageBitmap?)

/** One visible decode and one adjacent static preview; no gallery-wide bitmap cache or decoder. */
internal class PhotoImageLoader(
    private val source: PhotoBytes,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val still: (ByteArray, Int) -> ImageBitmap? = ::decodePhotoImage,
    private val animated: (ByteArray, Int) -> AnimatedImage? = ::decodeAnimatedImage,
) {
    private data class Adjacent(val key: PhotoKey, val bytes: ByteArray)
    private val reads = Mutex()
    private val decodes = Mutex()
    private var adjacent: Adjacent? = null
    private var closed = false
    private val previewState = MutableStateFlow<PhotoPreview?>(null)
    val preview = previewState.asStateFlow()

    suspend fun view(ref: DocumentRef): ViewedImage {
        var animation: AnimatedImage? = null
        var transferred = false
        try {
            checkOpen()
            val key = PhotoKey(ref)
            val cached = adjacent?.takeIf { it.key == key }
            val bytes = reads.withLock {
                if (cached != null) {
                    if (source.isReadable(ref)) cached.bytes else null
                } else source.read(ref, source.limits.encodedBytes)
            } ?: return ViewedImage.Unsupported
            checkOpen()
            val result = decodes.withLock {
                withContext(dispatcher) {
                    animation = animated(bytes, source.limits.animationDimension)
                    animation?.let { ViewedImage.Animated(OwnedPhotoAnimation(it)) }
                        ?: still(bytes, source.limits.stillDimension)?.let { ViewedImage.Still(it) }
                        ?: ViewedImage.Unsupported
                }
            }
            currentCoroutineContext().ensureActive()
            checkOpen()
            transferred = true
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ImageTooLargeException) {
            return ViewedImage.TooLarge
        } catch (_: Exception) {
            return ViewedImage.Unsupported
        } finally {
            // withContext can be cancelled after a decoder is created but before its result returns.
            if (!transferred) animation?.close()
        }
    }

    /** Only a small still preview is decoded offscreen, including the first frame of animated files. */
    suspend fun prefetch(ref: DocumentRef) {
        try {
            checkOpen()
            val key = PhotoKey(ref)
            if (adjacent?.key == key) return
            adjacent = null
            previewState.value = null
            val bytes = reads.withLock { source.read(ref, source.limits.prefetchBytes) } ?: return
            val bitmap = decodes.withLock { withContext(dispatcher) { still(bytes, source.limits.previewDimension) } }
            currentCoroutineContext().ensureActive()
            checkOpen()
            adjacent = Adjacent(key, bytes)
            previewState.value = PhotoPreview(key, bitmap)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Prefetch is optional; the visible page retries its own bounded read and reports errors.
        }
    }

    fun trimMemory() {
        adjacent = null
        previewState.value = null
    }

    fun close() {
        closed = true
        trimMemory()
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("Photo viewer closed")
    }

}

/** The producer and composition may both dispose; the underlying decoder is closed exactly once. */
private class OwnedPhotoAnimation(private val delegate: AnimatedImage) : AnimatedImage {
    private var closed = false
    override val width = delegate.width
    override val height = delegate.height
    override fun start() { if (!closed) delegate.start() }
    override fun stop() { if (!closed) delegate.stop() }
    override fun draw(scope: DrawScope) { if (!closed) delegate.draw(scope) }
    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }
}
