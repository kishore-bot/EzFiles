package com.ezfiles.reader.platform

import kotlinx.coroutines.flow.StateFlow

enum class TtsState { Idle, Speaking, Error }

expect class TextToSpeech {
    val state: StateFlow<TtsState>
    fun speak(text: String)
    fun stop()
}

@androidx.compose.runtime.Composable
expect fun rememberTextToSpeech(): TextToSpeech
