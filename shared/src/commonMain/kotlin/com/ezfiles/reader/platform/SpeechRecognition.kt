package com.ezfiles.reader.platform

import kotlinx.coroutines.flow.StateFlow

enum class SpeechState { Idle, Listening, Error }

expect class SpeechRecognition {
    val state: StateFlow<SpeechState>
    fun start()
    fun stop()
    fun cancel()
}

expect class SpeechResult(text: String, isFinal: Boolean) {
    val text: String
    val isFinal: Boolean
}

@androidx.compose.runtime.Composable
expect fun rememberSpeechRecognition(onResult: (SpeechResult) -> Unit): SpeechRecognition
