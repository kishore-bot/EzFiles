package com.ezfiles.reader.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaFormat
import android.media.MediaPlayer
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * `android.media.MediaPlayer`: built in, so playback adds nothing to the app.
 * Embedded media is written to a private cache file first, because the player
 * needs a seekable source, and deleted on release.
 */
private class AndroidMediaPlayer(context: Context, private val lifecycle: Lifecycle) : MediaPlayerState {
    val player = MediaPlayer()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener { change ->
            if (change < 0 && !released) pause()
        }.build()
    var file: File? = null
    var released = false

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
    private var subtitleStart = 0L
    private var subtitleEnd = Long.MAX_VALUE

    override fun play() {
        // Preparation may finish after the device locks or the app leaves the foreground.
        if (released || status != MediaStatus.Ready || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            keyguard?.isKeyguardLocked == true || power?.isInteractive == false) return
        if (playing) return
        if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            trackProblem = "Another app is using audio. Try Play again when it finishes."
            return
        }
        if (positionMs >= durationMs && durationMs > 0) seekTo(0)
        try {
            player.start()
            playing = true
        } catch (_: IllegalStateException) {
            audio?.abandonAudioFocusRequest(focus)
            trackProblem = "Playback could not start. Open the file again."
        }
    }

    override fun pause() {
        if (released || status != MediaStatus.Ready) return
        try {
            if (player.isPlaying) player.pause()
            updatePosition()
        } catch (_: RuntimeException) {
            trackProblem = "Playback is no longer available. Open the file again."
        } finally {
            playing = false
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (status != MediaStatus.Ready) return
        val target = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        try {
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            this.positionMs = target
            subtitleText = ""
        } catch (_: RuntimeException) {
            trackProblem = "This file cannot seek to that position on this device."
        }
    }

    override fun selectAudioTrack(id: String) = trackChange {
        val track = audioTracks.firstOrNull { it.id == id && it.supported } ?: return@trackChange
        player.selectTrack(track.id.toInt())
        selectedAudioTrack = track.id
    }

    override fun selectSubtitleTrack(id: String?) = trackChange {
        val track = id?.takeUnless { it == "external" }?.let { candidate ->
            subtitleTracks.firstOrNull { it.id == candidate && it.supported } ?: return@trackChange
        }
        if (id == "external" && externalSubtitles == null) return@trackChange
        val previous = selectedSubtitleTrack?.toIntOrNull()
        selectedSubtitleTrack = null
        subtitleText = ""
        previous?.let(player::deselectTrack)
        if (track != null) player.selectTrack(track.id.toInt())
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

    fun refreshTracks() {
        if (released || status != MediaStatus.Ready) return
        try {
            val tracks = player.trackInfo
            fun label(language: String, fallback: String): String = language.takeUnless { it.isBlank() || it == "und" }
                ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).takeIf(String::isNotBlank) } ?: fallback
            audioTracks = tracks.mapIndexedNotNull { index, info ->
                if (info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) null
                else MediaTrack(index.toString(), info.language, label(info.language, "Audio ${index + 1}"))
            }
            subtitleTracks = tracks.mapIndexedNotNull { index, info ->
                if (info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT && info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) null
                else {
                    val mime = info.format?.getString(MediaFormat.KEY_MIME).orEmpty()
                    val supported = info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ||
                        (Build.VERSION.SDK_INT >= 28 && mime == "text/vtt")
                    MediaTrack(index.toString(), info.language, label(info.language, "Subtitles ${index + 1}") + if (supported) "" else " (unsupported format)", supported)
                }
            }
            selectedAudioTrack = player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO).takeIf { it >= 0 }?.toString()
        } catch (_: RuntimeException) {
            trackProblem = "Track information is unavailable on this device."
        }
    }

    fun showSubtitle(text: String, startMs: Long = 0, endMs: Long = Long.MAX_VALUE) {
        if (released || selectedSubtitleTrack == null || selectedSubtitleTrack == "external") return
        subtitleText = plainSubtitleText(text)
        subtitleStart = startMs
        subtitleEnd = endMs
    }

    fun updatePosition() {
        try {
            positionMs = player.currentPosition.toLong()
            if (positionMs < subtitleStart || positionMs >= subtitleEnd) subtitleText = ""
        } catch (_: RuntimeException) {
            playing = false
            audio?.abandonAudioFocusRequest(focus)
            trackProblem = "Playback is no longer available. Open the file again."
        }
    }

    fun playbackEnded() {
        playing = false
        subtitleText = ""
        audio?.abandonAudioFocusRequest(focus)
    }

    fun release() {
        released = true
        audio?.abandonAudioFocusRequest(focus)
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
    // The player has no position callback; poll it while playing.
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
    val player = state.player
    player.setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
    )
    when (source) {
        is MediaSource.File -> player.setDataSource(context, Uri.parse(source.ref.uri))
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
            player.setDataSource(file.path)
        }
    }
    player.setOnPreparedListener {
        if (state.released) return@setOnPreparedListener
        state.durationMs = it.duration.toLong().coerceAtLeast(0)
        if (it.videoWidth > 0 && it.videoHeight > 0) state.videoAspect = it.videoWidth.toFloat() / it.videoHeight
        state.status = MediaStatus.Ready
        state.refreshTracks()
        state.play()
    }
    player.setOnTimedTextListener { _, text -> state.showSubtitle(text?.text.orEmpty()) }
    if (Build.VERSION.SDK_INT >= 28) {
        player.setOnSubtitleDataListener { _, data ->
            if (state.selectedSubtitleTrack == data.trackIndex.toString()) {
                state.showSubtitle(embeddedWebVtt(data.data), data.startTimeUs / 1_000,
                    (data.startTimeUs + data.durationUs) / 1_000)
            }
        }
    }
    player.setOnInfoListener { _, what, _ ->
        if (what == MediaPlayer.MEDIA_INFO_METADATA_UPDATE) state.refreshTracks()
        false
    }
    player.setOnVideoSizeChangedListener { _, width, height ->
        if (width > 0 && height > 0) state.videoAspect = width.toFloat() / height
    }
    player.setOnCompletionListener {
        state.playbackEnded()
        state.positionMs = state.durationMs
    }
    player.setOnErrorListener { _, _, _ ->
        state.playbackEnded()
        state.status = MediaStatus.Failed("This file can't be played on this device.")
        true
    }
    player.prepareAsync()
}

@Composable
actual fun VideoSurface(player: MediaPlayerState, modifier: Modifier) {
    val android = player as? AndroidMediaPlayer ?: return
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (!android.released) android.player.setDisplay(holder)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (!android.released) android.player.setDisplay(null)
                        }
                    },
                )
            }
        },
        modifier = modifier,
        update = { it.keepScreenOn = android.playing },
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
