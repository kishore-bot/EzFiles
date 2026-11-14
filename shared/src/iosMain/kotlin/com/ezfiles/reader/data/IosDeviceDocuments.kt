package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** iOS is parked with no folder library; the library shows recents. */
internal object IosDeviceDocuments : DeviceDocuments {
    override val supportsFolders = false
    override val folders: StateFlow<List<LibraryFolder>> = MutableStateFlow(emptyList())
    override val files: StateFlow<List<DocumentRef>> = MutableStateFlow(emptyList())
    override val scanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val limitReached: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun refresh() = Unit

    override suspend fun addFolder(uri: String) = false

    override suspend fun removeFolder(uri: String) = Unit
}
