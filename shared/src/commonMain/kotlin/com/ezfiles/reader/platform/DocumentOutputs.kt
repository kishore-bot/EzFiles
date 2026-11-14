package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** Creates and removes the files a tool writes, in places the user chose through a system dialog. */
interface DocumentOutputs {
    /** Size in bytes, or `null` if the provider doesn't say. A Save As target of size 0 was just created. */
    suspend fun sizeOf(uri: String): Long?

    /** Removes a file; `false` if the provider refused or doesn't support it. Never throws. */
    suspend fun delete(uri: String): Boolean

    /** Whether files can be created in [folder], from [rememberWritableFolderPicker]. */
    suspend fun canCreateIn(folder: String): Boolean

    /**
     * A new, empty file in [folder]; never an existing file, as the provider
     * makes [name] unique. Throws [com.ezfiles.reader.domain.DocumentException].
     */
    suspend fun createIn(folder: String, name: String, mimeType: String): String

    /** The name the provider gave the file, or `null` if it doesn't say. */
    suspend fun nameOf(uri: String): String?
}

@Composable
expect fun rememberDocumentOutputs(): DocumentOutputs
