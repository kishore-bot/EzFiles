package com.ezfiles.reader.platform

class LlamaBridge {
    companion object {
        init { System.loadLibrary("ezfiles-llama") }
    }

    external fun loadModel(path: String, useGpu: Boolean): Long
    external fun runInference(modelPtr: Long, prompt: String, maxTokens: Int, stablePrefix: String): String
    external fun freeModel(modelPtr: Long)
    external fun systemInfo(): String
    external fun gpuFailed(): Boolean
}
