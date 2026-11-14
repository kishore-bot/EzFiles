package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentRef

enum class PageImageFormat(val extension: String, val mimeType: String) {
    Jpeg("jpg", "image/jpeg"),
    Png("png", "image/png"),
}

/** What turning a PDF into pictures and archives needs from the platform. Everything throws [com.ezfiles.reader.domain.DocumentException]. */
interface PdfConverter {
    /** Encodes a page from [PdfPages.renderForExport], then frees its pixels: [image] can't be used afterwards. */
    suspend fun encodeAndRelease(image: ImageBitmap, format: PageImageFormat): ByteArray

    /** A new, empty archive in private storage. Only one exists at a time. */
    suspend fun newZip(): PdfZipWriter
}

/**
 * Builds an archive in a private file, so nothing reaches the user's storage
 * until it's complete. Entries are streamed; call one at a time. Cancelling
 * the calling coroutine stops a running [addDocument].
 */
interface PdfZipWriter {
    /** Compresses the file at [ref]. [onProgress] gets bytes read so far. Returns its full size. */
    suspend fun addDocument(ref: DocumentRef, entryName: String, onProgress: (Long) -> Unit = {}): Long

    /** Stores [bytes] as they are: pictures don't get smaller by compressing them again. */
    suspend fun addStored(entryName: String, bytes: ByteArray)

    /** Completes the archive. Returns its size. */
    suspend fun finish(): Long

    /** Copies a finished archive to [targetUri]; a failed copy doesn't leave a partial file there if it can be helped. */
    suspend fun copyTo(targetUri: String)

    /** Deletes the private file. Safe to call at any time, more than once. */
    fun discard()
}

val LocalPdfConverter = staticCompositionLocalOf<PdfConverter> { error("PdfConverter not provided") }
