package com.ezfiles.reader.presentation.viewers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ezfiles.reader.application.assistant.ReaderPosition
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.platform.HiddenSystemBars
import com.ezfiles.reader.platform.LockOrientation
import com.ezfiles.reader.platform.MediaPlayerState
import com.ezfiles.reader.platform.MediaSource
import com.ezfiles.reader.platform.MediaStatus
import com.ezfiles.reader.platform.VideoSurface
import com.ezfiles.reader.platform.formatDate
import com.ezfiles.reader.platform.rememberMediaPlayer
import com.ezfiles.reader.platform.MediaDeviceControls
import com.ezfiles.reader.platform.MediaDrag
import com.ezfiles.reader.platform.draggedMediaLevel
import com.ezfiles.reader.platform.draggedMediaPosition
import com.ezfiles.reader.platform.rememberMediaDeviceControls
import com.ezfiles.reader.platform.rememberEnterPip
import com.ezfiles.reader.platform.rememberSubtitleLoader
import com.ezfiles.reader.platform.rememberSubtitlePicker
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CONTROLS_HIDE_MS = 3000L
private val MEDIA_VOICE_OPERATIONS = setOf("play", "pause", "seek")

private enum class VideoScale(val label: String) {
    Fit("Fit"),
    Fill("Fill"),
    Stretch("Stretch"),
    ;
    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]
}

/**
 * A full player on black: the picture fitted to the screen, or artwork for
 * audio. While a video plays, its controls fade after a few seconds; a tap
 * brings them back.
 */
@Composable
fun MediaPlayerView(source: MediaSource, kind: MediaKind, title: String, modifier: Modifier = Modifier, ref: DocumentRef? = null, onClose: (() -> Unit)? = null) {
    val player = rememberMediaPlayer(source)
    var controls by remember { mutableStateOf(true) }
    val video = kind == MediaKind.Video
    val device = rememberMediaDeviceControls()
    val haptics = rememberEzHaptics()
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<String?>(null) }
    var interaction by remember { mutableStateOf(0) }
    var interacting by remember { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    var subtitleProblem by remember { mutableStateOf<String?>(null) }
    // Only the full-screen player: media embedded in a document leaves the document in control.
    if (onClose != null) {
        ReaderControlTarget(MEDIA_VOICE_OPERATIONS, ReaderPosition()) { operation, value ->
            when {
                player.status != MediaStatus.Ready -> "not_ready"
                operation == "play" -> null.also { player.play() }
                operation == "pause" -> null.also { player.pause() }
                else -> spokenNumber(value)?.let { seconds -> null.also { player.seekTo(seconds * 1000L) } } ?: "missing_time"
            }
        }
    }
    var subtitleLoading by remember { mutableStateOf(false) }
    var orientationLocked by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var videoScale by remember { mutableStateOf(VideoScale.Fit) }
    val enterPip = rememberEnterPip()
    LockOrientation(orientationLocked)
    val loadSubtitles = rememberSubtitleLoader()
    val subtitlePicker = rememberSubtitlePicker { ref ->
        if (ref != null) scope.launch {
            subtitleLoading = true
            try {
                player.useExternalSubtitles(loadSubtitles(ref))
                settings = null
                haptics.confirm()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                subtitleProblem = if (failure is IllegalArgumentException) failure.message
                    else "This caption file cannot be read. Choose a local UTF-8 or UTF-16 SRT/WebVTT file."
                haptics.reject()
            } finally { subtitleLoading = false }
        }
    }
    LaunchedEffect(controls, player.playing, interaction, interacting, settings) {
        if (video && controls && player.playing && !interacting && settings == null) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    val touched: () -> Unit = { controls = true; interaction++ }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                onClick("Show or hide playback controls") { controls = !controls; interaction++; true }
                customActions = listOf(
                    CustomAccessibilityAction("Back 10 seconds") { player.seekTo(player.positionMs - SKIP_MS); touched(); true },
                    CustomAccessibilityAction("Forward 10 seconds") { player.seekTo(player.positionMs + SKIP_MS); touched(); true },
                )
            }
            .pointerInput(player, video, haptics, onClose) {
                val centerZone = 0.3f
                detectTapGestures(
                    onTap = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val inCenter = abs(offset.x - cx) < cx * centerZone && abs(offset.y - cy) < cy * centerZone
                        if (inCenter && onClose != null) {
                            onClose()
                        } else {
                            controls = !controls; interaction++
                        }
                    },
                    onDoubleTap = { offset ->
                        if (video && player.status == MediaStatus.Ready) {
                            val delta = if (offset.x < size.width / 2f) -SKIP_MS else SKIP_MS
                            player.seekTo(player.positionMs + delta)
                            haptics.select()
                            touched()
                        }
                    },
                )
            }
            .pointerInput(player, device, video, haptics) {
                var origin = Offset.Zero
                var movement = Offset.Zero
                var axis: MediaDrag? = null
                var startPosition = 0L
                var targetPosition = 0L
                var startLevel = 0f
                detectDragGestures(
                    onDragStart = { point ->
                        origin = point
                        movement = Offset.Zero
                        axis = null
                        startPosition = player.positionMs
                        targetPosition = startPosition
                        device.refresh()
                    },
                    onDrag = { change, amount ->
                        if (video && player.status == MediaStatus.Ready) {
                            change.consume()
                            movement += amount
                            if (axis == null) {
                                axis = if (abs(movement.x) >= abs(movement.y)) MediaDrag.Seek
                                    else if (origin.x < size.width / 2f) MediaDrag.Brightness else MediaDrag.Volume
                                startLevel = if (axis == MediaDrag.Brightness) device.brightness else device.volume
                                interacting = true
                                touched()
                                haptics.threshold()
                            }
                            when (axis) {
                                MediaDrag.Seek -> {
                                    targetPosition = draggedMediaPosition(startPosition, movement.x, size.width.toFloat(), player.durationMs)
                                    gestureMessage = "Seek ${clock(targetPosition)} / ${clock(player.durationMs)}"
                                }
                                MediaDrag.Brightness -> {
                                    if (device.brightnessAvailable) device.setBrightness(draggedMediaLevel(startLevel, movement.y, size.height.toFloat()))
                                    gestureMessage = if (device.brightnessAvailable) "Brightness ${(device.brightness * 100).roundToInt()}%" else "Brightness is managed by this device"
                                }
                                MediaDrag.Volume -> {
                                    if (device.volumeAvailable) device.setVolume(draggedMediaLevel(startLevel, movement.y, size.height.toFloat()))
                                    gestureMessage = if (device.volumeAvailable) "Volume ${(device.volume * 100).roundToInt()}%" else "Use the device volume buttons"
                                }
                                null -> Unit
                            }
                        }
                    },
                    onDragEnd = {
                        if (axis == MediaDrag.Seek) player.seekTo(targetPosition)
                        if (axis != null) haptics.select()
                        interacting = false
                        gestureMessage = null
                        interaction++
                    },
                    onDragCancel = { interacting = false; gestureMessage = null },
                )
            },
    ) {
        if (video) {
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                val aspect = player.videoAspect.takeIf { it > 0f } ?: (16f / 9f)
                val surfaceModifier = when (videoScale) {
                    VideoScale.Fit -> {
                        val wider = maxWidth / maxHeight > aspect
                        Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = wider)
                    }
                    VideoScale.Fill -> {
                        val narrower = maxWidth / maxHeight < aspect
                        Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = !narrower)
                    }
                    VideoScale.Stretch -> Modifier.fillMaxSize()
                }
                VideoSurface(player, surfaceModifier)
            }
        } else {
            AudioArtwork(title, Modifier.align(Alignment.Center))
        }
        if (video) SubtitleOverlay(player, controls, Modifier.align(Alignment.BottomCenter))
        gestureMessage?.let { message ->
            Text(message, color = Color.White, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center).clip(EzShapes.Card).background(Color.Black.copy(alpha = 0.72f)).padding(16.dp))
        }
        when (val status = player.status) {
            MediaStatus.Preparing -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            is MediaStatus.Failed -> Text(
                status.message,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            MediaStatus.Ready -> {}
        }
        AnimatedVisibility(controls || !video, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onClose != null) RoundButton(EzIcons.Close, "Close player", onClose)
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Box {
                        RoundButton(EzIcons.More, "More options", { menuOpen = true; touched() })
                        EzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            EzMenuItem("Audio", onClick = { menuOpen = false; settings = "audio"; touched() }, icon = EzIcons.Audio)
                            if (video) EzMenuItem("Subtitles", onClick = { menuOpen = false; settings = "subtitles"; touched() }, icon = EzIcons.Subtitles)
                            EzMenuItem("Playback", onClick = { menuOpen = false; settings = "settings"; touched() }, icon = EzIcons.Music)
                            if (video) EzMenuItem(
                                "Resize: ${videoScale.label}",
                                onClick = { videoScale = videoScale.next(); haptics.select(); menuOpen = false },
                                icon = EzIcons.AspectRatio,
                            )
                            if (video) EzMenuItem(
                                "Picture-in-picture",
                                onClick = {
                                    menuOpen = false
                                    val aspect = player.videoAspect.takeIf { it > 0f } ?: (16f / 9f)
                                    enterPip(aspect)
                                },
                                icon = EzIcons.PictureInPicture,
                            )
                            EzMenuItem(
                                if (orientationLocked) "Unlock rotation" else "Lock rotation",
                                onClick = { menuOpen = false; orientationLocked = !orientationLocked; haptics.select() },
                                icon = if (orientationLocked) EzIcons.Lock else EzIcons.ScreenLockRotation,
                            )
                            if (ref != null) EzMenuItem("File details", onClick = { menuOpen = false; detailsOpen = true }, icon = EzIcons.Document)
                        }
                    }
                }
                Transport(player, Modifier.align(Alignment.BottomCenter), onInteraction = touched, onSeeking = { interacting = it })
            }
        }
    }
    settings?.let { section ->
        PlayerSettings(player, device, section, subtitleLoading, onPickSubtitles = subtitlePicker::launch, onDismiss = { settings = null; touched() })
    }
    val problem = subtitleProblem ?: player.trackProblem ?: device.problem
    if (problem != null) EzDialog(
        onDismissRequest = { subtitleProblem = null; player.dismissTrackProblem(); device.dismissProblem() },
        title = { Text("Playback") }, text = { Text(problem) },
        confirmButton = { EzTextButton("OK", { subtitleProblem = null; player.dismissTrackProblem(); device.dismissProblem() }) },
    )
    if (detailsOpen && ref != null) MediaDetails(ref, onDismiss = { detailsOpen = false })
}

@Composable
private fun SubtitleOverlay(player: MediaPlayerState, controls: Boolean, modifier: Modifier) {
    val text = if (player.selectedSubtitleTrack == "external") player.externalSubtitles?.textAt(player.positionMs).orEmpty() else player.subtitleText
    if (text.isNotBlank()) Text(
        text, color = Color.White, fontSize = 20.sp, lineHeight = 27.sp, textAlign = TextAlign.Center,
        maxLines = 4, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 24.dp).padding(bottom = if (controls) 164.dp else 28.dp)
            .wrapContentSize().clip(EzShapes.Small).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettings(player: MediaPlayerState, device: MediaDeviceControls, section: String,
                           loading: Boolean, onPickSubtitles: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberEzHaptics()
    EzSheet(onDismissRequest = onDismiss, title = when (section) { "audio" -> "Audio language"; "subtitles" -> "Subtitles"; else -> "Playback settings" }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (section) {
                "audio" -> {
                    if (player.audioTracks.isEmpty()) Text("No selectable audio tracks are available.", color = EzColors.TextSecondary, modifier = Modifier.padding(24.dp))
                    player.audioTracks.take(64).forEach { track ->
                        EzMenuItem((if (player.selectedAudioTrack == track.id) "✓ " else "") + track.label,
                            onClick = { player.selectAudioTrack(track.id); onDismiss() }, enabled = track.supported && player.status == MediaStatus.Ready)
                    }
                }
                "subtitles" -> {
                    EzMenuItem((if (player.selectedSubtitleTrack == null) "✓ " else "") + "Off", onClick = { player.selectSubtitleTrack(null); onDismiss() })
                    player.subtitleTracks.take(64).forEach { track ->
                        EzMenuItem((if (player.selectedSubtitleTrack == track.id) "✓ " else "") + track.label,
                            onClick = { player.selectSubtitleTrack(track.id); onDismiss() }, enabled = track.supported && player.status == MediaStatus.Ready)
                    }
                    player.externalSubtitles?.let { external ->
                        EzMenuItem((if (player.selectedSubtitleTrack == "external") "✓ " else "") + external.name,
                            onClick = { player.selectSubtitleTrack("external"); onDismiss() })
                    }
                    EzMenuItem(if (loading) "Loading captions…" else "Choose local SRT or WebVTT", onClick = onPickSubtitles,
                        enabled = !loading && player.status == MediaStatus.Ready)
                }
                else -> {
                    Text("Volume ${(device.volume * 100).roundToInt()}%", modifier = Modifier.padding(horizontal = 24.dp))
                    Slider(value = device.volume, onValueChange = device::setVolume, onValueChangeFinished = { haptics.select() },
                        enabled = device.volumeAvailable, modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = "Media volume" })
                    Text("Brightness ${(device.brightness * 100).roundToInt()}%", modifier = Modifier.padding(horizontal = 24.dp))
                    Slider(value = device.brightness, onValueChange = device::setBrightness, onValueChangeFinished = { haptics.select() },
                        enabled = device.brightnessAvailable, modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = "Player brightness" })
                    Text("Swipe left/right to seek. Swipe up/down on the left for brightness or on the right for media volume.", color = EzColors.TextSecondary, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDetails(ref: DocumentRef, onDismiss: () -> Unit) {
    EzSheet(onDismissRequest = onDismiss, title = "File details") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailRow("Name", ref.displayName)
            ref.displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { DetailRow("Extension", ".$it") }
            ref.sizeBytes?.let { DetailRow("Size", formatFileSize(it)) }
            ref.modifiedAt?.let { DetailRow("Modified", formatDate(it)) }
            DetailRow("Location", ref.uri.substringBefore(':'))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, color = EzColors.TextSecondary, fontSize = 12.sp)
        Text(value, color = EzColors.Text, fontSize = 15.sp)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

/** Opens [source] over everything, with the system bars hidden. */
@Composable
fun MediaDialog(source: MediaSource, kind: MediaKind, title: String, ref: DocumentRef? = null, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        HiddenSystemBars()
        MediaPlayerView(source, kind, title, ref = ref, onClose = onDismiss)
    }
}

@Composable
private fun Transport(player: MediaPlayerState, modifier: Modifier = Modifier, onInteraction: () -> Unit, onSeeking: (Boolean) -> Unit) {
    // While dragging, the thumb follows the finger; the seek happens on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val haptics = rememberEzHaptics()
    val sliderInteractions = remember { MutableInteractionSource() }
    LaunchedEffect(sliderInteractions) {
        sliderInteractions.interactions.collect { event ->
            if (event is DragInteraction.Cancel) {
                dragging = null
                onSeeking(false)
            }
        }
    }
    val duration = player.durationMs.coerceAtLeast(1)
    val fraction = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Slider(
            value = fraction,
            onValueChange = { if (dragging == null) { onInteraction(); onSeeking(true) }; dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * duration).toLong()) }
                haptics.select()
                dragging = null
                onSeeking(false)
                onInteraction()
            },
            enabled = player.status == MediaStatus.Ready,
            interactionSource = sliderInteractions,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = EzColors.Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(EzIcons.Rewind, "Back 10 seconds", { player.seekTo(player.positionMs - SKIP_MS); onInteraction() }, enabled = player.status == MediaStatus.Ready)
            RoundButton(
                if (player.playing) EzIcons.Pause else EzIcons.Play,
                if (player.playing) "Pause" else "Play",
                { if (player.playing) player.pause() else player.play(); onInteraction() },
                large = true,
                enabled = player.status == MediaStatus.Ready,
            )
            RoundButton(EzIcons.Forward, "Forward 10 seconds", { player.seekTo(player.positionMs + SKIP_MS); onInteraction() }, enabled = player.status == MediaStatus.Ready)
            Spacer(Modifier.weight(1f))
            val shown = dragging?.let { (it * duration).toLong() } ?: player.positionMs
            Text("${clock(shown)} / ${clock(player.durationMs)}", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, large: Boolean = false, enabled: Boolean = true) {
    val haptics = rememberEzHaptics()
    Box(
        Modifier
            .size(if (large) 56.dp else 48.dp)
            .clip(CircleShape)
            .background(if (large) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { haptics.select(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (large) Color.Black else Color.White, modifier = Modifier.size(if (large) 30.dp else 22.dp))
    }
}

@Composable
private fun AudioArtwork(title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(EzColors.Primary, EzColors.Primary.copy(alpha = 0.65f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EzIcons.Music, contentDescription = null, tint = Color.White, modifier = Modifier.size(84.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(280.dp))
    }
}

private const val SKIP_MS = 10_000L

/** 65,000 ms → "1:05"; an hour or more → "1:01:05". */
internal fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
