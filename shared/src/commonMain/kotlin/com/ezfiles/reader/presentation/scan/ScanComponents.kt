package com.ezfiles.reader.presentation.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ezfiles.app.currentTimeMillis
import com.ezfiles.reader.data.RustImaging
import com.ezfiles.reader.platform.CameraCapturer
import com.ezfiles.reader.platform.CameraStatus
import com.ezfiles.reader.platform.CameraViewfinder
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import com.ezfiles.reader.platform.rememberCameraAccess
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.EzNotice
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.glassPanel
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.PrimaryGradient
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** The few extra icons the scanner needs, from Material Symbols path data (Apache 2.0). */
internal object ScanIcons {
    val Camera = icon(
        "M12,15.2A3.2,3.2 0 1,0 12,8.8A3.2,3.2 0 1,0 12,15.2zM9,2L7.17,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6" +
            "c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
    )
    val Delete = icon("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Crop = icon(
        "M17,15h2V7c0,-1.1 -0.9,-2 -2,-2H9v2h8v8zM7,17V1H5v4H1v2h4v10c0,1.1 0.9,2 2,2h10v4h2v-4h4v-2H7z",
    )
    val Copy = icon(
        "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5z" +
            "M19,21H8V7h11V21z",
    )
    val Check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}

/** The app's primary (red gradient) or secondary (glass) action, sized for a row of scanner controls. */
@Composable
internal fun ScanButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    if (primary) {
        PrimaryActionButton(text, icon, onClick, modifier, enabled)
    } else {
        SecondaryActionButton(text, icon, onClick, modifier, enabled)
    }
}

/** A dismissible note. */
@Composable
internal fun ScanMessage(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    EzNotice(text, onDismiss, modifier)
}

/** Covers the tool while work runs. */
@Composable
internal fun ScanProgress(text: String, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // Swallow touches so the controls behind can't be used.
            .clickable(interactionSource = null, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp).widthIn(max = 420.dp).glassPanel(EzShapes.Dialog).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = EzColors.Primary)
            Text(text, color = EzColors.Text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            EzTextButton("Cancel", onCancel)
        }
    }
}

/**
 * A picture from the workspace, decoded only while on screen. [version]
 * reloads it. A list of these keeps only its visible pictures in memory.
 */
@Composable
internal fun ScanPicture(
    workspace: ScanWorkspace,
    id: String,
    kind: ScanPictureKind,
    version: Int,
    maxSide: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val picture by loadPicture(workspace, id, kind, version, maxSide)
    Box(modifier, contentAlignment = Alignment.Center) {
        picture?.let {
            Image(it, contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun loadPicture(workspace: ScanWorkspace, id: String, kind: ScanPictureKind, version: Int, maxSide: Int) =
    produceState<ImageBitmap?>(null, workspace, id, kind, version, maxSide) {
        value = try {
            workspace.preview(id, kind, maxSide)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // No preview (low memory, say) isn't an error worth interrupting for: the page is still on disk.
            null
        }
    }

/**
 * The camera, or an explanation and the gallery when there is none. The
 * permission is asked for once when this first appears; the gallery path never
 * needs it.
 */
@Composable
internal fun CameraPanel(
    purpose: String,
    onCapture: (CameraCapturer) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    autoCapture: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    val access = rememberCameraAccess()
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access.status) {
        if (access.status == CameraStatus.NotAsked && !asked) {
            asked = true
            access.request()
        }
    }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var capturer by remember { mutableStateOf<CameraCapturer?>(null) }
    // Auto-capture: on by default where offered; the choice is remembered while scanning.
    var auto by rememberSaveable { mutableStateOf(true) }
    val autoOn = autoCapture && auto
    val steady = remember { SteadyPage() }
    val seen = remember { MutableStateFlow(AutoCaptureState.Searching) }
    val shots = remember { Channel<Unit>(Channel.CONFLATED) }
    val latestCapturer by rememberUpdatedState(capturer)
    val latestCapture by rememberUpdatedState(onCapture)
    LaunchedEffect(autoOn) {
        if (autoOn) for (shot in shots) latestCapturer?.let(latestCapture)
    }
    val autoState by seen.collectAsState()
    val haptics = rememberEzHaptics()
    LaunchedEffect(autoState) { if (autoState == AutoCaptureState.Holding) haptics.select() }
    val problem = failure
    when {
        problem != null -> NoCamera(problem, onImport, modifier, footer)
        access.status == CameraStatus.Granted -> Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CameraViewfinder(
                    Modifier.fillMaxSize().clip(EzShapes.Card).background(Color.Black),
                    onReady = { capturer = it },
                    onFailure = { failure = it },
                    onFrame = if (autoOn) {
                        { frame ->
                            // On the camera's analysis thread: find the page, check it's sharp, and wait for stillness.
                            val quad = detectPageOutline(frame.bytes, frame.width, frame.height)
                            val sharp = quad != null && !RustImaging.isBlurry(frame)
                            val state = steady.offer(quad, sharp, currentTimeMillis())
                            seen.value = state
                            if (state == AutoCaptureState.Take) shots.trySend(Unit)
                        }
                    } else {
                        null
                    },
                )
                if (autoCapture) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EzChip("Auto", auto, { auto = !auto })
                        if (autoOn) {
                            Text(
                                autoHint(autoState),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(EzShapes.Field)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ScanButton("Gallery", onImport, icon = EzIcons.Image)
                val ready = capturer
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (ready != null) PrimaryGradient else SolidColor(EzColors.TextSecondary))
                        .clickable(enabled = ready != null, role = Role.Button) { ready?.let { haptics.activate(); onCapture(it) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ScanIcons.Camera, "Take picture", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                // Balances the row so the shutter stays in the middle.
                Box(Modifier.widthIn(min = 96.dp), contentAlignment = Alignment.CenterEnd) { footer() }
            }
        }
        access.status == CameraStatus.NoCamera -> NoCamera("This device has no camera. You can still use a picture from your gallery.", onImport, modifier, footer)
        else -> Column(
            modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Icon(ScanIcons.Camera, null, tint = EzColors.Primary, modifier = Modifier.size(48.dp))
            Text(
                if (access.status == CameraStatus.NotAsked) "Waiting for camera permission." else "Xfiles doesn't have permission to use the camera.",
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                textAlign = TextAlign.Center,
            )
            Text(purpose, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
            if (access.status == CameraStatus.RefusedForGood) {
                ScanButton("Open settings", access::openSettings)
            } else {
                ScanButton("Allow camera", access::request)
            }
            ScanButton("Import from gallery", onImport, primary = true, icon = EzIcons.Image)
            footer()
        }
    }
}

@Composable
private fun NoCamera(text: String, onImport: () -> Unit, modifier: Modifier, footer: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(EzIcons.Image, null, tint = EzColors.Primary, modifier = Modifier.size(48.dp))
        Text(text, color = EzColors.Text, textAlign = TextAlign.Center)
        ScanButton("Import from gallery", onImport, primary = true, icon = EzIcons.Image)
        footer()
    }
}

/**
 * The picture with the page outline over it and a handle on each corner.
 * Dragging a handle reports a new [PageQuad]; moves that would fold the
 * outline are refused by [PageQuad.moveCorner].
 */
@Composable
internal fun CornerEditor(
    picture: ImageBitmap,
    quad: PageQuad,
    onChange: (PageQuad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestQuad by rememberUpdatedState(quad)
    val latestChange by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val handleRadius = with(density) { 10.dp.toPx() }
    val grabRadius = with(density) { 48.dp.toPx() }
    BoxWithConstraints(modifier) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        // Inset so a handle on the picture's edge can still be grabbed.
        val inset = handleRadius * 2
        val scale = minOf((boxWidth - inset * 2) / picture.width, (boxHeight - inset * 2) / picture.height).coerceAtLeast(0.01f)
        val shownWidth = picture.width * scale
        val shownHeight = picture.height * scale
        val left = (boxWidth - shownWidth) / 2
        val top = (boxHeight - shownHeight) / 2
        fun toScreen(p: ScanPoint) = Offset(left + p.x * shownWidth, top + p.y * shownHeight)
        Canvas(
            Modifier.fillMaxSize().pointerInput(left, top, shownWidth, shownHeight) {
                var dragged = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val distances = latestQuad.corners.map { (toScreen(it) - start).getDistance() }
                        val nearest = distances.indices.minBy { distances[it] }
                        dragged = if (distances[nearest] <= grabRadius) nearest else -1
                    },
                    onDragEnd = { dragged = -1 },
                    onDragCancel = { dragged = -1 },
                    onDrag = { change, amount ->
                        if (dragged >= 0) {
                            change.consume()
                            val current = latestQuad.corners[dragged]
                            val moved = ScanPoint(current.x + amount.x / shownWidth, current.y + amount.y / shownHeight)
                            latestChange(latestQuad.moveCorner(dragged, moved))
                        }
                    },
                )
            },
        ) {
            drawImage(
                picture,
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(shownWidth.toInt(), shownHeight.toInt()),
            )
            val corners = quad.corners.map(::toScreen)
            val outline = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1..3) lineTo(corners[i].x, corners[i].y)
                close()
            }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(left, top, left + shownWidth, top + shownHeight))
                addPath(outline)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(outline, EzColors.Primary, style = Stroke(width = 2.dp.toPx()))
            for (corner in corners) {
                drawCircle(Color.White, handleRadius + 2.dp.toPx(), corner)
                drawCircle(EzColors.Primary, handleRadius, corner)
            }
        }
    }
}

/** What auto-capture is waiting for, in a few words over the viewfinder. */
private fun autoHint(state: AutoCaptureState): String = when (state) {
    AutoCaptureState.Searching -> "Point at a page"
    AutoCaptureState.Moving -> "Hold still"
    AutoCaptureState.Holding -> "Holding…"
    AutoCaptureState.Take, AutoCaptureState.Taken -> "Taking the picture"
}
