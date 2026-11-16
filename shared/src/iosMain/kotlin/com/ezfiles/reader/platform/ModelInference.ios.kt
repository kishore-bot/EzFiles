package com.ezfiles.reader.platform

actual class ModelInference actual constructor() {
    actual fun load(path: String, useGpu: Boolean): Boolean = false
    actual fun infer(prompt: String, maxTokens: Int, stablePrefix: String): String = "{\"kind\":\"reply\",\"text\":\"Not available on iOS.\"}"
    actual fun isLoaded(): Boolean = false
    actual fun unload() {}
    actual fun systemInfo(): String = "iOS stub"
    actual fun gpuFailed(): Boolean = false
}
