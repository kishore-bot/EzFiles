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
    private val onResult: (SpeechResult) -> Unit,
) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    actual val state: StateFlow<SpeechState> = _state

    private var recognizer: SpeechRecognizer? = null

    /**
     * The system's default recognizer may stream audio to a server. EzFiles is offline, so the
     * on-device recognizer is used wherever the phone has one, and the default one is told to
     * stay offline otherwise.
     */
    private val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    actual fun start() {
        cancel()
        val local = onDevice
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SpeechState.Error
            return
        }
        val sr = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SpeechState.Listening }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
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

    actual fun stop() {
        recognizer?.stopListening()
    }

    actual fun cancel() {
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
actual fun rememberSpeechRecognition(hints: () -> List<String>, onResult: (SpeechResult) -> Unit): SpeechRecognition {
    val context = LocalContext.current
    val latestHints by rememberUpdatedState(hints)
    val sr = remember(context) { SpeechRecognition(context, { latestHints() }, onResult) }
    DisposableEffect(sr) { onDispose { sr.destroy() } }
    return sr
}
