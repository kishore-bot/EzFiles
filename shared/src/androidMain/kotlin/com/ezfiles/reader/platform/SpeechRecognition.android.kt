package com.ezfiles.reader.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

actual class SpeechRecognition(
    private val context: Context,
    private val hints: () -> List<String>,
    private val engine: SpeechEngine,
    private val onResult: (SpeechResult) -> Unit,
) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    actual val state: StateFlow<SpeechState> = _state
    private val _level = MutableStateFlow(0f)
    actual val level: StateFlow<Float> = _level

    private var recognizer: SpeechRecognizer? = null

    /**
     * The system's default recognizer may stream audio to a server. EzFiles is offline, so the
     * on-device recognizer is used wherever the phone has one, and the default one is told to
     * stay offline otherwise.
     */
    private val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var moonshine: Thread? = null
    /** Each Moonshine session's number; cancel() and a new start() make older ones stale. */
    @Volatile private var moonshineSession = 0
    @Volatile private var moonshineFinish = false

    actual fun start() {
        cancel()
        if (engine == SpeechEngine.Moonshine) {
            if (MoonshineSpeech.available()) return startMoonshine()
            Log.w(TAG, "Moonshine files missing in ${MoonshineSpeech.MODEL_DIR}; using the Android recognizer")
        }
        val local = onDevice
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SpeechState.Error
            return
        }
        val sr = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SpeechState.Listening }
            override fun onBeginningOfSpeech() {}
            // Roughly -2 dB (silence) to 10 dB (speech) on phones.
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onError(error: Int) {
                Log.w(TAG, "Recognition error $error (on-device=$local)")
                _state.value = SpeechState.Error
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter(String::isNotBlank)
                Log.i(TAG, "Heard (on-device=$local): $matches")
                if (matches.isNotEmpty()) onResult(SpeechResult(matches.first(), true, matches))
                _state.value = SpeechState.Idle
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onResult(SpeechResult(text, false))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Several guesses: the assistant keeps the one that names a file or a command.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Room for a short pause mid-sentence ("open the... resume").
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            if (local && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints().take(MAX_HINTS)))
            }
        }
        sr.startListening(intent)
        _state.value = SpeechState.Listening
    }

    /** One utterance with Moonshine on a background thread; results come back on the main thread. */
    private fun startMoonshine() {
        val mine = ++moonshineSession
        val stale = { moonshineSession != mine }
        moonshineFinish = false
        // Listening from now on, so the overlay doesn't restart us while the model loads.
        _state.value = SpeechState.Listening
        val previous = moonshine
        moonshine = Thread({
            // The previous session must have released the microphone first.
            previous?.join(2_000)
            val text = try {
                MoonshineSpeech.listenOnce(
                    cancelled = stale,
                    finish = { moonshineFinish },
                    onListening = { main.post { if (!stale()) _state.value = SpeechState.Listening } },
                    onLevel = { _level.value = it },
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Moonshine failed: ${e.message}")
                ""
            }
            main.post {
                if (stale() || text == null) return@post
                Log.i(TAG, "Heard (moonshine): [$text]")
                if (text.isNotBlank()) {
                    onResult(SpeechResult(text, true, listOf(text)))
                    _state.value = SpeechState.Idle
                } else {
                    // Silence: the overlay listens again, as after the Android recognizer's timeout.
                    _state.value = SpeechState.Error
                }
            }
        }, "ez-moonshine").apply { isDaemon = true; start() }
    }

    actual fun stop() {
        recognizer?.stopListening()
        moonshineFinish = true
    }

    actual fun cancel() {
        moonshineSession++
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = SpeechState.Idle
    }

    fun destroy() { cancel() }

    private companion object {
        const val TAG = "EzSpeech"
        const val MAX_HINTS = 100
    }
}

@Composable
actual fun rememberSpeechRecognition(hints: () -> List<String>, engine: SpeechEngine, onResult: (SpeechResult) -> Unit): SpeechRecognition {
    val context = LocalContext.current
    val latestHints by rememberUpdatedState(hints)
    val sr = remember(context, engine) { SpeechRecognition(context, { latestHints() }, engine, onResult) }
    DisposableEffect(sr) { onDispose { sr.destroy() } }
    return sr
}
