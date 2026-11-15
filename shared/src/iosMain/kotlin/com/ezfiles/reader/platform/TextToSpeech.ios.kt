package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class TextToSpeech {
    private val _state = MutableStateFlow(TtsState.Idle)
    actual val state: StateFlow<TtsState> = _state
    actual fun speak(text: String) {}
    actual fun stop() {}
}

@Composable
actual fun rememberTextToSpeech(): TextToSpeech = remember { TextToSpeech() }
