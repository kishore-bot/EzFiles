package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class SpeechResult actual constructor(actual val text: String, actual val isFinal: Boolean)

actual class SpeechRecognition(private val onResult: (SpeechResult) -> Unit) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    actual val state: StateFlow<SpeechState> = _state
    actual fun start() { _state.value = SpeechState.Error }
    actual fun stop() {}
    actual fun cancel() { _state.value = SpeechState.Idle }
}

@Composable
actual fun rememberSpeechRecognition(onResult: (SpeechResult) -> Unit): SpeechRecognition {
    return remember { SpeechRecognition(onResult) }
}
