package com.ezfiles.reader.platform

import kotlinx.coroutines.flow.StateFlow

enum class SpeechState { Idle, Listening, Error }

expect class SpeechRecognition {
    val state: StateFlow<SpeechState>
    /** How loud the user is speaking, 0..1, while listening; drives the orb. */
    val level: StateFlow<Float>
    fun start()
    fun stop()
    fun cancel()
}

/** [alternatives] are the recognizer's guesses, best first; [text] is the first. */
class SpeechResult(val text: String, val isFinal: Boolean, val alternatives: List<String> = listOf(text))

/**
 * Recognition on the device only. [hints] (file names, command phrases) bias it towards words it
 * would otherwise mishear; they are given only to an on-device recognizer.
 */
@androidx.compose.runtime.Composable
expect fun rememberSpeechRecognition(hints: () -> List<String>, onResult: (SpeechResult) -> Unit): SpeechRecognition
