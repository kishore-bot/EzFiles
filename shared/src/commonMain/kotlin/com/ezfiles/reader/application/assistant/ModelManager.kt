package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.platform.ModelInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class ModelState { Idle, Loading, Ready, Error }

class ModelManager {
    private val inference = ModelInference()
    private val _state = MutableStateFlow(ModelState.Idle)
    val state: StateFlow<ModelState> = _state

    private var systemPrompt: String = ""

    suspend fun load(modelPath: String, policy: String, useGpu: Boolean = true) {
        _state.value = ModelState.Loading
        val ok = withContext(Dispatchers.Default) {
            inference.load(modelPath, useGpu)
        }
        if (ok) {
            systemPrompt = policy
            _state.value = ModelState.Ready
        } else {
            _state.value = ModelState.Error
        }
    }

    suspend fun call(userMessage: String, contextTurns: String = ""): String {
        if (_state.value != ModelState.Ready) return "{\"kind\":\"reply\",\"text\":\"Model not ready.\"}"
        val prompt = buildPrompt(userMessage, contextTurns)
        return withContext(Dispatchers.Default) {
            inference.infer(prompt, 512)
        }
    }

    private fun buildPrompt(userMessage: String, contextTurns: String): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append("SYSTEM: ")
        sb.append(systemPrompt)
        if (contextTurns.isNotBlank()) {
            sb.append("\n\nCONVERSATION:\n")
            sb.append(contextTurns)
        }
        sb.append("\n\nUSER: ")
        sb.append(userMessage)
        sb.append("<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }

    fun systemInfo(): String = inference.systemInfo()

    fun unload() {
        inference.unload()
        _state.value = ModelState.Idle
    }
}
