package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class SpeechRecognition(private val onResult: (SpeechResult) -> Unit) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    actual val state: StateFlow<SpeechState> = _state
    actual val level: StateFlow<Float> = MutableStateFlow(0f)
    actual fun start() { _state.value = SpeechState.Error }
    actual fun stop() {}
    actual fun cancel() { _state.value = SpeechState.Idle }
}

@Composable
actual fun rememberSpeechRecognition(hints: () -> List<String>, engine: SpeechEngine, onResult: (SpeechResult) -> Unit): SpeechRecognition {
    return remember { SpeechRecognition(onResult) }
}
