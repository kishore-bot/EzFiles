package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfBox
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.platform.PdfTextSelection
import kotlin.math.abs
import kotlin.math.hypot

enum class PdfTool { Hand, Highlight, Pen, Note, Signature }

/** Icon width as a fraction of page width. The engine draws saved notes the same size. */
internal const val NOTE_ICON_FRACTION = 0.04f

private const val MAX_HISTORY = 200

/**
 * The annotations made in this session and their undo history. Immutable:
 * every change returns a new instance. [items] is what a save would write.
 */
class PdfAnnotationHistory private constructor(
    val items: List<PdfAnnotation>,
    private val undoStack: List<List<PdfAnnotation>>,
    private val redoStack: List<List<PdfAnnotation>>,
    private val savedItems: List<PdfAnnotation>,
) {
    private val byPage by lazy { items.groupBy { it.page } }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Differs from the last save. Undoing back to a saved state is clean again. */
    val isDirty: Boolean get() = items != savedItems

    fun onPage(page: Int): List<PdfAnnotation> = byPage[page].orEmpty()

    /** Starting a new edit discards anything that could be redone. */
    fun add(annotation: PdfAnnotation): PdfAnnotationHistory =
        PdfAnnotationHistory(items + annotation, (undoStack + listOf(items)).takeLast(MAX_HISTORY), emptyList(), savedItems)

    /** Changes the newest item without a new undo step: a signature being moved undoes in one go. */
    fun replaceLast(annotation: PdfAnnotation): PdfAnnotationHistory =
        if (items.isEmpty()) this else PdfAnnotationHistory(items.dropLast(1) + annotation, undoStack, redoStack, savedItems)

    fun undo(): PdfAnnotationHistory {
        val previous = undoStack.lastOrNull() ?: return this
        return PdfAnnotationHistory(previous, undoStack.dropLast(1), redoStack + listOf(items), savedItems)
    }

    fun redo(): PdfAnnotationHistory {
        val next = redoStack.lastOrNull() ?: return this
        return PdfAnnotationHistory(next, undoStack + listOf(items), redoStack.dropLast(1), savedItems)
    }

    /** A copy was saved; these stay editable here, as the open file doesn't have them. */
    fun markSaved(): PdfAnnotationHistory = PdfAnnotationHistory(items, undoStack, redoStack, items)

    companion object {
        val Empty = PdfAnnotationHistory(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Thins a stroke when it's finished: nearby points add weight, not shape. The first and last points always stay. */
internal fun simplifyStroke(points: List<PdfPoint>, minDistance: Float = 0.0015f, maxPoints: Int = 2000): List<PdfPoint> {
    if (points.size <= 2) return points
    var distance = minDistance
    var result = thin(points, distance)
    while (result.size > maxPoints) {
        distance *= 2
        result = thin(result, distance)
    }
    return result
}

private fun thin(points: List<PdfPoint>, minDistance: Float): List<PdfPoint> {
    val kept = ArrayList<PdfPoint>(points.size)
    kept += points.first()
    for (index in 1 until points.lastIndex) {
        val point = points[index]
        val last = kept.last()
        if (hypot(point.x - last.x, point.y - last.y) >= minDistance) kept += point
    }
    kept += points.last()
    return kept
}

/**
 * The note under [point], topmost first, or `null`. [pageAspect] is width
 * over height, because an icon is square on the page but not in fractions.
 * The target is twice the icon, for fingers.
 */
internal fun noteAt(annotations: List<PdfAnnotation>, point: PdfPoint, pageAspect: Float): PdfAnnotation.Note? {
    val halfU = NOTE_ICON_FRACTION
    val halfV = NOTE_ICON_FRACTION * pageAspect
    return annotations.asReversed().filterIsInstance<PdfAnnotation.Note>().firstOrNull {
        abs(it.x - point.x) <= halfU && abs(it.y - point.y) <= halfV
    }
}

/**
 * Fits a signature drawn on a pad onto a page, [widthFraction] of the page wide
 * and centred on [center], kept whole on the page. Aspects are width over height.
 */
internal fun placeSignature(
    strokes: List<List<PdfPoint>>,
    center: PdfPoint,
    pageAspect: Float,
    padAspect: Float,
    widthFraction: Float = 0.35f,
): List<List<PdfPoint>> {
    val heightFraction = widthFraction / padAspect * pageAspect
    val left = (center.x - widthFraction / 2).coerceIn(0f, (1f - widthFraction).coerceAtLeast(0f))
    val top = (center.y - heightFraction / 2).coerceIn(0f, (1f - heightFraction).coerceAtLeast(0f))
    return strokes.map { stroke ->
        stroke.map { PdfPoint((left + it.x * widthFraction).coerceIn(0f, 1f), (top + it.y * heightFraction).coerceIn(0f, 1f)) }
    }
}

/** What's selected on a page, and the points that reproduce it. */
data class PdfSelection(val page: Int, val text: String, val boxes: List<PdfBox>) {
    /** At each outer end, so asking again selects the same words. */
    val startAnchor: PdfPoint get() = boxes.first().let { PdfPoint(it.left, (it.top + it.bottom) / 2) }
    val endAnchor: PdfPoint get() = boxes.last().let { PdfPoint(it.right, (it.top + it.bottom) / 2) }

    companion object {
        fun of(page: Int, found: PdfTextSelection?): PdfSelection? =
            found?.takeIf { it.boxes.isNotEmpty() && it.text.isNotBlank() }?.let { PdfSelection(page, it.text, it.boxes) }
    }
}

enum class PdfSelectionHandle { Start, End }

/**
 * The range to ask the platform for after dragging a handle to [to]. A handle
 * dragged past the other end swaps them, so the range stays in reading order.
 */
internal fun PdfSelection.rangeAfterDrag(handle: PdfSelectionHandle, to: PdfPoint): Pair<PdfPoint, PdfPoint> {
    val fixed = if (handle == PdfSelectionHandle.Start) endAnchor else startAnchor
    val lineHeight = (boxes.first().bottom - boxes.first().top).coerceAtLeast(0f)
    return if (readsBefore(to, fixed, lineHeight)) to to fixed else fixed to to
}

/** Top to bottom; on one line (within half a line's height), left to right. */
private fun readsBefore(a: PdfPoint, b: PdfPoint, lineHeight: Float): Boolean =
    if (abs(a.y - b.y) > lineHeight / 2) a.y < b.y else a.x < b.x

/** The annotation a marker stroke becomes: over text if the stroke covered any, otherwise as drawn. */
internal fun markerAnnotation(
    page: Int,
    color: Int,
    width: Float,
    stroke: List<PdfPoint>,
    selected: PdfTextSelection?,
): PdfAnnotation = if (selected != null && selected.boxes.isNotEmpty() && selected.text.isNotBlank()) {
    PdfAnnotation.Highlight(page, color, selected.boxes)
} else {
    PdfAnnotation.Ink(page, color, width, listOf(stroke), PdfInkStyle.Marker)
}

/** The warning to show before a save, or `null` if there's nothing to warn about. */
internal fun saveWarning(signed: Boolean, archival: Boolean): String? = when {
    signed && archival ->
        "This PDF is digitally signed and declares PDF/A conformance. Saving annotations can invalidate its signature and its PDF/A status."
    signed -> "This PDF is digitally signed. Saving annotations can invalidate its signature."
    archival -> "This PDF declares PDF/A conformance. Saving annotations can break its PDF/A status."
    else -> null
}

/** "report.pdf" becomes "report (annotated).pdf". */
internal fun annotatedFileName(name: String): String {
    val stem = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }
    return "$stem (annotated).pdf"
}
