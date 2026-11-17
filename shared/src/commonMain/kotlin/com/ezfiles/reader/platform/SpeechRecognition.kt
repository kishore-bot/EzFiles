package com.ezfiles.reader.platform

import com.ezfiles.reader.data.KeyValueStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechState { Idle, Listening, Error }

expect class SpeechRecognition {
    val state: StateFlow<SpeechState>
    /** How loud the user is speaking, 0..1, while listening; drives the orb. */
    val level: StateFlow<Float>
    fun start()
    fun stop()
    fun cancel()
}

/** Which on-device recognizer listens. Both stay on the phone. */
enum class SpeechEngine(val label: String, internal val stored: String) {
    /** The phone's own on-device recognizer, biased towards file names and commands. */
    System("Android", "system"),
    /** Moonshine Base (English) through sherpa-onnx; needs its model files on the phone. */
    Moonshine("Moonshine", "moonshine"),
    ;

    companion object {
        fun fromStored(value: String?): SpeechEngine = entries.firstOrNull { it.stored == value } ?: System
    }
}

/** The speech engine setting, persisted like the theme. */
internal class SpeechSettings(private val storage: KeyValueStorage) {
    private val state = MutableStateFlow(SpeechEngine.fromStored(storage.get(KEY)))
    val engine: StateFlow<SpeechEngine> = state.asStateFlow()

    fun set(value: SpeechEngine) {
        if (value == state.value) return
        state.value = value
        storage.put(KEY, value.stored)
    }

    private companion object {
        const val KEY = "speech_engine_v1"
    }
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
    engine: SpeechEngine = SpeechEngine.System,
    onResult: (SpeechResult) -> Unit,
): SpeechRecognition
