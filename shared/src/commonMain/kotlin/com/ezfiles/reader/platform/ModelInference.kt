package com.ezfiles.reader.platform

expect class ModelInference() {
    fun load(path: String, useGpu: Boolean): Boolean
    /** [stablePrefix] starts every prompt; the engine keeps its state so later prompts skip reading it. */
    fun infer(prompt: String, maxTokens: Int, stablePrefix: String): String
    fun isLoaded(): Boolean
    fun unload()
    /** True once the GPU threw during inference and the engine switched to a CPU-only copy. */
    fun gpuFailed(): Boolean
    fun systemInfo(): String
}
