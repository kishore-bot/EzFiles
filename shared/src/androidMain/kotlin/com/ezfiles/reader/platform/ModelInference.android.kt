package com.ezfiles.reader.platform

actual class ModelInference actual constructor() {
    private val bridge = LlamaBridge()
    private var modelPtr: Long = 0L

    actual fun load(path: String, useGpu: Boolean): Boolean {
        if (modelPtr != 0L) unload()
        modelPtr = bridge.loadModel(path, useGpu)
        return modelPtr != 0L
    }

    actual fun infer(prompt: String, maxTokens: Int, stablePrefix: String, grammar: String): String {
        if (modelPtr == 0L) return "{\"kind\":\"reply\",\"text\":\"Model not loaded.\"}"
        return bridge.runInference(modelPtr, prompt, maxTokens, stablePrefix, grammar)
    }

    actual fun isLoaded(): Boolean = modelPtr != 0L

    actual fun unload() {
        if (modelPtr != 0L) {
            bridge.freeModel(modelPtr)
            modelPtr = 0L
        }
    }

    actual fun systemInfo(): String = bridge.systemInfo()

    actual fun gpuFailed(): Boolean = bridge.gpuFailed()
}
