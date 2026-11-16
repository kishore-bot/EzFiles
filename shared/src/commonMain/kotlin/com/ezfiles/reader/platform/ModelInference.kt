package com.ezfiles.reader.platform

expect class ModelInference() {
    /** [stablePrefix] is read and kept at load, so the first request starts with it in place. */
    fun load(path: String, useGpu: Boolean, threads: Int, ubatch: Int, stablePrefix: String): Boolean
    /** [stablePrefix] starts every prompt; the engine keeps its state so later prompts skip reading it. */
    fun infer(prompt: String, maxTokens: Int, stablePrefix: String, grammar: String): String
    fun isLoaded(): Boolean
    fun unload()
    /** True once the GPU threw during inference and the engine switched to a CPU-only copy. */
    fun gpuFailed(): Boolean
    fun systemInfo(): String
}
