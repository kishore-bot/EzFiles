package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.theme.rememberHapticChoice

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.Canvas
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.theme.glass
import kotlin.math.roundToInt

private const val MAX_NOTE_CHARS = 2000

/** Translucent, because they multiply with the page. */
internal val HighlightColors = listOf(0x66FFEB3B, 0x6669F0AE, 0x66FF80AB, 0x6682B1FF)
internal val PenColors = listOf(0xFF17121A, 0xFFE60023, 0xFF1565C0, 0xFF2E7D32).map { it.toInt() }
internal val HighlightWidths = listOf(0.012f, 0.02f, 0.03f)
internal val PenWidths = listOf(0.002f, 0.004f, 0.008f)
internal const val NOTE_COLOR = 0xFFFFD54F.toInt()

private object AnnotationIcons {
    val Annotate = icon("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    val Hand = icon("M23,5.5V20c0,2.2 -1.8,4 -4,4h-7.3c-1.08,0 -2.1,-0.43 -2.85,-1.19L1,14.83c0,0 1.26,-1.23 1.3,-1.25c0.22,-0.19 0.49,-0.29 0.79,-0.29c0.22,0 0.42,0.06 0.6,0.16C3.73,13.46 8,15.91 8,15.91V4c0,-0.83 0.67,-1.5 1.5,-1.5S11,3.17 11,4v7h1V1.5C12,0.67 12.67,0 13.5,0S15,0.67 15,1.5V11h1V2.5C16,1.67 16.67,1 17.5,1S19,1.67 19,2.5V11h1V5.5C20,4.67 20.67,4 21.5,4S23,4.67 23,5.5z")
    val Highlight = icon("M6,14l3,3v5h6v-5l3,-3V9H6V14zM11,2h2v3h-2V2zM3.5,5.88l1.41,-1.41l2.12,2.12L5.62,8L3.5,5.88zM16.96,6.59l2.12,-2.12l1.41,1.41L18.38,8L16.96,6.59z")
    val Note = icon("M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM18,14H6v-2h12V14zM18,11H6V9h12V11zM18,8H6V6h12V8z")
    val Signature = icon("M4.59,6.89c0.7,-0.71 1.4,-1.35 1.71,-1.22c0.5,0.2 0,1.03 -0.3,1.52c-0.25,0.42 -2.86,3.89 -2.86,6.31c0,1.28 0.48,2.34 1.34,2.98c0.75,0.56 1.74,0.73 2.64,0.46c1.07,-0.31 1.95,-1.4 3.06,-2.77c1.21,-1.49 2.83,-3.44 4.08,-3.44c1.63,0 1.65,1.01 1.76,1.79c-3.78,0.64 -5.38,3.67 -5.38,5.37c0,1.7 1.44,3.09 3.21,3.09c1.63,0 4.29,-1.33 4.69,-6.1H21v-2.5h-2.47c-0.15,-1.65 -1.09,-4.2 -4.03,-4.2c-2.25,0 -4.18,1.91 -4.94,2.84c-0.58,0.73 -2.06,2.48 -2.29,2.72c-0.25,0.3 -0.68,0.84 -1.11,0.84c-0.45,0 -0.72,-0.83 -0.36,-1.92c0.35,-1.09 1.4,-2.86 1.85,-3.52c0.78,-1.14 1.3,-1.92 1.3,-3.28C8.95,3.69 7.31,3 6.44,3C5.12,3 3.97,4 3.72,4.25C3.36,4.61 3.06,4.91 2.84,5.18L4.59,6.89zM13.88,18.55c-0.31,0 -0.74,-0.26 -0.74,-0.72c0,-0.6 0.73,-2.2 2.87,-2.76C15.71,17.76 14.58,18.55 13.88,18.55z")
    val Undo = icon("M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88c3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Redo = icon("M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5c1.95,0 3.73,0.72 5.12,1.88L13,16h9V7L18.4,10.6z")
    val Save = icon("M17,3H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7L17,3zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10V9z")
    val Done = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Copy = icon("M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}

internal val AnnotateIcon: ImageVector get() = AnnotationIcons.Annotate
internal val UndoIcon: ImageVector get() = AnnotationIcons.Undo
internal val RedoIcon: ImageVector get() = AnnotationIcons.Redo
internal val SignatureIcon: ImageVector get() = AnnotationIcons.Signature
internal val SaveIcon: ImageVector get() = AnnotationIcons.Save
internal val CopyIcon: ImageVector get() = AnnotationIcons.Copy

/** What a page does with touches, decided by the reader. */
internal class PdfPageActions(
    val tool: PdfTool,
    val inkColor: Int,
    val inkWidth: Float,
    val canSelectText: Boolean,
    val onTap: (page: Int, point: PdfPoint, pageAspect: Float) -> Unit,
    val onLongPress: (page: Int, point: PdfPoint) -> Unit,
    val onExtendSelection: (page: Int, to: PdfPoint) -> Unit,
    val onHandleDrag: (PdfSelectionHandle, to: PdfPoint) -> Unit,
    val onStroke: (page: Int, points: List<PdfPoint>) -> Unit,
    val placement: SignaturePlacement? = null,
    val onPlacementChange: (SignaturePlacement) -> Unit = {},
    val onPlacementDone: () -> Unit = {},
) {
    val drawsStrokes: Boolean get() = tool == PdfTool.Pen || tool == PdfTool.Highlight
}

/**
 * Everything over a page image: annotations, selection and their gestures.
 * Sized by the page card, so all of it holds at every zoom and in every view
 * mode. [actions] is `null` where a page is for looking only, as in the grid.
 */
@Composable
internal fun PdfPageOverlay(
    page: Int,
    annotations: List<PdfAnnotation>,
    selection: PdfSelection?,
    actions: PdfPageActions?,
) {
    Box(Modifier.fillMaxSize()) {
        if (annotations.isNotEmpty() || selection != null) {
            Canvas(Modifier.fillMaxSize()) {
                annotations.forEach { drawAnnotation(it) }
                selection?.boxes?.forEach { box ->
                    drawRect(
                        SelectionColor,
                        Offset(box.left * size.width, box.top * size.height),
                        Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    )
                }
            }
        }
        if (actions == null) return@Box
        val latest by rememberUpdatedState(actions)
        val current by rememberUpdatedState(page)
        var draft by remember { mutableStateOf<List<PdfPoint>>(emptyList()) }
        val drawing = actions.drawsStrokes
        // The reading gesture consumes nothing until a long press fires, so scroll and pinch are unaffected.
        Box(
            Modifier.fillMaxSize().pointerInput(drawing) {
                fun fraction(position: Offset) = PdfPoint(
                    (position.x / size.width).coerceIn(0f, 1f),
                    (position.y / size.height).coerceIn(0f, 1f),
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (drawing) {
                        down.consume()
                        val finished = trackStroke(down, fraction(down.position), { draft = it }, ::fraction)
                        draft = emptyList()
                        if (finished != null) latest.onStroke(current, finished)
                    } else {
                        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForTapOrMove(down, viewConfiguration.touchSlop)
                        }
                        when (outcome) {
                            PressOutcome.Tap -> latest.onTap(current, fraction(down.position), size.width.toFloat() / size.height)
                            PressOutcome.Moved -> Unit
                            null -> if (latest.canSelectText && latest.tool == PdfTool.Hand) {
                                latest.onLongPress(current, fraction(down.position))
                                // Still down: dragging extends, instead of scrolling.
                                trackHeldDrag(down) { latest.onExtendSelection(current, fraction(it)) }
                            }
                        }
                    }
                }
            },
        )
        if (draft.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                drawInk(draft.let(::listOf), actions.inkColor, actions.inkWidth, actions.tool == PdfTool.Highlight)
            }
        }
        selection?.takeIf { it.page == page && actions.canSelectText }?.let { active ->
            SelectionHandles(active) { handle, to -> latest.onHandleDrag(handle, to) }
        }
        // On top, so it takes this page's touches for as long as the signature can still move.
        actions.placement?.takeIf { it.page == page }?.let { placement ->
            SignaturePlacementLayer(placement, { latest.onPlacementChange(it) }, { latest.onPlacementDone() })
        }
    }
}

private enum class PressOutcome { Tap, Moved }

private suspend fun AwaitPointerEventScope.waitForTapOrMove(down: PointerInputChange, slop: Float): PressOutcome {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) return PressOutcome.Moved
        val change = event.changes.firstOrNull { it.id == down.id } ?: return PressOutcome.Moved
        if (!change.pressed) return if (change.isConsumed) PressOutcome.Moved else PressOutcome.Tap
        if ((change.position - down.position).getDistance() > slop) return PressOutcome.Moved
    }
}

/** The finished stroke, or `null` if a second finger turned this into a pinch. */
internal suspend fun AwaitPointerEventScope.trackStroke(
    down: PointerInputChange,
    first: PdfPoint,
    onDraft: (List<PdfPoint>) -> Unit,
    fraction: (Offset) -> PdfPoint,
): List<PdfPoint>? {
    val points = arrayListOf(first)
    onDraft(points.toList())
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) return null
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return points
        change.consume()
        points += fraction(change.position)
        onDraft(points.toList())
    }
}

private suspend fun AwaitPointerEventScope.trackHeldDrag(down: PointerInputChange, onMove: (Offset) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) return
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed) return
        if (change.positionChange() != Offset.Zero) {
            change.consume()
            onMove(change.position)
        }
    }
}

@Composable
private fun SelectionHandles(selection: PdfSelection, onDrag: (PdfSelectionHandle, PdfPoint) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val halfTarget = with(density) { (HANDLE_TARGET / 2).toPx() }
        val first = selection.boxes.first()
        val last = selection.boxes.last()
        SelectionHandle("Selection start", first.left * widthPx, first.bottom * heightPx, halfTarget, widthPx, heightPx) {
            onDrag(PdfSelectionHandle.Start, it)
        }
        SelectionHandle("Selection end", last.right * widthPx, last.bottom * heightPx, halfTarget, widthPx, heightPx) {
            onDrag(PdfSelectionHandle.End, it)
        }
    }
}

private val HANDLE_TARGET = 44.dp

/** A drag target centred on the end of the selection. It consumes its touches, so nothing scrolls beneath it. */
@Composable
private fun SelectionHandle(
    label: String,
    x: Float,
    y: Float,
    halfTarget: Float,
    pageWidth: Float,
    pageHeight: Float,
    onDrag: (PdfPoint) -> Unit,
) {
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)
    val latestWidth by rememberUpdatedState(pageWidth)
    val latestHeight by rememberUpdatedState(pageHeight)
    val latestDrag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .offset { IntOffset((x - halfTarget).roundToInt(), (y - halfTarget).roundToInt()) }
            .size(HANDLE_TARGET)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var at = Offset(latestX, latestY)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || event.changes.count { it.pressed } >= 2) break
                        val moved = change.positionChange()
                        if (moved != Offset.Zero) {
                            change.consume()
                            at += moved
                            latestDrag(PdfPoint((at.x / latestWidth).coerceIn(0f, 1f), (at.y / latestHeight).coerceIn(0f, 1f)))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(EzColors.Primary).border(2.dp, EzColors.OnPrimary, CircleShape))
    }
}

private val SelectionColor = Color(0x5500A0FF)

private fun DrawScope.drawAnnotation(annotation: PdfAnnotation) {
    when (annotation) {
        is PdfAnnotation.Highlight -> annotation.boxes.forEach { box ->
            drawRect(
                Color(annotation.color),
                Offset(box.left * size.width, box.top * size.height),
                Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                blendMode = BlendMode.Multiply,
            )
        }
        is PdfAnnotation.Ink -> drawInk(annotation.strokes, annotation.color, annotation.width, annotation.style == PdfInkStyle.Marker)
        is PdfAnnotation.Note -> drawNote(annotation)
    }
}

internal fun DrawScope.drawInk(strokes: List<List<PdfPoint>>, color: Int, width: Float, multiply: Boolean) {
    val strokeWidth = (width * size.width).coerceAtLeast(0.5f)
    val mode = if (multiply) BlendMode.Multiply else BlendMode.SrcOver
    for (stroke in strokes) {
        val first = stroke.firstOrNull() ?: continue
        if (stroke.size == 1) {
            drawCircle(Color(color), strokeWidth / 2, Offset(first.x * size.width, first.y * size.height), blendMode = mode)
            continue
        }
        val path = Path().apply {
            moveTo(first.x * size.width, first.y * size.height)
            for (index in 1..stroke.lastIndex) lineTo(stroke[index].x * size.width, stroke[index].y * size.height)
        }
        drawPath(path, Color(color), style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = mode)
    }
}

/** Matches the appearance the engine writes into the file. */
private fun DrawScope.drawNote(note: PdfAnnotation.Note) {
    val side = NOTE_ICON_FRACTION * size.width
    val halfV = side / 2 / size.height
    val centerX = note.x.coerceIn(NOTE_ICON_FRACTION / 2, 1f - NOTE_ICON_FRACTION / 2) * size.width
    val centerY = note.y.coerceIn(halfV, 1f - halfV) * size.height
    val topLeft = Offset(centerX - side / 2, centerY - side / 2)
    val outline = Color(0xFF40331A)
    val corner = CornerRadius(side / 10)
    drawRoundRect(Color(note.color).copy(alpha = 1f), topLeft, Size(side, side), corner)
    drawRoundRect(outline, topLeft, Size(side, side), corner, style = Stroke(side / 16))
    for (row in listOf(-0.225f, 0f, 0.225f)) {
        val y = centerY + row * side
        drawLine(outline, Offset(centerX - 0.3f * side, y), Offset(centerX + 0.3f * side, y), side / 16)
    }
}

/** Under the page, in place of the reading toolbar: tools scroll, while Save and Done stay in reach on a narrow phone. */
@Composable
internal fun AnnotationToolbar(
    tool: PdfTool,
    onTool: (PdfTool) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onDone: () -> Unit,
    hint: String?,
    colors: List<Int>,
    color: Int,
    onColor: (Int) -> Unit,
    widths: List<Float>,
    width: Float,
    onWidth: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .glassBar()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (colors.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                colors.forEachIndexed { index, option -> ColorSwatch(option, option == color, "Colour ${index + 1}") { onColor(option) } }
                Box(Modifier.size(10.dp))
                widths.forEachIndexed { index, option -> WidthDot(index, option == width, listOf("Thin", "Medium", "Thick")[index]) { onWidth(option) } }
            }
        }
        hint?.let {
            Text(it, fontSize = 12.sp, color = EzColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.weight(1f).glass(EzShapes.Pill, 0.6f).clip(EzShapes.Pill).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(AnnotationIcons.Hand, "Move and select", tool == PdfTool.Hand) { onTool(PdfTool.Hand) }
                ToolButton(AnnotationIcons.Highlight, "Highlight", tool == PdfTool.Highlight) { onTool(PdfTool.Highlight) }
                ToolButton(AnnotationIcons.Annotate, "Pen", tool == PdfTool.Pen) { onTool(PdfTool.Pen) }
                ToolButton(AnnotationIcons.Note, "Add a note", tool == PdfTool.Note) { onTool(PdfTool.Note) }
                ToolButton(AnnotationIcons.Signature, "Signature", tool == PdfTool.Signature) { onTool(PdfTool.Signature) }
                BarIconButton(AnnotationIcons.Undo, "Undo", onUndo, enabled = canUndo)
                BarIconButton(AnnotationIcons.Redo, "Redo", onRedo, enabled = canRedo)
            }
            Row(Modifier.glass(EzShapes.Pill, 0.6f), verticalAlignment = Alignment.CenterVertically) {
                // Labelled: a disk icon alone doesn't say that this saves a copy of the PDF.
                EzTextButton("Save", onSave, enabled = canSave && !saving)
                BarIconButton(AnnotationIcons.Done, "Finish annotating", onDone)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) EzColors.Selected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = rememberHapticChoice(selected, onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) EzColors.Primary else EzColors.Text, modifier = Modifier.size(EzSpacing.BarIcon))
    }
}

@Composable
internal fun ColorSwatch(color: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = rememberHapticChoice(selected, onClick))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(color).copy(alpha = 1f))
                .border(if (selected) 3.dp else 1.dp, if (selected) EzColors.Primary else EzColors.PageOutline, CircleShape),
        )
    }
}

@Composable
internal fun WidthDot(index: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) EzColors.Selected else Color.Transparent)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = rememberHapticChoice(selected, onClick))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((5 + index * 5).dp).clip(CircleShape).background(EzColors.Text))
    }
}

/** Above the page while text is selected. */
@Composable
internal fun SelectionBar(text: String, canHighlight: Boolean, onCopy: () -> Unit, onHighlight: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .glassBar()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.replace('\n', ' '), fontSize = 14.sp, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        BarIconButton(AnnotationIcons.Copy, "Copy", onCopy)
        if (canHighlight) BarIconButton(AnnotationIcons.Highlight, "Highlight", onHighlight)
        BarIconButton(EzIcons.Close, "Clear selection", onClose)
    }
}

@Composable
internal fun NoteEditorDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NOTE_CHARS) },
                label = { Text("Note") },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            EzTextButton("Add", { onAdd(text.trim()) }, enabled = text.isNotBlank())
        },
        dismissButton = { EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary) },
    )
}

@Composable
internal fun NoteViewerDialog(note: PdfAnnotation.Note, onDismiss: () -> Unit) {
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        scrollBody = false,
        text = {
            Text(
                note.text.ifBlank { "This note is empty." },
                color = EzColors.Text,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { EzTextButton("Close", onDismiss) },
    )
}

@Composable
internal fun SaveDialog(warning: String?, canOverwrite: Boolean, onSaveCopy: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save annotations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                warning?.let { Text(it, fontWeight = FontWeight.Medium, color = EzColors.Accent) }
                Text("Your annotations are added as a new copy, and the original stays as it is.", color = EzColors.Text)
            }
        },
        confirmButton = { EzTextButton("Save a copy", onSaveCopy) },
        dismissButton = {
            Row {
                if (canOverwrite) EzTextButton("Update original", onOverwrite, color = EzColors.TextSecondary)
                EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, color = EzColors.Text) },
        confirmButton = { EzTextButton(confirm, onConfirm) },
        dismissButton = { EzTextButton(dismiss, onDismiss, color = EzColors.TextSecondary) },
    )
}

@Composable
internal fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, color = EzColors.Text) },
        confirmButton = { EzTextButton("OK", onDismiss) },
    )
}
