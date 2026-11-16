package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.platform.ModelInference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ModelState { Idle, Loading, Ready, Error }

/**
 * Loads Fily and runs it on one thread at a time. The GPU is tried at most once per install: a GPU
 * that throws, hangs or takes the app down is remembered, and later launches use the CPU.
 */
class ModelManager internal constructor(
    private val storage: KeyValueStorage,
    /** Changes with every install, so a new build gets one fresh GPU attempt. */
    private val buildStamp: String,
) {
    private var inference = ModelInference()
    private var lane = newLane()
    private var path = ""
    private var onGpu = false
    private var gpuProven = false
    private val _state = MutableStateFlow(ModelState.Idle)
    val state: StateFlow<ModelState> = _state

    suspend fun load(modelPath: String, preferGpu: Boolean) {
        path = modelPath
        _state.value = ModelState.Loading
        // Still "trying" means the last GPU attempt ended the process.
        if (storage.get(GPU_KEY) == "$buildStamp:$TRYING") storage.put(GPU_KEY, "$buildStamp:$BAD")
        onGpu = preferGpu && storage.get(GPU_KEY) != "$buildStamp:$BAD"
        if (onGpu && storage.get(GPU_KEY) != "$buildStamp:$PROVEN") storage.put(GPU_KEY, "$buildStamp:$TRYING")
        var ok = withContext(lane) { inference.load(modelPath, onGpu) }
        if (onGpu && (!ok || inference.gpuFailed())) {
            gpuIsBad()
            if (!ok) ok = withContext(lane) { inference.load(modelPath, false) }
        }
        _state.value = if (ok) ModelState.Ready else ModelState.Error
    }

    /** Continues a fully rendered prompt; see [FilyPrompt.render]. */
    suspend fun complete(prompt: String, maxTokens: Int = 128): String {
        if (_state.value != ModelState.Ready) return NOT_READY
        if (!onGpu) return withContext(lane) { inference.infer(prompt, maxTokens, FilyPrompt.STABLE_PREFIX) }
        val current = inference
        // Not a child of this call: a hung native call cannot be cancelled, so it is left behind.
        val call = CoroutineScope(lane).async { current.infer(prompt, maxTokens, FilyPrompt.STABLE_PREFIX) }
        val answer = withTimeoutOrNull(GPU_TIMEOUT_MS) { call.await() }
        if (answer != null && !current.gpuFailed()) {
            if (!gpuProven) storage.put(GPU_KEY, "$buildStamp:$PROVEN")
            gpuProven = true
            return answer
        }
        gpuIsBad()
        // The engine already answered on its own CPU copy after the GPU threw.
        if (answer != null) return answer
        // The GPU hung: start again on a fresh thread with a CPU-only engine.
        inference = ModelInference()
        lane = newLane()
        if (!withContext(lane) { inference.load(path, false) }) {
            _state.value = ModelState.Error
            return NOT_READY
        }
        return withContext(lane) { inference.infer(prompt, maxTokens, FilyPrompt.STABLE_PREFIX) }
    }

    fun systemInfo(): String = inference.systemInfo()

    fun unload() {
        inference.unload()
        _state.value = ModelState.Idle
    }

    private fun gpuIsBad() {
        onGpu = false
        storage.put(GPU_KEY, "$buildStamp:$BAD")
    }

    private companion object {
        const val GPU_KEY = "fily.gpu"
        const val TRYING = "trying"
        const val BAD = "bad"
        const val PROVEN = "proven"
        /** Far above a normal answer (a few seconds) but short enough not to look frozen for long. */
        const val GPU_TIMEOUT_MS = 30_000L
        const val NOT_READY = """{"kind":"reply","text":"Model not ready.","citations":[]}"""

        fun newLane(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
