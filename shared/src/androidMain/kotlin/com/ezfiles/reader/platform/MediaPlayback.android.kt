package com.ezfiles.reader.platform

import android.content.Context
import android.app.KeyguardManager
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Media3 ExoPlayer. Video uses the phone's hardware decoders; audio the phone can't decode itself
 * (E-AC3/Dolby Digital Plus, AC3, DTS, TrueHD…) goes through FFmpeg's LGPL decoders (:ffmpeg).
 * Embedded media is written to a private cache file first, because the player needs a seekable
 * source, and deleted on release. Audio focus is ExoPlayer's.
 */
@OptIn(UnstableApi::class)
private class AndroidMediaPlayer(context: Context, private val lifecycle: Lifecycle) : MediaPlayerState {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            // The phone's own decoders first; FFmpeg only for what they can't play.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true),
    ).setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        /* handleAudioFocus = */ true,
    ).setHandleAudioBecomingNoisy(true).build()
    var file: File? = null
    var released = false
    private var startedOnce = false

    override var status: MediaStatus by mutableStateOf(MediaStatus.Preparing)
    override var playing by mutableStateOf(false)
    override var durationMs by mutableLongStateOf(0L)
    override var positionMs by mutableLongStateOf(0L)
    override var videoAspect by mutableFloatStateOf(0f)
    override var audioTracks: List<MediaTrack> by mutableStateOf(emptyList())
    override var subtitleTracks: List<MediaTrack> by mutableStateOf(emptyList())
    override var selectedAudioTrack: String? by mutableStateOf(null)
    override var selectedSubtitleTrack: String? by mutableStateOf(null)
    override var externalSubtitles: SubtitleDocument? by mutableStateOf(null)
    override var subtitleText by mutableStateOf("")
    override var trackProblem: String? by mutableStateOf(null)

    /** Track id ("audio:2:0") → the group and index ExoPlayer selects by. */
    private val trackGroups = HashMap<String, Pair<Tracks.Group, Int>>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                when (state) {
                    Player.STATE_READY -> {
                        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                        status = MediaStatus.Ready
                        if (!startedOnce) {
                            startedOnce = true
                            play()
                        }
                    }
                    Player.STATE_ENDED -> {
                        playbackEnded()
                        positionMs = durationMs
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Focus loss, unplugged headphones and the end of the file stop it from outside.
                if (!isPlaying && !player.playWhenReady) playing = false
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)

            override fun onCues(cueGroup: CueGroup) {
                if (selectedSubtitleTrack == null || selectedSubtitleTrack == "external") return
                subtitleText = plainSubtitleText(cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n"))
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackEnded()
                status = MediaStatus.Failed(
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                            "This phone can't decode this video's picture format."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                            "This file's format isn't supported or it is damaged."
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                            "This file can't be read any more. Open it again."
                        else -> "This file can't be played on this device."
                    },
                )
            }
        })
    }

    override fun play() {
        // Preparation may finish after the device locks or the app leaves the foreground.
        if (released || status != MediaStatus.Ready || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            keyguard?.isKeyguardLocked == true || power?.isInteractive == false) return
        if (playing) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
        playing = true
    }

    override fun pause() {
        if (released) return
        player.pause()
        updatePosition()
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        if (status != MediaStatus.Ready) return
        val target = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        player.seekTo(target)
        this.positionMs = target
        subtitleText = ""
    }

    override fun selectAudioTrack(id: String) = trackChange {
        val (group, index) = trackGroups[id] ?: return@trackChange
        if (!group.isTrackSupported(index)) return@trackChange
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        selectedAudioTrack = id
    }

    override fun selectSubtitleTrack(id: String?) = trackChange {
        if (id == "external" && externalSubtitles == null) return@trackChange
        subtitleText = ""
        val builder = player.trackSelectionParameters.buildUpon()
        val embedded = id?.takeUnless { it == "external" }?.let { trackGroups[it] ?: return@trackChange }
        if (embedded == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(embedded.first.mediaTrackGroup, embedded.second))
        }
        player.trackSelectionParameters = builder.build()
        selectedSubtitleTrack = id
    }

    override fun useExternalSubtitles(subtitles: SubtitleDocument) {
        externalSubtitles = subtitles
        selectSubtitleTrack("external")
    }

    override fun dismissTrackProblem() { trackProblem = null }

    private inline fun trackChange(change: () -> Unit) {
        if (released || status != MediaStatus.Ready) return
        try { change() } catch (_: RuntimeException) {
            trackProblem = "This track cannot be selected on this device. Choose another track or a local caption file."
        }
    }

    private fun refreshTracks(tracks: Tracks) {
        if (released) return
        trackGroups.clear()
        val audio = ArrayList<MediaTrack>()
        val text = ArrayList<MediaTrack>()
        var selectedAudio: String? = null
        var selectedText: String? = null
        tracks.groups.forEachIndexed { g, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val id = "audio:$g:$i"
                        trackGroups[id] = group to i
                        val supported = group.isTrackSupported(i)
                        audio += MediaTrack(id, format.language.orEmpty(), audioLabel(format, audio.size) + if (supported) "" else " (can't play here)", supported)
                        if (group.isTrackSelected(i)) selectedAudio = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val id = "text:$g:$i"
                        trackGroups[id] = group to i
                        val supported = group.isTrackSupported(i)
                        val name = format.label ?: language(format.language) ?: "Subtitles ${text.size + 1}"
                        text += MediaTrack(id, format.language.orEmpty(), name + if (supported) "" else " (unsupported format)", supported)
                        if (group.isTrackSelected(i)) selectedText = id
                    }
                }
            }
        }
        audioTracks = audio
        subtitleTracks = text
        selectedAudioTrack = selectedAudio
        if (selectedSubtitleTrack != "external") selectedSubtitleTrack = selectedText
        // Every audio track unplayable: say so plainly rather than playing in silence.
        trackProblem = if (audio.isNotEmpty() && audio.none { it.supported }) {
            "This video's sound (${audio.first().label.substringBefore(" (")}) can't be played on this phone."
        } else trackProblem
    }

    private fun language(code: String?): String? = code?.takeUnless { it.isBlank() || it == "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).takeIf(String::isNotBlank) }

    /** "English · E-AC3 5.1", as a player lists them. */
    private fun audioLabel(format: Format, index: Int): String {
        val name = format.label ?: language(format.language) ?: "Audio ${index + 1}"
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            else -> null
        }
        val channels = when (format.channelCount) { 6 -> "5.1"; 8 -> "7.1"; 2 -> "stereo"; 1 -> "mono"; else -> null }
        return listOfNotNull(name, listOfNotNull(codec, channels).joinToString(" ").takeIf(String::isNotBlank)).joinToString(" · ")
    }

    fun updatePosition() {
        if (released) return
        positionMs = player.currentPosition.coerceAtLeast(0)
    }

    fun playbackEnded() {
        playing = false
        subtitleText = ""
    }

    fun release() {
        released = true
        player.release()
        file?.delete()
    }
}

@Composable
actual fun rememberMediaPlayer(source: MediaSource): MediaPlayerState {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(source, lifecycle) { AndroidMediaPlayer(context, lifecycle) }
    DisposableEffect(state) { onDispose { state.release() } }

    LaunchedEffect(state) {
        try {
            prepare(context, state, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!state.released) state.status = MediaStatus.Failed(e.message ?: "This file can't be played.")
        }
    }
    // Position for the seek bar and external subtitles, while playing.
    LaunchedEffect(state, state.playing) {
        while (state.playing && !state.released) {
            state.updatePosition()
            delay(POSITION_POLL_MS)
        }
    }
    // Leaving the app pauses playback.
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) state.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

private suspend fun prepare(context: Context, state: AndroidMediaPlayer, source: MediaSource) {
    val uri = when (source) {
        is MediaSource.File -> Uri.parse(source.ref.uri)
        is MediaSource.Embedded -> {
            val file = withContext(Dispatchers.IO) {
                val bytes = source.load() ?: throw IOException("This media file can't be read from the document.")
                val extension = source.name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(5)
                val dir = File(context.cacheDir, "media").apply { mkdirs() }
                File.createTempFile("embedded", ".$extension", dir).apply { writeBytes(bytes) }
            }
            if (state.released) {
                file.delete()
                return
            }
            state.file = file
            Uri.fromFile(file)
        }
    }
    state.player.setMediaItem(MediaItem.fromUri(uri))
    state.player.prepare()
}

@OptIn(UnstableApi::class)
@Composable
actual fun VideoSurface(player: MediaPlayerState, modifier: Modifier) {
    val android = player as? AndroidMediaPlayer ?: return
    // A TextureView, so the picture can be cropped, stretched and pinch-zoomed like any other view.
    AndroidView(
        factory = { context -> TextureView(context).also { if (!android.released) android.player.setVideoTextureView(it) } },
        modifier = modifier,
        update = { it.keepScreenOn = android.playing },
        onRelease = { if (!android.released) android.player.clearVideoTextureView(it) },
    )
}

private const val POSITION_POLL_MS = 200L

@Composable
actual fun rememberSiblingFinder(): suspend (ref: DocumentRef, path: String) -> DocumentRef? {
    val context = LocalContext.current.applicationContext
    return remember(context) { { ref, path -> withContext(Dispatchers.IO) { findSibling(context, ref, path) } } }
}

/**
 * Only a document reached through a folder tree the user granted (the library)
 * has a folder the app may list; a single picked file doesn't. The provider
 * names the document's ancestors, and the path is walked from its parent,
 * never above the granted folder. Providers are other apps' code, so their
 * runtime failures count as "not found".
 */
private fun findSibling(context: Context, ref: DocumentRef, path: String): DocumentRef? = try {
    val uri = Uri.parse(ref.uri)
    val tree = if (DocumentsContract.isDocumentUri(context, uri) && uri.pathSegments.firstOrNull() == "tree") {
        DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
    } else {
        null
    }
    val ancestors = tree?.let { DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path }
    if (tree == null || ancestors == null || ancestors.size < 2) {
        null
    } else {
        // From the tree's root down to the document's folder.
        val folders = ArrayDeque(ancestors.dropLast(1))
        var found: Pair<String, String>? = null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        for ((i, segment) in segments.withIndex()) {
            if (segment == "..") {
                if (folders.size <= 1) return null
                folders.removeLast()
                continue
            }
            val child = childNamed(context, tree, folders.last(), segment) ?: return null
            if (i == segments.lastIndex) found = child else folders.addLast(child.first)
        }
        found?.let { (id, name) ->
            DocumentRef(uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), displayName = name)
        }
    }
} catch (_: SecurityException) {
    null
} catch (_: RuntimeException) {
    null
}

/** (document id, name) of the child of [parentId] named [name]; an exact match wins over one in another case. */
private fun childNamed(context: Context, tree: Uri, parentId: String, name: String): Pair<String, String>? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    var loose: Pair<String, String>? = null
    context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val shown = cursor.getString(1) ?: continue
            if (shown == name) return id to shown
            if (loose == null && shown.equals(name, ignoreCase = true)) loose = id to shown
        }
    }
    return loose
}

@Composable
actual fun rememberMediaFilePicker(onResult: (DocumentRef?) -> Unit): DocumentPicker {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onResult(uri?.let { documentRef(context, it) })
    }
    return remember(launcher) { DocumentPicker { launcher.launch(arrayOf("audio/*", "video/*")) } }
}
