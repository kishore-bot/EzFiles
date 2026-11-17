package com.ezfiles.reader.application.assistant

import kotlinx.coroutines.flow.StateFlow

/** One file of a downloadable model, checked by size and SHA-256 before it is used. */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/**
 * The AI models EzFiles downloads on request (they are too large for the app itself). They are
 * published as assets of a GitHub release, [RELEASE_URL] + file name, and stored in the app's
 * private data folder.
 */
enum class AssistantModel(val label: String, val folder: String, val files: List<ModelFile>) {
    Fily(
        "Fily assistant model", "fily-v8",
        listOf(ModelFile("Fily-EzFiles-350M-Q8_0.gguf", 379_220_000, "44b6af9cb9e94c07509cbfe7c6107504c7eb40dadbb70edeaedf1b98d127de0a")),
    ),
    Moonshine(
        "Moonshine speech model", "moonshine-base-en",
        listOf(
            ModelFile("encoder_model.ort", 31_326_816, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
            ModelFile("decoder_model_merged.ort", 109_424_400, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
            ModelFile("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
            ModelFile("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        ),
    ),
    ;

    val bytes: Long get() = files.sumOf { it.bytes }

    companion object {
        /** Release assets: each file is downloaded from this URL plus its name. */
        const val RELEASE_URL = "https://github.com/kishore-bot/EzFiles/releases/download/models-v1/"
    }
}

sealed interface ModelInstall {
    data object Missing : ModelInstall
    /** [done] of [total] bytes. */
    data class Downloading(val done: Long, val total: Long) : ModelInstall
    data object Verifying : ModelInstall
    data object Installed : ModelInstall
    data class Failed(val message: String) : ModelInstall
}

/** Downloads, imports and locates the models. */
interface ModelDownloads {
    fun state(model: AssistantModel): StateFlow<ModelInstall>

    /** Downloads every missing file, resuming a partial one; a no-op while one is running. */
    fun download(model: AssistantModel)

    fun cancel(model: AssistantModel)

    /**
     * Copies model files the user downloaded themselves (e.g. from GitHub in the browser). Each
     * picked file is matched to the model's files by size and checked by SHA-256.
     */
    fun import(model: AssistantModel, uris: List<String>)

    /** The installed folder, or null. */
    fun folder(model: AssistantModel): String?

    fun delete(model: AssistantModel)
}
