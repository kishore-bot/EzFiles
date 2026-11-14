package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.MAX_TEXT_PDF_CHARS
import com.ezfiles.reader.platform.PageImageFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The pages someone typed: sorted, without repeats, counted from zero; or what's wrong with what they typed. */
internal sealed interface ConvertPageRange {
    data class Pages(val indices: List<Int>) : ConvertPageRange

    data class Invalid(val message: String) : ConvertPageRange
}

private const val MAX_RANGE_INPUT = 500

/** Parses "1-3, 5, 8-" where pages count from 1. An open end runs to the first or last page. */
internal fun parseConvertPageRange(text: String, pageCount: Int): ConvertPageRange {
    if (text.isBlank()) return ConvertPageRange.Invalid("Enter the pages you want, like 1-3, 5.")
    if (text.length > MAX_RANGE_INPUT) return ConvertPageRange.Invalid("That list is too long. Use ranges, like 1-3, 5.")
    if (pageCount <= 0) return ConvertPageRange.Invalid("This PDF has no pages.")
    val chosen = BooleanArray(pageCount)
    for (raw in text.replace('–', '-').replace('—', '-').split(',')) {
        val part = raw.trim()
        if (part.isEmpty()) continue
        val dash = part.indexOf('-')
        val first = if (dash < 0) part else part.substring(0, dash).trim()
        val last = if (dash < 0) part else part.substring(dash + 1).trim()
        if (first.isEmpty() && last.isEmpty()) return notUnderstood(part)
        val from = if (first.isEmpty()) 1 else first.pageNumberOrNull() ?: return notUnderstood(part)
        val to = if (last.isEmpty()) pageCount else last.pageNumberOrNull() ?: return notUnderstood(part)
        if (from < 1 || to < 1) return ConvertPageRange.Invalid("Pages start at 1.")
        if (from > pageCount || to > pageCount) return ConvertPageRange.Invalid("This PDF has $pageCount ${if (pageCount == 1) "page" else "pages"}.")
        if (from > to) return ConvertPageRange.Invalid("\"$part\" runs backwards. Try $to-$from.")
        for (page in from..to) chosen[page - 1] = true
    }
    val indices = chosen.indices.filter { chosen[it] }
    return if (indices.isEmpty()) ConvertPageRange.Invalid("Enter the pages you want, like 1-3, 5.") else ConvertPageRange.Pages(indices)
}

/** Digits only, so a stray sign or a number too large for an Int is refused rather than misread. */
private fun String.pageNumberOrNull(): Int? = takeIf { value -> value.all { it in '0'..'9' } }?.toIntOrNull()

private fun notUnderstood(part: String) = ConvertPageRange.Invalid("\"${part.take(20)}\" isn't a page or a range. Try 1-3, 5.")

private const val MAX_STEM_CHARS = 80

/** A file's name without its .pdf, safe as part of a new file's name and as a ZIP entry. */
internal fun convertFileStem(displayName: String): String {
    val withoutType = if (displayName.endsWith(".pdf", ignoreCase = true)) displayName.dropLast(4) else displayName
    val cleaned = withoutType
        .map { if (it < ' ' || it in "/\\:*?\"<>|") '_' else it }
        .joinToString("")
        .trim { it == ' ' || it == '.' }
        .take(MAX_STEM_CHARS)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .trim { it == ' ' || it == '.' }
    return cleaned.ifEmpty { "document" }
}

/** "report-page-007.jpg". Three digits at least, so files sort in page order. */
internal fun pageImageFileName(stem: String, pageIndex: Int, pageCount: Int, format: PageImageFormat): String {
    val digits = max(3, pageCount.toString().length)
    return "$stem-page-${(pageIndex + 1).toString().padStart(digits, '0')}.${format.extension}"
}

internal data class ExportImageSize(val width: Int, val height: Int, val reduced: Boolean)

internal const val MAX_EXPORT_PIXELS = 16_000_000L
internal const val MAX_EXPORT_SIDE = 8192
private const val FALLBACK_WIDTH_POINTS = 612f
private const val FALLBACK_HEIGHT_POINTS = 792f

/** The size of a page at [dpi], reduced to fit the limits. Each page's bitmap is 4 bytes per pixel, so the limits bound memory. */
internal fun exportImageSize(widthPoints: Float, heightPoints: Float, dpi: Int): ExportImageSize {
    val usable = widthPoints.isFinite() && heightPoints.isFinite() && widthPoints > 0f && heightPoints > 0f
    val width = (if (usable) widthPoints else FALLBACK_WIDTH_POINTS) * dpi / 72.0
    val height = (if (usable) heightPoints else FALLBACK_HEIGHT_POINTS) * dpi / 72.0
    val scale = min(1.0, min(MAX_EXPORT_SIDE / max(width, height), sqrt(MAX_EXPORT_PIXELS / (width * height))))
    return ExportImageSize(
        width = floor(width * scale).toInt().coerceAtLeast(1),
        height = floor(height * scale).toInt().coerceAtLeast(1),
        reduced = scale < 1.0,
    )
}

/** How a run over pages ended. [failure] is `null` when every page went through. */
internal data class PageExportResult(val written: Int, val total: Int, val failure: String?)

/**
 * Handles [pages] strictly one at a time, so one page's pixels are all that's
 * held. Stops at the first failure and reports how far it got. Cancellation
 * passes through, and [onProgress] has the count by then.
 */
internal suspend fun exportPagesOneByOne(
    pages: List<Int>,
    encode: suspend (Int) -> ByteArray,
    write: suspend (Int, ByteArray) -> Unit,
    onProgress: (done: Int, total: Int) -> Unit,
): PageExportResult {
    var written = 0
    onProgress(0, pages.size)
    for (page in pages) {
        try {
            write(page, encode(page))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentException) {
            return PageExportResult(written, pages.size, "Page ${page + 1}: ${e.error.detail}")
        } catch (_: RuntimeException) {
            // The platform renderer throws its own exceptions on damaged pages.
            return PageExportResult(written, pages.size, "Page ${page + 1} couldn't be read.")
        }
        written++
        onProgress(written, pages.size)
    }
    return PageExportResult(written, pages.size, null)
}

/** Most text a review holds: more than a text field stays usable with. */
internal const val MAX_EXTRACTED_CHARS = MAX_TEXT_PDF_CHARS

/**
 * Text from a PDF's pages, in order, up to [maxChars]. Past that it stops
 * taking any, and says where it stopped, so the memory held stays bounded.
 */
internal class PdfTextCollector(private val pageCount: Int, private val maxChars: Int = MAX_EXTRACTED_CHARS) {
    private val pages = ArrayList<Pair<Int, String>>()
    private var chars = 0

    var pagesWithText: Int = 0
        private set

    /** Pages read that had no text, as in a scan. */
    var blankPages: Int = 0
        private set

    /** The page, counted from zero, where the limit was reached. */
    var cutAtPage: Int? = null
        private set

    val isFull: Boolean get() = cutAtPage != null
    val hasText: Boolean get() = pagesWithText > 0

    /** Takes a page's text. Returns `false` once there's no room for more. */
    fun add(pageIndex: Int, raw: String): Boolean {
        if (isFull) return false
        val text = cleanExtractedText(raw)
        if (text.isBlank()) {
            blankPages++
            return true
        }
        val room = maxChars - chars
        val kept = if (text.length > room) text.take(room).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it } else text
        if (kept.isNotEmpty()) {
            pages += pageIndex to kept
            pagesWithText++
            chars += kept.length
        }
        if (text.length > room) {
            cutAtPage = pageIndex
            return false
        }
        return true
    }

    /** All the text, pages apart by a blank line, or under a heading line when [separators] is set. */
    fun assemble(separators: Boolean): String = buildString {
        pages.forEachIndexed { position, (index, text) ->
            if (position > 0) append("\n\n")
            if (separators) append(pageSeparator(index, pageCount)).append("\n\n")
            append(text)
        }
    }
}

internal fun pageSeparator(pageIndex: Int, pageCount: Int): String = "--- Page ${pageIndex + 1} of $pageCount ---"

/** One newline convention, and no control characters a text editor would show as boxes. */
internal fun cleanExtractedText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').filter { it >= ' ' || it == '\n' || it == '\t' }.trim()

/** "340 KB", "1.2 MB": for telling people, so round figures. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
    else -> {
        val tenths = (bytes * 10.0 / (1024 * 1024)).roundToLong()
        "${tenths / 10}.${tenths % 10} MB"
    }
}

/** An honest sentence about what zipping did, as PDFs are compressed inside already. */
internal fun zipSizeNote(before: Long?, after: Long, pictures: Boolean): String {
    if (pictures) return "This ZIP holds pictures of the pages. They aren't searchable, unlike the text in the PDF."
    if (before == null || before <= 0) return "PDFs are already compressed inside, so a ZIP rarely makes them much smaller."
    val saved = (before - after) * 100.0 / before
    return when {
        saved < 1 -> "About the same size: PDFs are already compressed inside. The ZIP is mainly useful for sending or storing."
        saved < 10 -> "${saved.roundToInt()}% smaller. PDFs are already compressed inside, so this is about as much as a ZIP can do."
        else -> "${saved.roundToInt()}% smaller."
    }
}
