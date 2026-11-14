package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** iOS is parked: media reports that it can't play. */
private object UnavailablePlayer : MediaPlayerState {
    override val status: MediaStatus = MediaStatus.Failed("Playback isn't available on this device yet.")
    override val playing = false
    override val durationMs = 0L
    override val positionMs = 0L
    override val videoAspect = 0f

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit
}

@Composable
actual fun rememberMediaPlayer(source: MediaSource): MediaPlayerState = remember { UnavailablePlayer }

@Composable
actual fun VideoSurface(player: MediaPlayerState, modifier: Modifier) = Unit

@Composable
actual fun HiddenSystemBars() = Unit

@Composable
actual fun rememberSiblingFinder(): suspend (ref: com.ezfiles.reader.domain.DocumentRef, path: String) -> com.ezfiles.reader.domain.DocumentRef? =
    remember { { _, _ -> null } }

@Composable
actual fun rememberMediaFilePicker(onResult: (com.ezfiles.reader.domain.DocumentRef?) -> Unit): DocumentPicker =
    remember { DocumentPicker { onResult(null) } }

@Composable
actual fun rememberSubtitlePicker(onResult: (com.ezfiles.reader.domain.DocumentRef?) -> Unit): DocumentPicker =
    remember { DocumentPicker { onResult(null) } }

@Composable
actual fun rememberSubtitleLoader(): suspend (com.ezfiles.reader.domain.DocumentRef) -> SubtitleDocument =
    remember { { error("Playback isn't available on this device yet.") } }

@Composable
actual fun rememberMediaDeviceControls(): MediaDeviceControls = remember {
    object : MediaDeviceControls {
        override val volume = 0f
        override val brightness = 0.5f
        override val volumeAvailable = false
        override val brightnessAvailable = false
        override val problem: String? = null
        override fun refresh() = Unit
        override fun setVolume(value: Float) = Unit
        override fun setBrightness(value: Float) = Unit
        override fun dismissProblem() = Unit
    }
}

@Composable
actual fun rememberPageFolderAccess(page: com.ezfiles.reader.domain.DocumentRef): PageFolderAccess = remember {
    object : PageFolderAccess {
        override val available = false
        override val version = 0
        override val problem: String? = null

        override fun choose() = Unit

        override fun dismissProblem() = Unit
    }
}
