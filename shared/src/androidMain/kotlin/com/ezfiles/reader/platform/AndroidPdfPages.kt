package com.ezfiles.reader.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Point
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ezfiles.reader.data.AndroidDocumentAccess
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class AndroidPdfOpener(private val context: Context) : PdfOpener {
    override val textRecognitionNotice: String? get() = if (deviceCanSearchPdf()) null else LEGACY_PDF_TEXT_NOTICE
    override fun open(ref: DocumentRef, password: String?): PdfPages {
        // Same access path as the engine: non-seekable providers are copied first.
        val fd = AndroidDocumentAccess.get(context).openDescriptor(ref, ReadLimits.Default)
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        return try {
            val renderer = if (password != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(descriptor)
            }
            AndroidPdfPages(descriptor, renderer, AndroidPdfEditor(context, ref, renderer.pageCount))
        } catch (e: SecurityException) {
            descriptor.close()
            // Opening encrypted PDFs needs the Android 15 PdfRenderer API.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                throw DocumentException(
                    DocumentError.Unsupported("Password-protected PDFs need Android 15 or newer on this device."),
                )
            }
            throw PdfPasswordRequired(incorrect = password != null)
        } catch (e: IOException) {
            descriptor.close()
            throw DocumentException(DocumentError.Malformed("This PDF is damaged and cannot be displayed."))
        }
    }
}

/**
 * `PdfRenderer` allows one open page at a time and is not thread-safe, so
 * every call holds [lock].
 */
private class AndroidPdfPages(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val editor: PdfEditor,
) : PdfPages {
    private val lock = Mutex()
    private var closed = false
    private val aspects = HashMap<Int, Float>()
    private var older: OlderPdfSearch? = null

    /**
     * Where pages are drawn: PdfRenderer only draws into ordinary memory. Each
     * result is copied to GPU memory right away (see [onGpu]), so this one
     * bitmap, guarded by [lock], serves every page.
     */
    private var scratch: Bitmap? = null
    override val pageCount: Int = renderer.pageCount
    private val nativeText = deviceCanSearchPdf()
    private val recognized = if (nativeText) null else LegacyPdfText(::recognitionBitmap)
    private var recognitionAllowed: Boolean? = null
    override val canSearch: Boolean = true
    override val usesTextRecognition: Boolean get() = !nativeText
    override val textRecognitionNotice: String? get() = if (nativeText) null else LEGACY_PDF_TEXT_NOTICE

    // selectContent arrives with searchText: API 35, or SDK extension 13 through PdfRendererPreV.
    override val canSelectText: Boolean = nativeText

    override suspend fun aspectRatio(index: Int): Float = lock.withLock {
        aspects[index]?.let { return it }
        if (closed) return A4_ASPECT
        val ratio = withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
        }.takeIf { it > 0f } ?: A4_ASPECT
        aspects[index] = ratio
        ratio
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = renderReusing(index, widthPx, null)

    override suspend fun renderReusing(index: Int, widthPx: Int, reuse: ImageBitmap?): ImageBitmap? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH_PX)
                val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val canvas = scratch?.takeIf { it.canHold(width, height) }
                    ?.apply { reconfigure(width, height, Bitmap.Config.ARGB_8888) }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        scratch?.recycle()
                        scratch = it
                    }
                canvas.eraseColor(android.graphics.Color.WHITE)
                page.render(canvas, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                aspects[index] = page.width.toFloat() / page.height.coerceAtLeast(1)
                // Uploaded here, off the drawing thread, instead of while the page scrolls in.
                // [reuse] has nothing to offer now: GPU bitmaps can't be redrawn into.
                (canvas.copy(Bitmap.Config.HARDWARE, false) ?: canvas.copy(Bitmap.Config.ARGB_8888, false)).asImageBitmap()
            }
        }
    }

    override suspend fun widthPoints(index: Int): Float? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) { renderer.openPage(index).use { it.width.toFloat() } }
    }

    override suspend fun renderForExport(index: Int, widthPx: Int): ImageBitmap? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceAtLeast(1)
                val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val bitmap = try {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    throw DocumentException(DocumentError.LimitExceeded("There isn't enough memory for page ${index + 1} at this resolution. Try a lower one."))
                }
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    override suspend fun pageText(index: Int): String? {
        recognized?.let { return it.page(index)?.text }
        return lock.withLock {
            if (closed || !nativeText) return null
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
                } else {
                    olderSearch().text(index)
                }
            }
        }
    }

    override suspend fun search(index: Int, query: String): List<PdfTextMatch> {
        if (query.isBlank()) return emptyList()
        recognized?.let { fallback ->
            return withContext(Dispatchers.Default) {
                fallback.page(index)?.let { page -> searchRecognizedPdf(page, query) }.orEmpty()
            }
        }
        return lock.withLock {
            if (closed || !nativeText) return emptyList()
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    renderer.openPage(index).use { page -> page.searchText(query).toMatches(page.width, page.height) }
                } else {
                    olderSearch().search(index, query)
                }
            }
        }
    }

    override suspend fun hasText(index: Int): Boolean = lock.withLock {
        // OCR does not establish that the PDF contains an actual selectable text layer.
        if (closed || !nativeText) return false
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                renderer.openPage(index).use { page -> page.textContents.any { it.text.isNotBlank() } }
            } else {
                olderSearch().hasText(index)
            }
        }
    }

    override suspend fun selectText(index: Int, start: PdfPoint, end: PdfPoint): PdfTextSelection? = lock.withLock {
        if (closed || !canSelectText) return null
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                renderer.openPage(index).use { page ->
                    page.selectContent(start.toBoundary(page.width, page.height), end.toBoundary(page.width, page.height))
                        .toSelection(page.width, page.height)
                }
            } else {
                olderSearch().select(index, start, end)
            }
        }
    }

    /** Opened on the first search, as most readers never search. Call only while holding [lock]. */
    @SuppressLint("NewApi") // Reached only when canSearch holds below Android 15.
    private fun olderSearch(): OlderPdfSearch =
        older ?: OlderPdfSearch(descriptor).also { older = it }

    private suspend fun recognitionBitmap(index: Int): Bitmap? {
        if (recognitionAllowed == null) {
            val inspection = editor.inspect()
            recognitionAllowed = inspection.saveBlock != PdfSaveBlock.Encrypted && inspection.saveBlock !is PdfSaveBlock.Unreadable
        }
        if (recognitionAllowed != true) throw DocumentException(DocumentError.Unsupported("Recognizing text from an encrypted or unreadable PDF isn't available on this Android version."))
        var owned: Bitmap? = null
        try {
            return lock.withLock {
                if (closed || index !in 0 until pageCount) return@withLock null
                withContext(Dispatchers.IO) {
                    renderer.openPage(index).use { page ->
                        val w = page.width.coerceAtLeast(1).toDouble()
                        val h = page.height.coerceAtLeast(1).toDouble()
                        val scale = minOf(2.0, 2048.0 / maxOf(w, h), sqrt(2_000_000.0 / (w * h)))
                        val bitmap = createBitmap((w * scale).roundToInt().coerceAtLeast(32), (h * scale).roundToInt().coerceAtLeast(32), Bitmap.Config.ARGB_8888)
                        owned = bitmap
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.also { owned = null }
            }
        } catch (_: OutOfMemoryError) {
            throw DocumentException(DocumentError.LimitExceeded("There isn't enough memory to recognize this PDF page."))
        } finally { owned?.recycle() }
    }

    @SuppressLint("NewApi") // older is only set when the SDK extension is present.
    override suspend fun close() = lock.withLock {
        if (!closed) {
            closed = true
            recognized?.close()
            older?.close()
            renderer.close()
            descriptor.close()
            scratch?.recycle()
            scratch = null
        }
    }

    /** The same rule as the shared page cache's: whole ARGB_8888 pixels fit in its memory. */
    private fun Bitmap.canHold(width: Int, height: Int): Boolean =
        isMutable && !isRecycled && allocationByteCount.toLong() >= width.toLong() * height * 4

    private companion object {
        const val A4_ASPECT = 1f / 1.414f

        /** Wider bitmaps cost memory (4 bytes per pixel) without visible gain. */
        const val MAX_RENDER_WIDTH_PX = 2048
    }
}

/**
 * Android 12-14 only: `PdfRenderer.Page` has no text search before API 35, but
 * `PdfRendererPreV` (SDK extension 13) does. It is a second renderer on a
 * duplicate of the descriptor, and is only used while holding the owner's lock.
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = MIN_S_EXTENSION_FOR_PDF_SEARCH)
private class OlderPdfSearch(descriptor: ParcelFileDescriptor) : AutoCloseable {
    private val renderer = PdfRendererPreV(descriptor.dup())

    fun search(index: Int, query: String): List<PdfTextMatch> =
        renderer.openPage(index).use { page -> page.searchText(query).toMatches(page.width, page.height) }

    fun hasText(index: Int): Boolean =
        renderer.openPage(index).use { page -> page.textContents.any { it.text.isNotBlank() } }

    fun text(index: Int): String =
        renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }

    fun select(index: Int, start: PdfPoint, end: PdfPoint): PdfTextSelection? =
        renderer.openPage(index).use { page ->
            page.selectContent(start.toBoundary(page.width, page.height), end.toBoundary(page.width, page.height))
                .toSelection(page.width, page.height)
        }

    override fun close() = renderer.close()
}

private const val MIN_S_EXTENSION_FOR_PDF_SEARCH = 13

private fun deviceCanSearchPdf(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
        (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= MIN_S_EXTENSION_FOR_PDF_SEARCH
            )

/** Page units are whole points from the top-left corner, the same as the rectangles coming back. */
@SuppressLint("NewApi") // Callers run on API 35 or with the SDK extension.
private fun PdfPoint.toBoundary(pageWidth: Int, pageHeight: Int): SelectionBoundary =
    SelectionBoundary(Point((x * pageWidth).toInt().coerceIn(0, pageWidth), (y * pageHeight).toInt().coerceIn(0, pageHeight)))

@SuppressLint("NewApi") // Callers run on API 35 or with the SDK extension.
private fun PageSelection?.toSelection(pageWidth: Int, pageHeight: Int): PdfTextSelection? {
    val contents = this?.selectedTextContents.orEmpty()
    val boxes = contents.flatMap { content ->
        content.bounds.mapNotNull { normalisedBox(it.left, it.top, it.right, it.bottom, pageWidth.toFloat(), pageHeight.toFloat()) }
    }
    val text = contents.joinToString("") { it.text }
    return PdfTextSelection(text, boxes).takeIf { text.isNotBlank() && boxes.isNotEmpty() }
}

/** `PageMatchBounds` gives one rectangle per line, in points from the page's top-left corner. */
@SuppressLint("NewApi") // Callers run on API 35 or with the SDK extension.
private fun List<PageMatchBounds>.toMatches(pageWidth: Int, pageHeight: Int): List<PdfTextMatch> = mapNotNull { match ->
    match.bounds
        .mapNotNull { normalisedBox(it.left, it.top, it.right, it.bottom, pageWidth.toFloat(), pageHeight.toFloat()) }
        .takeIf { it.isNotEmpty() }
        ?.let(::PdfTextMatch)
}
