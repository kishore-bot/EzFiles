package com.ezfiles.reader.domain

/**
 * One picture (JPEG, PNG or anything else the platform decodes) that becomes a
 * PDF page, turned clockwise by [rotationDegrees] after any EXIF orientation.
 * Prefer the [uri] form: the picture is then read only while its page is made,
 * so a long list holds no pixels. Exactly one of [encoded] and [uri] is set.
 */
class PdfPageImage private constructor(val encoded: ByteArray?, val uri: String?, val rotationDegrees: Int) {
    constructor(encoded: ByteArray, rotationDegrees: Int = 0) : this(encoded, null, rotationDegrees)

    constructor(uri: String, rotationDegrees: Int = 0) : this(null, uri, rotationDegrees)
}

enum class PdfPageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595f, 842f),
    Letter("Letter", 612f, 792f),

    /** Each page takes the shape of its picture. */
    FitImage("Fit picture", 0f, 0f),
}

/** The longest picture side kept, and the JPEG quality when the picture is stored as JPEG. */
/**
 * [jpegQuality] is the most a page is given; each page then gets the lowest
 * quality whose result still scores [similarity] (SSIM) against the original,
 * so plain pages shrink further and detailed ones keep what they need.
 */
enum class PdfImageQuality(val label: String, val maxSidePx: Int, val jpegQuality: Int, val similarity: Float) {
    High("High quality", 2600, 90, 0.985f),
    Balanced("Balanced", 2000, 78, 0.97f),
    Small("Smaller file", 1400, 62, 0.94f),
}

/** A line of OCR text and where it sits on its picture, as fractions of the picture's size (0..1). */
data class PdfTextLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One page of [PdfCreator.textPreview], drawn by the code that draws the export. */
class TextPagePreview(val png: ByteArray, val pageIndex: Int, val pageCount: Int, val widthPt: Int, val heightPt: Int)

/**
 * Creates PDFs and writes them to a URI from [com.ezfiles.reader.platform.DocumentSaver].
 * Nothing touches the target until the output is complete, so cancelling or
 * failing never damages a file the user chose to overwrite.
 * Throws [DocumentException] on failure.
 */
interface PdfCreator {
    /**
     * One page per picture, fitted inside [marginPt]. With [textLayers] (one
     * list per page) the page also gets invisible text, so the scan is searchable.
     * [onProgress] gets (pages done, total) from a background thread.
     */
    suspend fun imagesToPdf(
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        marginPt: Float,
        target: String,
        textLayers: List<List<PdfTextLine>> = emptyList(),
        quality: PdfImageQuality = PdfImageQuality.High,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    )

    /** A small upright copy of the picture at [uri], encoded, for showing in a list. */
    suspend fun imageThumbnail(uri: String, maxSidePx: Int): ByteArray

    /** Flows [text] over as many pages as it needs and returns how many that was. */
    suspend fun textToPdf(
        text: String,
        style: TextPdfStyle,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int

    /** Writes the first page as a PNG and returns how many pages the whole text takes. */
    suspend fun textToPng(text: String, style: TextPdfStyle, target: String): Int

    /** Draws one page, about [widthPx] wide, to show before exporting. [pageIndex] is clamped. */
    suspend fun textPreview(text: String, style: TextPdfStyle, pageIndex: Int, widthPx: Int): TextPagePreview
}
