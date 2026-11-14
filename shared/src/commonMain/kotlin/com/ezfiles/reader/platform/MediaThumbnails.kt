package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentRef

data class MediaThumbnail(val image: ImageBitmap, val durationSeconds: Long? = null)

/** Small local previews only; unsupported platforms retain their ordinary file icons. */
interface MediaThumbnails {
    suspend fun load(ref: DocumentRef, video: Boolean, sizePx: Int): MediaThumbnail?
}

private object NoMediaThumbnails : MediaThumbnails {
    override suspend fun load(ref: DocumentRef, video: Boolean, sizePx: Int): MediaThumbnail? = null
}

val LocalMediaThumbnails = staticCompositionLocalOf<MediaThumbnails> { NoMediaThumbnails }
