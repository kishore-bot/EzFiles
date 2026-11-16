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

    suspend fun load(modelPath: String, useGpu: Boolean) {
        _state.value = ModelState.Loading
        val ok = withContext(Dispatchers.Default) { inference.load(modelPath, useGpu) }
        _state.value = if (ok) ModelState.Ready else ModelState.Error
    }

    /** Continues a fully rendered prompt; see [FilyPrompt.render]. */
    suspend fun complete(prompt: String, maxTokens: Int = 256): String {
        if (_state.value != ModelState.Ready) return """{"kind":"reply","text":"Model not ready.","citations":[]}"""
        return withContext(Dispatchers.Default) { inference.infer(prompt, maxTokens) }
    }

    fun systemInfo(): String = inference.systemInfo()

    fun unload() {
        inference.unload()
        _state.value = ModelState.Idle
    }
}
