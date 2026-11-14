package com.ezfiles.reader.presentation.viewers

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A frozen, explicitly authorized sequence. It never discovers siblings or combines recents. */
class PhotoGallerySession private constructor(val refs: List<DocumentRef>, opened: DocumentRef) {
    private val state = MutableStateFlow(opened)
    val selected: StateFlow<DocumentRef> = state.asStateFlow()

    fun indexOf(uri: String): Int = refs.indexOfFirst { it.uri == uri }

    fun select(index: Int) {
        require(index in refs.indices)
        state.value = refs[index]
    }

    companion object {
        /** Call only with the originating library image list or an explicitly selected image batch. */
        fun create(opened: DocumentRef, authorizedInOrder: List<DocumentRef>): PhotoGallerySession? {
            val refs = authorizedInOrder.filter(::isGalleryImage).distinctBy { it.uri }.toList()
            val selected = refs.firstOrNull { it.uri == opened.uri } ?: return null
            return PhotoGallerySession(refs, selected)
        }
    }
}

val LocalPhotoGallerySession = staticCompositionLocalOf<PhotoGallerySession?> { null }

/** Retain authorized references across Activity recreation without saving an entire library in a Bundle. */
internal class PhotoGalleryState : ViewModel() {
    val session = mutableStateOf<PhotoGallerySession?>(null)
}

private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

internal fun isGalleryImage(ref: DocumentRef): Boolean =
    ref.displayName.substringAfterLast('.', "").lowercase() in PHOTO_EXTENSIONS

/** Fitted dimensions after quarter-turn rotation, and the pan limits of the actual picture. */
internal data class PhotoBounds(val fitScale: Float, val panX: Float, val panY: Float)

internal fun photoBounds(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int, zoom: Float, turns: Int): PhotoBounds {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return PhotoBounds(1f, 0f, 0f)
    val fit = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val rotated = turns.mod(2) != 0
    val width = (if (rotated) imageHeight else imageWidth) * fit
    val height = (if (rotated) imageWidth else imageHeight) * fit
    val rotationFit = minOf(viewWidth / width, viewHeight / height)
    return PhotoBounds(rotationFit, ((width * rotationFit * zoom - viewWidth) / 2).coerceAtLeast(0f), ((height * rotationFit * zoom - viewHeight) / 2).coerceAtLeast(0f))
}
