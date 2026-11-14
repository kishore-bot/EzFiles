package com.ezfiles.reader.presentation.viewers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ezfiles.reader.platform.DrawnSignature
import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPoint
import kotlin.math.max
import kotlin.math.min

internal const val SIGNATURE_DEFAULT_WIDTH = 0.35f
private const val SIGNATURE_MIN_WIDTH = 0.08f
internal const val MAX_SIGNATURE_STROKES = 64
internal const val MAX_SIGNATURE_POINTS = 2000

// The box stays within these, so a dot or a straight line is neither distorted nor impossible to place.
private const val MIN_BOX_ASPECT = 0.5f
private const val MAX_BOX_ASPECT = 12f
private const val MIN_BOX_EXTENT = 0.05f
private const val MAX_BOX_PEN = 0.2f

/**
 * Crops strokes drawn on a pad to their own box, without distorting them, so
 * "a third of the page wide" means the ink and not the empty pad around it.
 * Points are fractions of the pad, [padAspect] its width over height, and
 * [penWidth] a fraction of its width. `null` if nothing was drawn.
 */
internal fun drawnSignature(strokes: List<List<PdfPoint>>, padAspect: Float, color: Int, penWidth: Float): DrawnSignature? {
    val kept = thinned(strokes.filter { it.isNotEmpty() }.take(MAX_SIGNATURE_STROKES))
    val points = kept.flatten()
    if (points.isEmpty()) return null
    // In units of the pad's height, where a step across and a step down are the same length.
    val minX = points.minOf { it.x } * padAspect
    val maxX = points.maxOf { it.x } * padAspect
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    var boxWidth = max(maxX - minX, MIN_BOX_EXTENT)
    var boxHeight = max(maxY - minY, MIN_BOX_EXTENT)
    boxWidth = max(boxWidth, boxHeight * MIN_BOX_ASPECT)
    boxHeight = max(boxHeight, boxWidth / MAX_BOX_ASPECT)
    val left = (minX + maxX - boxWidth) / 2
    val top = (minY + maxY - boxHeight) / 2
    return DrawnSignature(
        id = 0,
        strokes = kept.map { stroke ->
            stroke.map { PdfPoint(((it.x * padAspect - left) / boxWidth).coerceIn(0f, 1f), ((it.y - top) / boxHeight).coerceIn(0f, 1f)) }
        },
        aspect = boxWidth / boxHeight,
        color = color,
        penWidth = (penWidth * padAspect / boxWidth).coerceAtMost(MAX_BOX_PEN),
    )
}

private fun thinned(strokes: List<List<PdfPoint>>): List<List<PdfPoint>> {
    var distance = 0.0015f
    var result = strokes.map { simplifyStroke(it, distance) }
    // Every stroke keeps its two ends, which the stroke limit keeps well below the point limit.
    while (result.sumOf { it.size } > MAX_SIGNATURE_POINTS && distance < 1f) {
        distance *= 2
        result = result.map { simplifyStroke(it, distance) }
    }
    return result
}

/**
 * Where a signature sits on a page: its centre and width as fractions of the
 * page. The saved strokes are always worked out from the originals, so moving
 * and resizing can't wear them down. Build with [at]; every change keeps it
 * whole on the page.
 */
internal class SignaturePlacement private constructor(
    val signature: DrawnSignature,
    val page: Int,
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val pageAspect: Float,
) {
    val heightFraction: Float get() = widthFraction / signature.aspect * pageAspect
    val left: Float get() = centerX - widthFraction / 2
    val top: Float get() = centerY - heightFraction / 2

    fun contains(point: PdfPoint): Boolean =
        point.x in left..(left + widthFraction) && point.y in top..(top + heightFraction)

    fun movedBy(dx: Float, dy: Float): SignaturePlacement =
        at(signature, page, PdfPoint(centerX + dx, centerY + dy), pageAspect, widthFraction)

    /** Resizes around the centre, within limits that keep it legible and on the page. */
    fun resizedTo(width: Float): SignaturePlacement = at(signature, page, PdfPoint(centerX, centerY), pageAspect, width)

    /**
     * After the corner handle moved by ([dx], [dy]) in fractions of the page.
     * Only movement along the box's diagonal counts, as that's the way the
     * corner can travel. Resizing is around the centre, so it counts twice.
     */
    fun handleDraggedBy(dx: Float, dy: Float): SignaturePlacement {
        val aspect = signature.aspect
        val along = (dx * aspect + dy / pageAspect) * aspect / (aspect * aspect + 1)
        return resizedTo(widthFraction + 2 * along)
    }

    /** What a save writes. */
    fun ink(): PdfAnnotation.Ink = PdfAnnotation.Ink(
        page = page,
        color = signature.color,
        width = signature.penWidth * widthFraction,
        strokes = placeSignature(signature.strokes, PdfPoint(centerX, centerY), pageAspect, signature.aspect, widthFraction),
        style = PdfInkStyle.Signature,
    )

    companion object {
        fun at(
            signature: DrawnSignature,
            page: Int,
            center: PdfPoint,
            pageAspect: Float,
            width: Float = SIGNATURE_DEFAULT_WIDTH,
        ): SignaturePlacement {
            // A tall signature on a wide page runs out of height before it runs out of width.
            val widest = min(1f, signature.aspect / pageAspect)
            val fitted = width.coerceIn(min(SIGNATURE_MIN_WIDTH, widest), widest)
            val halfWidth = fitted / 2
            // Rounding can push a full-height signature a hair over, which coerceIn wouldn't accept.
            val halfHeight = min(fitted / signature.aspect * pageAspect / 2, 0.5f)
            return SignaturePlacement(
                signature,
                page,
                center.x.coerceIn(halfWidth, 1f - halfWidth),
                center.y.coerceIn(halfHeight, 1f - halfHeight),
                fitted,
                pageAspect,
            )
        }
    }
}

/**
 * Choosing, placing and adjusting a signature, shared by the reader's annotate
 * toolbar and the Signature tool. A placed signature is the last item of the
 * caller's history from the first tap on, so it is drawn, saved and undone like
 * any other annotation.
 */
@Stable
internal class SignaturePlacer {
    /** The chooser is open. */
    var picking by mutableStateOf(false)
        private set

    /** Chosen, waiting for a tap on a page. */
    var pending by mutableStateOf<DrawnSignature?>(null)
        private set

    /** On a page and still movable. */
    var active by mutableStateOf<SignaturePlacement?>(null)
        private set

    /** The last one chosen, kept for this session even if it wasn't saved for reuse. */
    var lastUsed by mutableStateOf<DrawnSignature?>(null)
        private set

    val hint: String?
        get() = when {
            active != null -> "Drag to move, pinch or use the handle to resize. Tap outside when done."
            pending != null -> "Tap the page to place your signature"
            else -> null
        }

    fun startPicking() {
        clear()
        picking = true
    }

    fun cancelPicking() {
        picking = false
    }

    fun choose(signature: DrawnSignature) {
        picking = false
        pending = signature
        lastUsed = signature
    }

    fun placeAgain() {
        active = null
        pending = lastUsed
    }

    /** Places the pending signature, if any, centred on [point]. */
    fun placeAt(history: PdfAnnotationHistory, page: Int, point: PdfPoint, pageAspect: Float): PdfAnnotationHistory {
        val signature = pending ?: return history
        val placement = SignaturePlacement.at(signature, page, point, pageAspect)
        pending = null
        active = placement
        return history.add(placement.ink())
    }

    fun adjust(history: PdfAnnotationHistory, placement: SignaturePlacement): PdfAnnotationHistory {
        if (active == null) return history
        active = placement
        return history.replaceLast(placement.ink())
    }

    /** It stays where it is, in the history, and can no longer be moved. */
    fun finish() {
        active = null
    }

    /** Takes back the last annotation, which is the movable signature if there is one. */
    fun undo(history: PdfAnnotationHistory): PdfAnnotationHistory {
        active = null
        return history.undo()
    }

    fun clear() {
        picking = false
        pending = null
        active = null
    }
}

/** "report.pdf" becomes "report (signed).pdf". */
internal fun signedFileName(name: String): String {
    val stem = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }
    return "$stem (signed).pdf"
}
