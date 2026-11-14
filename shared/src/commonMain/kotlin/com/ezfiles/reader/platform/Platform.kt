package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentRef

/**
 * Pages of a PDF drawn by the platform renderer (ADR-009): Android
 * `PdfRenderer`, iOS PDFKit. All reader controls are shared Compose code on
 * top of this.
 */
interface PdfPages {
    val pageCount: Int
    val usesTextRecognition: Boolean get() = false
    val textRecognitionNotice: String? get() = null

    /** Width divided by height of a page. */
    suspend fun aspectRatio(index: Int): Float

    /** Renders a page [widthPx] wide; `null` once closed or on failure. */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?

    /**
     * Like [render], drawing into [reuse] when its memory holds the page at
     * [widthPx]. [reuse] is handed over whether or not it is used: nobody may
     * hold it or draw it any more. Platforms without reuse ignore it.
     */
    suspend fun renderReusing(index: Int, widthPx: Int, reuse: ImageBitmap?): ImageBitmap? = render(index, widthPx)

    /** Whether [search] and [hasText] work on this device. */
    val canSearch: Boolean

    /** Occurrences of [query] on a page in reading order; empty once closed or when [canSearch] is false. */
    suspend fun search(index: Int, query: String): List<PdfTextMatch>

    /** Whether a page has a text layer at all; a scanned page does not. */
    suspend fun hasText(index: Int): Boolean

    /** Whether [selectText] works on this device. */
    val canSelectText: Boolean get() = false

    /**
     * The text between two points on a page, widened to whole words; equal
     * points give the word there. `null` if there is no text, once closed, or
     * when [canSelectText] is false.
     */
    suspend fun selectText(index: Int, start: PdfPoint, end: PdfPoint): PdfTextSelection? = null

    /** Saves annotations into this file; `null` where the platform has no implementation. */
    val editor: PdfEditor? get() = null

    /** A page's width in points (1/72 inch), or `null` where the platform can't say. */
    suspend fun widthPoints(index: Int): Float? = null

    /**
     * Like [render], for saving rather than showing: past the on-screen width limit,
     * and never cached, so the caller alone holds the pixels. The caller bounds its size.
     *
     * @throws com.ezfiles.reader.domain.DocumentException when memory runs out
     */
    suspend fun renderForExport(index: Int, widthPx: Int): ImageBitmap? = render(index, widthPx)

    /** A page's text (recognized on compatibility devices); null once closed/unavailable. */
    suspend fun pageText(index: Int): String? = null

    suspend fun close()
}

/** A rectangle on a PDF page as fractions (0..1) of the page size, origin top-left, so zoom cannot break it. */
data class PdfBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One occurrence of a search query: one box per line it covers. */
data class PdfTextMatch(val boxes: List<PdfBox>)

/** A [PdfBox] from page-unit edges measured from the top-left corner; `null` if it covers nothing. */
internal fun normalisedBox(left: Float, top: Float, right: Float, bottom: Float, pageWidth: Float, pageHeight: Float): PdfBox? {
    if (pageWidth <= 0f || pageHeight <= 0f) return null
    val box = PdfBox(
        left = (minOf(left, right) / pageWidth).coerceIn(0f, 1f),
        top = (minOf(top, bottom) / pageHeight).coerceIn(0f, 1f),
        right = (maxOf(left, right) / pageWidth).coerceIn(0f, 1f),
        bottom = (maxOf(top, bottom) / pageHeight).coerceIn(0f, 1f),
    )
    return box.takeIf { it.right > it.left && it.bottom > it.top }
}

fun interface PdfOpener {
    val textRecognitionNotice: String? get() = null
    /**
     * Blocking; call off the main thread. [password] unlocks encrypted PDFs.
     *
     * @throws PdfPasswordRequired when the PDF is locked and [password] is missing or wrong
     * @throws com.ezfiles.reader.domain.DocumentException for any other failure
     */
    fun open(ref: DocumentRef, password: String?): PdfPages
}

/** The PDF is encrypted with a user password; [incorrect] when one was given but wrong. */
class PdfPasswordRequired(val incorrect: Boolean) : Exception("PDF password required")

val LocalPdfOpener = staticCompositionLocalOf<PdfOpener> { error("PdfOpener not provided") }

/**
 * Decodes a picture to at most [maxDimension] pixels on its longer side, upright;
 * for an animation, its first frame. `null` when the format can't be decoded here
 * or the file is damaged.
 *
 * @throws ImageTooLargeException when it declares too many pixels, or memory runs out
 */
expect fun decodeImage(bytes: ByteArray, maxDimension: Int): ImageBitmap?

/**
 * While composed: full-screen, landscape presentation of the current window
 * where the platform supports it (Android); nothing elsewhere.
 */
@Composable
expect fun PresentationMode()

/** A date in the user's locale, e.g. "Jan 18, 2026". */
expect fun formatDate(epochMillis: Long): String

/** Locks screen orientation to the current value while [locked] is true. */
@Composable
expect fun LockOrientation(locked: Boolean)

/** Returns a callback that enters Picture-in-Picture with the given aspect ratio; no-op where unavailable. */
@Composable
expect fun rememberEnterPip(): (Float) -> Unit

/** Handles the system back gesture where the platform has one (Android). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
