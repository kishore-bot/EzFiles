package com.ezfiles.reader.platform

/** A place on a PDF page as fractions (0..1) of the page as displayed, origin top-left. */
data class PdfPoint(val x: Float, val y: Float)

/** Text chosen on a page: what it says and one box per run or line it covers. */
data class PdfTextSelection(val text: String, val boxes: List<PdfBox>)

enum class PdfInkStyle { Pen, Marker, Signature }

/** Colours are ARGB; their alpha is the annotation's opacity. Geometry is normalised, so zoom cannot break it. */
sealed interface PdfAnnotation {
    val page: Int
    val color: Int

    data class Highlight(override val page: Int, override val color: Int, val boxes: List<PdfBox>) : PdfAnnotation

    /** [width] is a fraction of the page width. */
    data class Ink(
        override val page: Int,
        override val color: Int,
        val width: Float,
        val strokes: List<List<PdfPoint>>,
        val style: PdfInkStyle,
    ) : PdfAnnotation

    /** A sticky note whose icon is centred on ([x], [y]). */
    data class Note(override val page: Int, override val color: Int, val x: Float, val y: Float, val text: String) : PdfAnnotation
}

/** Why annotations can't be saved into a file. */
sealed interface PdfSaveBlock {
    val message: String

    data object Encrypted : PdfSaveBlock {
        override val message = "This PDF is password-protected, so Xfiles can't save annotations into it."
    }

    data class Unreadable(val detail: String) : PdfSaveBlock {
        override val message = "Xfiles can't safely save annotations into this PDF: $detail."
    }

    /** The engine and the page renderer disagree on the pages, so a note could land on the wrong one. */
    data object PageMismatch : PdfSaveBlock {
        override val message = "Xfiles can't safely save annotations into this PDF: its page structure is unusual."
    }
}

/**
 * What a reader can do with a file. [signed] and [archival] call for a warning
 * before saving; [existing] are annotations the file already has, for drawing.
 */
data class PdfEditInfo(
    val saveBlock: PdfSaveBlock?,
    val signed: Boolean = false,
    val archival: Boolean = false,
    val existing: List<PdfAnnotation> = emptyList(),
)

/**
 * Saves annotations into a PDF as an incremental update, so the original
 * bytes, text and structure are untouched. Implementations read the file on
 * their own descriptor and do not take the renderer's lock.
 */
interface PdfEditor {
    /** Never fails: a file that can't be understood reports a [PdfSaveBlock]. */
    suspend fun inspect(): PdfEditInfo

    /** Whether the original can be updated in place, here and with the grant this app holds. */
    suspend fun canOverwrite(): Boolean

    /** Writes a new file at [targetUri]. The original is not touched. Throws [com.ezfiles.reader.domain.DocumentException]. */
    suspend fun saveCopy(annotations: List<PdfAnnotation>, targetUri: String)

    /** Appends to the original file; its existing bytes are never rewritten. Throws [com.ezfiles.reader.domain.DocumentException]. */
    suspend fun overwrite(annotations: List<PdfAnnotation>)
}
