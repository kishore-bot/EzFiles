package com.ezfiles.reader.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * [NeedsLanguage]: the phone's offline recognizer has no English installed yet; [SpeechRecognition.downloadLanguage]
 * asks the phone to download it.
 */
enum class SpeechState { Idle, Listening, Error, NeedsLanguage }

expect class SpeechRecognition {
    val state: StateFlow<SpeechState>
    /** How loud the user is speaking, 0..1, while listening; drives the orb. */
    val level: StateFlow<Float>
    fun start()
    fun stop()
    fun cancel()

    /** Asks the phone to download its offline English speech pack (the system shows its own prompt). */
    fun downloadLanguage()
}

/** [alternatives] are the recognizer's guesses, best first; [text] is the first. */
class SpeechResult(val text: String, val isFinal: Boolean, val alternatives: List<String> = listOf(text))

/**
 * Recognition on the device only. [hints] (file names, command phrases) bias it towards words it
 * would otherwise mishear; they are given only to an on-device recognizer.
 */
@androidx.compose.runtime.Composable
expect fun rememberSpeechRecognition(
    hints: () -> List<String>,
    onResult: (SpeechResult) -> Unit,
): SpeechRecognition
