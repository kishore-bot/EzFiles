package com.ezfiles.reader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSMakeRange
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFSelection
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.posix.memcpy

internal object IosPdfOpener : PdfOpener {
    override fun open(ref: DocumentRef, password: String?): PdfPages {
        val url = PickedDocuments.urlFor(ref.uri)
            ?: throw DocumentException(DocumentError.FileAccess("The PDF is no longer available. Choose it again."))
        val scoped = url.startAccessingSecurityScopedResource()
        val document = PDFDocument(uRL = url)
        if (document == null) {
            if (scoped) url.stopAccessingSecurityScopedResource()
            throw DocumentException(DocumentError.Malformed("This PDF is damaged and cannot be displayed."))
        }
        if (document.isLocked() && (password == null || !document.unlockWithPassword(password))) {
            if (scoped) url.stopAccessingSecurityScopedResource()
            throw PdfPasswordRequired(incorrect = password != null)
        }
        return IosPdfPages(url, scoped, checkNotNull(document))
    }
}

/**
 * PDFKit reads pages lazily, so the security scope stays open until [close].
 * Pages are drawn by PDFKit and handed to Compose as PNG-encoded bitmaps.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPdfPages(
    private val url: NSURL,
    private val scoped: Boolean,
    private val document: PDFDocument,
) : PdfPages {
    private val lock = Mutex()
    private var closed = false
    override val pageCount: Int = document.pageCount.toInt()
    override val canSearch: Boolean = true

    override suspend fun aspectRatio(index: Int): Float = lock.withLock {
        val page = document.pageAtIndex(index.toULong()) ?: return A4_ASPECT
        page.boundsForBox(kPDFDisplayBoxCropBox).useContents {
            if (size.height > 0) (size.width / size.height).toFloat() else A4_ASPECT
        }
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) {
            val page = document.pageAtIndex(index.toULong()) ?: return@withContext null
            val scale = UIScreen.mainScreen.scale
            val (width, height) = page.boundsForBox(kPDFDisplayBoxCropBox).useContents {
                val w = widthPx.coerceAtMost(MAX_RENDER_WIDTH_PX) / scale
                w to w * size.height / size.width.coerceAtLeast(1.0)
            }
            val image = page.thumbnailOfSize(CGSizeMake(width, height), kPDFDisplayBoxCropBox)
            val png = UIImagePNGRepresentation(image) ?: return@withContext null
            val bytes = ByteArray(png.length.toInt())
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), png.bytes, png.length) }
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }
    }

    /**
     * Searches `PDFPage.string`, whose UTF-16 offsets are what `selectionForRange`
     * takes. Page by page, because `PDFDocument.findString` scans the whole
     * document at once. A phrase broken across lines has a newline there, so it
     * is not found.
     */
    override suspend fun search(index: Int, query: String): List<PdfTextMatch> = lock.withLock {
        if (closed || query.isEmpty()) return emptyList()
        withContext(Dispatchers.IO) {
            val page = document.pageAtIndex(index.toULong()) ?: return@withContext emptyList()
            val text = page.string ?: return@withContext emptyList()
            val crop = page.boundsForBox(kPDFDisplayBoxCropBox)
            val (cropX, cropY) = crop.useContents { origin.x to origin.y }
            val (cropWidth, cropHeight) = crop.useContents { size.width to size.height }
            buildList<PdfTextMatch> {
                var start = text.indexOf(query, 0, ignoreCase = true)
                while (start >= 0) {
                    val selection = page.selectionForRange(NSMakeRange(start.toULong(), query.length.toULong()))
                    val lines = selection?.selectionsByLine().orEmpty().filterIsInstance<PDFSelection>()
                    val boxes = lines.mapNotNull { line ->
                        // PDFKit's origin is the bottom-left corner; PdfBox's is the top-left.
                        line.boundsForPage(page).useContents {
                            normalisedBox(
                                left = (origin.x - cropX).toFloat(),
                                top = (cropHeight - (origin.y - cropY) - size.height).toFloat(),
                                right = (origin.x - cropX + size.width).toFloat(),
                                bottom = (cropHeight - (origin.y - cropY)).toFloat(),
                                pageWidth = cropWidth.toFloat(),
                                pageHeight = cropHeight.toFloat(),
                            )
                        }
                    }
                    if (boxes.isNotEmpty()) add(PdfTextMatch(boxes))
                    start = text.indexOf(query, start + query.length, ignoreCase = true)
                }
            }
        }
    }

    override suspend fun hasText(index: Int): Boolean = lock.withLock {
        if (closed) return false
        withContext(Dispatchers.IO) { document.pageAtIndex(index.toULong())?.string?.isNotBlank() == true }
    }

    override suspend fun close() = lock.withLock {
        if (!closed) {
            closed = true
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private companion object {
        const val A4_ASPECT = 1f / 1.414f
        const val MAX_RENDER_WIDTH_PX = 2048
    }
}
