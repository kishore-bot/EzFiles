package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** Makes new files in a folder from [rememberWritableFolderPicker]. */
interface WritableFolders {
    /**
     * A new empty file, whose content goes through [DocumentFiles.write]. An
     * existing file is never replaced: the system picks a free name instead.
     *
     * @throws com.ezfiles.reader.domain.DocumentException when the folder refuses it
     */
    suspend fun createFile(folder: String, name: String, mimeType: String): String

    /** Removes a file this app just created; a location that refuses keeps it. */
    suspend fun delete(file: String)

    /** What the system calls the folder, for telling the user where things went. */
    suspend fun displayName(folder: String): String?
}

@Composable
expect fun rememberWritableFolders(): WritableFolders
