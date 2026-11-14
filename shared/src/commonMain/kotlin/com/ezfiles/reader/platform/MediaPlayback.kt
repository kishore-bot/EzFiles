package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ezfiles.reader.domain.DocumentRef

/** What a player plays: a file the user opened, or audio or video embedded in a document. */
sealed interface MediaSource {
    data class File(val ref: DocumentRef) : MediaSource

    /** [name] is the path inside the document; [load] reads its bytes. */
    data class Embedded(val name: String, val load: suspend () -> ByteArray?) : MediaSource
}

sealed interface MediaStatus {
    data object Preparing : MediaStatus

    data object Ready : MediaStatus

    data class Failed(val message: String) : MediaStatus
}

/** Platform track identifiers are meaningful only for the currently open player. */
data class MediaTrack(val id: String, val language: String, val label: String, val supported: Boolean = true)

/**
 * A platform player. Its properties are snapshot state, so composables reading
 * them follow playback. Call everything from the main thread.
 */
interface MediaPlayerState {
    val status: MediaStatus
    val playing: Boolean
    val durationMs: Long
    val positionMs: Long

    /** Width over height of the picture; 0 for audio or until known. */
    val videoAspect: Float

    val audioTracks: List<MediaTrack> get() = emptyList()
    val subtitleTracks: List<MediaTrack> get() = emptyList()
    val selectedAudioTrack: String? get() = null
    /** Null means Off; "external" selects [externalSubtitles]. */
    val selectedSubtitleTrack: String? get() = null
    val externalSubtitles: SubtitleDocument? get() = null
    val subtitleText: String get() = ""
    val trackProblem: String? get() = null

    fun selectAudioTrack(id: String) = Unit
    fun selectSubtitleTrack(id: String?) = Unit
    fun useExternalSubtitles(subtitles: SubtitleDocument) = Unit
    fun dismissTrackProblem() = Unit

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)
}

/** A player for [source], released when this leaves the composition. */
@Composable
expect fun rememberMediaPlayer(source: MediaSource): MediaPlayerState

/** Where [player] draws its picture. */
@Composable
expect fun VideoSurface(player: MediaPlayerState, modifier: Modifier = Modifier)

/** Hides the status and navigation bars while composed; a swipe shows them for a moment. */
@Composable
expect fun HiddenSystemBars()

/**
 * Finds the file at a relative `path` from the folder holding a document, when
 * the app can see that folder (a library folder); `null` otherwise, or if
 * there is no such file. The path can't leave the folder the app was given.
 */
@Composable
expect fun rememberSiblingFinder(): suspend (ref: DocumentRef, path: String) -> DocumentRef?

/** The system picker, filtered to audio and video. [onResult] gets `null` if dismissed. */
@Composable
expect fun rememberMediaFilePicker(onResult: (DocumentRef?) -> Unit): DocumentPicker

/** User-selected local captions; no automatic sibling discovery or network subtitle lookup. */
@Composable
expect fun rememberSubtitlePicker(onResult: (DocumentRef?) -> Unit): DocumentPicker

/** At most 1 MiB of UTF-8 or BOM-marked UTF-16 SRT/WebVTT. */
@Composable
expect fun rememberSubtitleLoader(): suspend (DocumentRef) -> SubtitleDocument

interface MediaDeviceControls {
    val volume: Float
    val brightness: Float
    val volumeAvailable: Boolean
    val brightnessAvailable: Boolean
    val problem: String?
    fun refresh()
    fun setVolume(value: Float)
    fun setBrightness(value: Float)
    fun dismissProblem()
}

/** Brightness is scoped to the current player window and restored on leaving it. */
@Composable
expect fun rememberMediaDeviceControls(): MediaDeviceControls
