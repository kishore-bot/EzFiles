package com.ezfiles.reader.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class SpeechResult actual constructor(actual val text: String, actual val isFinal: Boolean)

actual class SpeechRecognition(
    private val context: Context,
    private val onResult: (SpeechResult) -> Unit,
) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    actual val state: StateFlow<SpeechState> = _state

    private var recognizer: SpeechRecognizer? = null

    actual fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SpeechState.Error
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SpeechState.Listening }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                _state.value = SpeechState.Error
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) onResult(SpeechResult(text, true))
                _state.value = SpeechState.Idle
            }
            override fun onPartialResults(partial: Bundle?) {
                val matches = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) onResult(SpeechResult(text, false))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
}

@Composable
actual fun rememberSpeechRecognition(onResult: (SpeechResult) -> Unit): SpeechRecognition {
    val context = LocalContext.current
    val sr = remember(context) { SpeechRecognition(context, onResult) }
    DisposableEffect(sr) { onDispose { sr.destroy() } }
    return sr
}
