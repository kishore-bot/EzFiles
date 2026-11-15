package com.ezfiles.reader.platform

expect class ModelInference() {
    fun load(path: String, useGpu: Boolean): Boolean
    fun infer(prompt: String, maxTokens: Int = 512): String
    fun isLoaded(): Boolean
    fun unload()
    fun systemInfo(): String
}
