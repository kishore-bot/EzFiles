package com.ezfiles.reader.presentation.pdftools

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.PdfPageSize
import kotlin.math.abs

private const val MAX_NAME_BASE = 120

/** `report.pdf` and `merged` give `report (merged).pdf`. Providers may return a path as a name, so only its end is kept. */
internal fun suggestedPdfName(original: String, suffix: String): String {
    val leaf = original.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':').trim()
    val base = (if (leaf.endsWith(".pdf", ignoreCase = true)) leaf.dropLast(4) else leaf)
        .filterNot(Char::isISOControl)
        .trim()
        .ifEmpty { "Document" }
        .take(MAX_NAME_BASE)
        .let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "$base ($suffix).pdf"
}

/** Where new pages go. Pages are counted from 1 here, as the user sees them. */
internal sealed interface InsertPosition {
    data object Start : InsertPosition

    data object End : InsertPosition

    data class After(val page: Int) : InsertPosition
}

/** The value for `PdfTools.insertImages`'s `atPage`, or `null` if [position] isn't in a document of [pageCount] pages. */
internal fun insertIndex(position: InsertPosition, pageCount: Int): Int? = when (position) {
    InsertPosition.Start -> 0
    InsertPosition.End -> pageCount
    is InsertPosition.After -> position.page.takeIf { it in 1..pageCount }
}

private const val SHAPE_TOLERANCE = 0.01f

/** A4 or Letter, by a page's width over height. A shape can't tell A4 from A5, so this names proportions only. */
internal fun describePageShape(aspectRatio: Float): String {
    if (aspectRatio <= 0f || aspectRatio.isNaN()) return "not known"
    val portrait = if (aspectRatio > 1f) 1f / aspectRatio else aspectRatio
    val match = listOf(PdfPageSize.A4, PdfPageSize.Letter)
        .firstOrNull { abs(portrait - it.widthPt / it.heightPt) < SHAPE_TOLERANCE }
        ?: return "other proportions"
    return "${match.label} proportions" + if (aspectRatio > 1f) ", landscape" else ""
}

/** The width to render a page so its longer side is at most [maxSidePx], whatever shape a hostile file declares. */
internal fun thumbnailWidthPx(aspectRatio: Float, maxSidePx: Int): Int {
    if (aspectRatio.isNaN() || aspectRatio <= 0f) return maxSidePx
    return if (aspectRatio >= 1f) maxSidePx else (maxSidePx * aspectRatio).toInt().coerceAtLeast(1)
}

/** Icons only these tools use. */
internal object PdfToolIcons {
    val Check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Delete = icon("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Undo = icon(
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
            "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
