package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.theme.rememberEzHaptics

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.Canvas
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.platform.DrawnSignature
import com.ezfiles.reader.platform.LocalSavedSignatures
import com.ezfiles.reader.platform.MAX_SAVED_SIGNATURES
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val SIGNATURE_PAD_ASPECT = 3f
private val SignatureColors = listOf(0xFF111111, 0xFF1A3FB0).map { it.toInt() }
private val SignatureColorNames = listOf("Black", "Blue")

// Fractions of the pad's width.
private val SignaturePens = listOf(0.006f, 0.009f, 0.014f)
private val SignaturePenNames = listOf("Thin", "Medium", "Thick")
private val PLACEMENT_HANDLE = 44.dp

private const val DRAWN_NOTICE =
    "This adds a drawing of your signature to the page. It is not a digital signature: it doesn't certify who signed or protect the file from changes."
private const val STORAGE_NOTICE = "Kept only on this device, never uploaded or backed up."

/** Shows the chooser while [placer] asks for it. The same dialog serves the reader and the Signature tool. */
@Composable
internal fun SignatureChooser(placer: SignaturePlacer, onDismiss: () -> Unit = {}) {
    if (!placer.picking) return
    SignatureDialog(
        onDismiss = {
            placer.cancelPicking()
            onDismiss()
        },
        onChosen = placer::choose,
    )
}

/** Saved signatures to pick from or delete, or the pad when there are none yet. Says once what a drawn signature is. */
@Composable
private fun SignatureDialog(onDismiss: () -> Unit, onChosen: (DrawnSignature) -> Unit) {
    val haptics = rememberEzHaptics()
    val store = LocalSavedSignatures.current
    val saved by store.signatures.collectAsState()
    var drawing by remember { mutableStateOf(saved.isEmpty()) }
    var deleting by remember { mutableStateOf<DrawnSignature?>(null) }
    var strokes by remember { mutableStateOf<List<List<PdfPoint>>>(emptyList()) }
    var color by remember { mutableIntStateOf(SignatureColors.first()) }
    var pen by remember { mutableFloatStateOf(SignaturePens[1]) }
    var keep by remember { mutableStateOf(true) }
    val roomToKeep = saved.size < MAX_SAVED_SIGNATURES

    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (drawing) "Draw your signature" else "Choose a signature") },
        scrollBody = false,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (drawing) {
                    SignaturePad(strokes, color, pen) { strokes = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignatureColors.forEachIndexed { index, option ->
                            ColorSwatch(option, option == color, SignatureColorNames[index]) { color = option }
                        }
                        Box(Modifier.size(6.dp))
                        SignaturePens.forEachIndexed { index, option ->
                            WidthDot(index, option == pen, SignaturePenNames[index]) { pen = option }
                        }
                        Box(Modifier.weight(1f))
                        BarIconButton(UndoIcon, "Undo last stroke", { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty())
                    }
                    if (roomToKeep) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { keep = !keep; haptics.toggle(keep) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(keep, null, colors = CheckboxDefaults.colors(checkedColor = EzColors.Primary), modifier = Modifier.padding(8.dp))
                            Column {
                                Text("Keep for next time", fontSize = 14.sp, color = EzColors.Text)
                                Text(STORAGE_NOTICE, fontSize = 12.sp, color = EzColors.TextSecondary)
                            }
                        }
                    } else {
                        Text(
                            "$MAX_SAVED_SIGNATURES signatures are kept already, so this one is for now only. Delete one to keep another.",
                            fontSize = 12.sp,
                            color = EzColors.TextSecondary,
                        )
                    }
                } else {
                    saved.forEach { signature ->
                        SavedSignatureRow(signature, onUse = { onChosen(signature) }, onDelete = { deleting = signature })
                    }
                    Text(STORAGE_NOTICE, fontSize = 12.sp, color = EzColors.TextSecondary)
                }
                Text(DRAWN_NOTICE, fontSize = 12.sp, color = EzColors.TextSecondary)
            }
        },
        confirmButton = {
            if (drawing) {
                TextButton(
                    onClick = {
                        drawnSignature(strokes, SIGNATURE_PAD_ASPECT, color, pen)?.let { drawn ->
                            onChosen(if (keep && roomToKeep) store.save(drawn) ?: drawn else drawn)
                        }
                    },
                    enabled = strokes.isNotEmpty(),
                ) { Text("Use", color = EzColors.Primary) }
            } else {
                EzTextButton("Draw a new one", { drawing = true })
            }
        },
        dismissButton = {
            Row {
                if (drawing) {
                    EzTextButton("Clear", { strokes = emptyList() }, enabled = strokes.isNotEmpty(), color = EzColors.TextSecondary)
                }
                EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary)
            }
        },
    )
    deleting?.let { signature ->
        ConfirmDialog(
            title = "Delete this signature?",
            message = "It will be removed from this device. PDFs you've already saved keep theirs.",
            confirm = "Delete",
            dismiss = "Keep",
            onConfirm = {
                store.delete(signature.id)
                deleting = null
                if (store.signatures.value.isEmpty()) drawing = true
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SignaturePad(strokes: List<List<PdfPoint>>, color: Int, pen: Float, onStrokes: (List<List<PdfPoint>>) -> Unit) {
    val latestStrokes by rememberUpdatedState(strokes)
    val latestOnStrokes by rememberUpdatedState(onStrokes)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(SIGNATURE_PAD_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .background(EzPaper.Background)
            .border(1.dp, EzColors.PageOutline, RoundedCornerShape(12.dp))
            .clipToBounds()
            .semantics { contentDescription = "Signature pad" }
            .pointerInput(Unit) {
                fun fraction(position: Offset) = PdfPoint(
                    (position.x / size.width).coerceIn(0f, 1f),
                    (position.y / size.height).coerceIn(0f, 1f),
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val before = latestStrokes
                    if (before.size < MAX_SIGNATURE_STROKES) {
                        trackStroke(down, fraction(down.position), { latestOnStrokes(before + listOf(it)) }, ::fraction)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawInk(strokes, color, pen, multiply = false) }
    }
}

@Composable
private fun SavedSignatureRow(signature: DrawnSignature, onUse: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EzPaper.Background)
            .border(1.dp, EzColors.PageOutline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Use this signature", onClick = rememberHapticActivation(onUse))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(52.dp).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
            Canvas(
                Modifier
                    .aspectRatio(signature.aspect, matchHeightConstraintsFirst = true)
                    .semantics { contentDescription = "Saved signature" },
            ) {
                drawInk(signature.strokes, signature.color, signature.penWidth, multiply = false)
            }
        }
        BarIconButton(EzIcons.Close, "Delete this signature", onDelete)
    }
}

/**
 * Over a page while a signature is still movable. One finger on it moves it,
 * two fingers anywhere on the page or the corner handle resize it, and a tap
 * elsewhere finishes. Other touches are left alone, so the page still scrolls.
 */
@Composable
internal fun SignaturePlacementLayer(placement: SignaturePlacement, onChange: (SignaturePlacement) -> Unit, onDone: () -> Unit) {
    val latest by rememberUpdatedState(placement)
    val latestChange by rememberUpdatedState(onChange)
    val latestDone by rememberUpdatedState(onDone)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val halfHandle = with(density) { (PLACEMENT_HANDLE / 2).toPx() }
        val latestWidth by rememberUpdatedState(widthPx)
        val latestHeight by rememberUpdatedState(heightPx)
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Signature on the page. Drag to move it." }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val onSignature = latest.contains(PdfPoint(down.position.x / latestWidth, down.position.y / latestHeight))
                        if (onSignature) down.consume()
                        var travelled = false
                        var pinched = false
                        // Kept here, as events can arrive faster than the screen recomposes.
                        var working = latest
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) {
                                pinched = true
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    working = working.resizedTo(working.widthFraction * zoom)
                                    latestChange(working)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (onSignature && !pinched) {
                                val moved = change.positionChange()
                                if (moved != Offset.Zero) {
                                    change.consume()
                                    working = working.movedBy(moved.x / latestWidth, moved.y / latestHeight)
                                    latestChange(working)
                                }
                            } else if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                travelled = true
                            }
                        }
                        if (!onSignature && !travelled && !pinched) latestDone()
                    }
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            val dash = 4.dp.toPx()
            drawRect(
                EzColors.Primary,
                Offset(placement.left * size.width, placement.top * size.height),
                Size(placement.widthFraction * size.width, placement.heightFraction * size.height),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
            )
        }
        // Kept on the page even when the signature's corner sits right at its edge.
        val handleX = min((placement.left + placement.widthFraction) * widthPx, widthPx - halfHandle)
        val handleY = min((placement.top + placement.heightFraction) * heightPx, heightPx - halfHandle)
        Box(
            Modifier
                .offset { IntOffset(max(handleX - halfHandle, 0f).roundToInt(), max(handleY - halfHandle, 0f).roundToInt()) }
                .size(PLACEMENT_HANDLE)
                .semantics { contentDescription = "Resize signature" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var working = latest
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val moved = change.positionChange()
                            if (moved != Offset.Zero) {
                                change.consume()
                                working = working.handleDraggedBy(moved.x / latestWidth, moved.y / latestHeight)
                                latestChange(working)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(EzColors.Primary).border(2.dp, EzColors.OnPrimary, CircleShape))
        }
    }
}
