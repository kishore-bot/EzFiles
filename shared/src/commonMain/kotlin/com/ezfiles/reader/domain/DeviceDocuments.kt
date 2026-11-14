package com.ezfiles.reader.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * A folder the user chose for the library. [uri] is meaningful only to the
 * platform adapter. [available] is false while the folder can't be reached but
 * may come back, such as on a removed SD card.
 */
data class LibraryFolder(val uri: String, val name: String, val available: Boolean = true)

/**
 * Port to the supported documents in folders the user chose. Only references
 * and metadata are read; files are opened through [DocumentReader] as usual.
 */
interface DeviceDocuments {
    /** False where the platform has no folder access to offer (iOS). */
    val supportsFolders: Boolean

    val folders: StateFlow<List<LibraryFolder>>

    /** Across all folders, one entry per file identity, newest first. */
    val files: StateFlow<List<DocumentRef>>

    val scanning: StateFlow<Boolean>

    /** The last scan stopped at a size limit, so [files] may be incomplete. */
    val limitReached: StateFlow<Boolean>

    /** Re-lists files, and drops folders whose access is gone. */
    suspend fun refresh()

    /** Keeps access to a folder from the system picker; false if the platform refuses it. */
    suspend fun addFolder(uri: String): Boolean

    /** Forgets a folder and gives up access to it. The files themselves are untouched. */
    suspend fun removeFolder(uri: String)
}
