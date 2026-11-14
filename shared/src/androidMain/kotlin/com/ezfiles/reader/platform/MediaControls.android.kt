package com.ezfiles.reader.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

@Composable
actual fun rememberSubtitlePicker(onResult: (DocumentRef?) -> Unit): DocumentPicker {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        currentResult(uri?.let { documentRef(context, it) })
    }
    // Providers disagree on .srt/.vtt MIME types; the bounded parser validates the chosen file.
    return remember(launcher) { DocumentPicker { launcher.launch(arrayOf("*/*")) } }
}

@Composable
actual fun rememberSubtitleLoader(): suspend (DocumentRef) -> SubtitleDocument {
    val context = LocalContext.current.applicationContext
    return remember(context) { { ref ->
        withContext(Dispatchers.IO) {
            val uri = android.net.Uri.parse(ref.uri)
            require(uri.scheme == "content") { "Choose a local subtitle file." }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "Choose a subtitle file smaller than 1 MiB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("This subtitle file cannot be read.")
            val encoding = when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val offset = if (encoding == Charsets.UTF_8) 0 else 2
            val text = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            parseSubtitles(ref.displayName, text)
        }
    } }
}

private class AndroidMediaControls(private val context: Context, private val window: Window?) : MediaDeviceControls {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maximum = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
    private val originalBrightness = window?.attributes?.screenBrightness ?: -1f
    private var changedBrightness = false
    private var volumeState by mutableFloatStateOf(0f)
    private var brightnessState by mutableFloatStateOf(currentBrightness())
    override val volume get() = volumeState
    override val brightness get() = brightnessState
    override val volumeAvailable get() = audio != null && !audio.isVolumeFixed
    override val brightnessAvailable get() = window != null
    override var problem: String? by mutableStateOf(null)

    init { refresh() }

    override fun refresh() {
        volumeState = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maximum
        if (!changedBrightness) brightnessState = currentBrightness()
    }

    override fun setVolume(value: Float) {
        if (!volumeAvailable || !value.isFinite()) return
        val index = (value.coerceIn(0f, 1f) * maximum).roundToInt()
        if ((volume * maximum).roundToInt() == index) return
        try {
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            volumeState = index.toFloat() / maximum
        } catch (_: SecurityException) { problem = "Media volume is managed by this device. Use its volume buttons." }
    }

    override fun setBrightness(value: Float) {
        if (window == null || !value.isFinite()) return
        val next = (value.coerceIn(0.05f, 1f) * 100).roundToInt() / 100f
        if (changedBrightness && next == brightness) return
        brightnessState = next
        changedBrightness = true
        applyBrightness()
    }

    fun applyBrightness() { if (changedBrightness) window?.let { it.attributes = it.attributes.apply { screenBrightness = brightness } } }
    fun restore() { window?.let { it.attributes = it.attributes.apply { screenBrightness = originalBrightness } } }
    override fun dismissProblem() { problem = null }

    private fun currentBrightness(): Float = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0.05f, 1f)
}

@Composable
actual fun rememberMediaDeviceControls(): MediaDeviceControls {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context.mediaActivity()
    val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
    val state = remember(context, window) { AndroidMediaControls(context, window) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val previousStream = activity?.volumeControlStream
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) state.restore()
            if (event == Lifecycle.Event.ON_RESUME) { state.refresh(); state.applyBrightness() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.restore()
            previousStream?.let { activity?.volumeControlStream = it }
        }
    }
    return state
}

private fun Context.mediaActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.mediaActivity()
    else -> null
}
