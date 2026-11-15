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
    private var fileCatalog: String = ""

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

    fun updateFileCatalog(catalog: String) {
        fileCatalog = catalog
    }

    suspend fun call(userMessage: String, contextTurns: String = ""): String {
        if (_state.value != ModelState.Ready) return "{\"kind\":\"reply\",\"text\":\"Model not ready.\"}"
        val prompt = buildPrompt(userMessage, contextTurns)
        return withContext(Dispatchers.Default) {
            inference.infer(prompt, 256)
        }
    }

    suspend fun callWithToolResult(
        userMessage: String,
        toolName: String,
        toolResult: String,
        contextTurns: String = "",
    ): String {
        if (_state.value != ModelState.Ready) return "{\"kind\":\"reply\",\"text\":\"Model not ready.\"}"
        val prompt = buildPromptWithToolResult(userMessage, toolName, toolResult, contextTurns)
        return withContext(Dispatchers.Default) {
            inference.infer(prompt, 256)
        }
    }

    private fun buildPrompt(userMessage: String, contextTurns: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        if (fileCatalog.isNotBlank()) {
            sb.append("\n\nFILES:\n")
            sb.append(fileCatalog)
        }
        if (contextTurns.isNotBlank()) {
            sb.append("\n\nCONVERSATION:\n")
            sb.append(contextTurns)
        }
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>user\n")
        sb.append(userMessage)
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildPromptWithToolResult(
        userMessage: String,
        toolName: String,
        toolResult: String,
        contextTurns: String,
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        if (fileCatalog.isNotBlank()) {
            sb.append("\n\nFILES:\n")
            sb.append(fileCatalog)
        }
        if (contextTurns.isNotBlank()) {
            sb.append("\n\nCONVERSATION:\n")
            sb.append(contextTurns)
        }
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>user\n")
        sb.append(userMessage)
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        sb.append("{\"kind\":\"tool\",\"name\":\"$toolName\"}<|im_end|>\n")
        sb.append("<|im_start|>tool\n")
        sb.append("$toolName result: $toolResult")
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun systemInfo(): String = inference.systemInfo()

    fun unload() {
        inference.unload()
        _state.value = ModelState.Idle
    }
}
