package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.platform.traceAppWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The library's files: everything supported in the folders the user chose.
 * The chosen folders are persisted as JSON; only references and names are
 * stored. Scans run in this class's own scope so a change of folders can
 * replace the one in progress.
 *
 * The last scan's results are kept in [scanCache], so the list and its counts
 * show at once on the next launch while a fresh scan runs behind them.
 */
internal class ChosenFolderDocuments(
    private val storage: KeyValueStorage,
    private val trees: DocumentTrees,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: TreeLimits = TreeLimits(),
    private val scanCache: KeyValueStorage? = null,
) : DeviceDocuments {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Mutex()
    private var scan: Job? = null
    private var cacheLoad: Job? = null
    private var walks: Map<String, TreeWalk> = emptyMap()

    private val _folders = MutableStateFlow(load())
    private val _files = MutableStateFlow<List<DocumentRef>>(emptyList())

    // Resuming the library always refreshes, so with folders a scan is about to start.
    private val _scanning = MutableStateFlow(_folders.value.isNotEmpty())
    private val _limitReached = MutableStateFlow(false)
    private val _initialMetadataReady = MutableStateFlow(_folders.value.isEmpty())
    val initialMetadataReady: StateFlow<Boolean> = _initialMetadataReady.asStateFlow()

    override val supportsFolders = true
    override val folders: StateFlow<List<LibraryFolder>> = _folders.asStateFlow()
    override val files: StateFlow<List<DocumentRef>> = _files.asStateFlow()
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    override val limitReached: StateFlow<Boolean> = _limitReached.asStateFlow()

    init {
        if (_folders.value.isNotEmpty()) cacheLoad = scope.launch {
            try {
                val cached = try { traceAppWork("EzFiles.libraryCache") { loadCache() } } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyMap() }
                lock.withLock {
                    // A fresh scan always wins, including an empty/unavailable result.
                    if (scan == null) {
                        walks = cached.filterKeys { key -> _folders.value.any { it.uri == key } }
                        publish()
                    }
                }
            } finally {
                _initialMetadataReady.value = true
            }
        }
    }

    /** A scan already running will do: each return to the library asks for one. */
    override suspend fun refresh() {
        // Restore useful metadata before walking providers; the caller suspends without blocking UI.
        cacheLoad?.join()
        lock.withLock {
            if (scan?.isActive != true) scan = startScan()
            scan
        }?.join()
    }

    override suspend fun addFolder(uri: String): Boolean {
        val folder = withContext(io) { trees.take(uri) } ?: return false
        replaceScan { list -> list.filterNot { it.uri == folder.uri } + folder }
        return true
    }

    override suspend fun removeFolder(uri: String) {
        withContext(io) { trees.release(uri) }
        replaceScan { list -> list.filterNot { it.uri == uri } }
    }

    private suspend fun replaceScan(change: (List<LibraryFolder>) -> List<LibraryFolder>) {
        lock.withLock {
            scan?.cancel()
            // Shown at once; a slow provider may hold the old scan for a moment.
            _folders.update(change)
            scan?.join()
            persist()
            scan = startScan()
            scan
        }?.join()
    }

    private fun startScan(): Job {
        _scanning.value = true
        return scope.launch {
            try {
                val current = _folders.value
                walks = walks.filterKeys { key -> current.any { it.uri == key } }
                publish()
                val share = limits.sharedBy(current.size)
                for (folder in current) {
                    ensureActive()
                    when (val status = trees.status(folder.uri)) {
                        is TreeStatus.Readable -> {
                            walks = walks + (folder.uri to walkDocumentTree(trees, folder.uri, status.rootId, share))
                            setAvailable(folder.uri, true)
                        }
                        TreeStatus.Unavailable -> {
                            walks = walks - folder.uri
                            setAvailable(folder.uri, false)
                        }
                        TreeStatus.Lost -> {
                            walks = walks - folder.uri
                            _folders.update { list -> list.filterNot { it.uri == folder.uri } }
                            persist()
                            trees.release(folder.uri)
                        }
                    }
                    publish()
                }
                saveCache()
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun publish() {
        _files.value = merged()
        _limitReached.value = walks.values.any { it.limitReached }
        _initialMetadataReady.value = true
    }

    private fun merged(): List<DocumentRef> = walks.values
        .flatMap { it.refs }
        .distinctBy { it.identity }
        .sortedByDescending { it.modifiedAt ?: 0L }

    private fun loadCache(): Map<String, TreeWalk> = try {
        scanCache?.get(CACHE_KEY)?.let { json.decodeFromString<List<CachedWalk>>(it) }
            ?.associate { walk ->
                walk.folder to TreeWalk(walk.files.map { DocumentRef(it.uri, it.name, it.size, it.modified, it.identity ?: it.uri) }, walk.limitReached)
            }
            .orEmpty()
    } catch (e: IllegalArgumentException) {
        // Corrupt or from another version: the scan rebuilds it.
        emptyMap()
    }

    private fun saveCache() {
        val cache = scanCache ?: return
        val stored = walks.map { (folder, walk) ->
            CachedWalk(
                folder,
                walk.refs.map { CachedFile(it.uri, it.displayName, it.sizeBytes, it.modifiedAt, it.identity.takeIf { id -> id != it.uri }) },
                walk.limitReached,
            )
        }
        cache.put(CACHE_KEY, json.encodeToString(stored))
    }

    private fun setAvailable(uri: String, available: Boolean) {
        _folders.update { list -> list.map { if (it.uri == uri) it.copy(available = available) else it } }
    }

    private fun persist() {
        storage.put(KEY, json.encodeToString(_folders.value.map { StoredFolder(it.uri, it.name) }))
    }

    private fun load(): List<LibraryFolder> = try {
        storage.get(KEY)?.let { json.decodeFromString<List<StoredFolder>>(it) }
            ?.map { LibraryFolder(it.uri, it.name) }
            .orEmpty()
    } catch (e: IllegalArgumentException) {
        // Corrupt or from an incompatible version: start fresh.
        emptyList()
    }

    @Serializable
    private data class StoredFolder(val uri: String, val name: String)

    @Serializable
    private data class CachedWalk(val folder: String, val files: List<CachedFile>, val limitReached: Boolean = false)

    @Serializable
    private data class CachedFile(val uri: String, val name: String, val size: Long? = null, val modified: Long? = null, val identity: String? = null)

    private companion object {
        const val KEY = "library_folders_v1"
        // Rebuild caches that used name/size identities and could collapse distinct files.
        const val CACHE_KEY = "library_scan_v2"
        val json = Json { ignoreUnknownKeys = true }
    }
}
