package com.ezfiles.reader.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezfiles.reader.domain.ContentHit
import com.ezfiles.reader.domain.ContentSearch
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.FileNameSearch
import com.ezfiles.reader.domain.IndexProgress
import com.ezfiles.reader.domain.SubstringFileNameSearch
import com.ezfiles.reader.domain.byFrecency
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.RecentDocument
import com.ezfiles.reader.domain.RecentDocuments
import com.ezfiles.reader.platform.formatDate
import com.ezfiles.reader.presentation.viewers.formatBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryItem(val ref: DocumentRef, val type: FileType, val meta: String)

/** A file found by its text: [snippet] with the matched words at [highlights], on PDF [page] (from 0) if known. */
data class ContentItem(val item: LibraryItem, val snippet: String, val highlights: List<IntRange>, val page: Int?)

data class LibraryUiState(
    val query: String = "",
    val filter: FileFilter = FileFilter.All,
    /** Recently opened, shown as a strip above the folders and their files. */
    val recents: List<LibraryItem> = emptyList(),
    /** The main list: files from the chosen folders, or the history (see [showsHistory]). */
    val items: List<LibraryItem> = emptyList(),
    val supportsFolders: Boolean = false,
    val folders: List<LibraryFolder> = emptyList(),
    val scanning: Boolean = false,
    /** The scan stopped at a size limit, so some files may be missing from the list. */
    val limitReached: Boolean = false,
    /** The chosen folders hold no supported files. */
    val noFolderFiles: Boolean = true,
    /** Nothing at all to show yet (no history and no folder files). */
    val libraryEmpty: Boolean = true,
    /** The system refused the folder the user just picked, or has no folder picker. */
    val addFolderFailed: Boolean = false,
    /** While searching: files whose text matches, other than those already in [items]. */
    val contentHits: List<ContentItem> = emptyList(),
    /** Search inside files is available here. */
    val searchesContent: Boolean = false,
    /** While searching and the index is still being built: how far it has got. */
    val indexing: IndexProgress? = null,
) {
    /** [items] come from the opening history rather than the chosen folders. */
    val showsHistory: Boolean get() = filter == FileFilter.Recent || folders.isEmpty()

    /** Adding and removing folders belongs to the plain view, not to search or history. */
    val showsFolderControls: Boolean get() = supportsFolders && filter != FileFilter.Recent && query.isEmpty()
}

/**
 * The library: every supported document in the folders the user chose, the
 * opening history, search and type filters. Filtering runs off the main
 * thread, so large lists stay smooth.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val recents: RecentDocuments,
    private val device: DeviceDocuments,
    computation: CoroutineDispatcher = Dispatchers.Default,
    private val nameSearch: FileNameSearch = SubstringFileNameSearch,
    private val contentSearch: ContentSearch? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(FileFilter.All)
    private val addFolderFailed = MutableStateFlow(false)

    /** Folder files mapped to display items, their names prepared for search, once per scan, not per keystroke. */
    private val deviceItems = device.files.map { files -> Searchable(files.map { toItem(it, null, null) }) }.flowOn(computation)
    /** Most used first: recency-weighted visit counts, which is plain recency for files opened once. */
    private val recentItems = recents.documents.map { list -> Searchable(list.byFrecency().map { toItem(it.ref, it, it.format) }) }
        .flowOn(computation)

    /** Display items with their names prepared for [FileNameSearch] on first search. */
    private inner class Searchable(val items: List<LibraryItem>) {
        private val matcher by lazy { nameSearch.prepare(items.map { it.ref.displayName }) }

        /** Best matches first; everything, in order, without a query. */
        fun matching(query: String): List<LibraryItem> =
            if (query.isEmpty()) items else matcher.rank(query).mapNotNull(items::getOrNull)
    }

    /** Files whose text matches the query as typed, with the query asked; re-asked as the index grows. */
    private val contentResults: Flow<Pair<String, List<ContentHit>>> = contentSearch?.let { search ->
        val typed = query.map { it.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0L else CONTENT_DEBOUNCE_MILLIS }
        // Every few files indexed, the same query may find more.
        val growth = search.progress.map { it.indexed / REQUERY_EVERY }.distinctUntilChanged()
        combine(typed, growth) { q, _ -> q }.mapLatest { q ->
            if (q.length < MIN_CONTENT_QUERY) return@mapLatest q to emptyList()
            val hits = try {
                search.search(q, CONTENT_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Search inside files is a bonus: if it fails, names still work.
                emptyList()
            }
            q to hits
        }.onStart { emit("" to emptyList()) }
    } ?: flowOf("" to emptyList())

    private val inputs = combine(query, filter, device.folders, device.scanning, device.limitReached) { q, f, folders, scanning, limit ->
        Inputs(q.trim(), f, folders, scanning, limit)
    }

    private val listState = combine(inputs, recentItems, deviceItems, addFolderFailed) { input, historyList, filesList, failed ->
        val history = historyList.items
        val files = filesList.items
        // All lists every file type, images included.
        val byType = { item: LibraryItem -> input.filter == FileFilter.All || input.filter in item.type.filters }
        val home = input.filter == FileFilter.All && input.query.isEmpty() && device.supportsFolders
        // Without folders, categories and search still work over the history.
        val source = when {
            input.filter == FileFilter.Recent -> historyList
            input.folders.isNotEmpty() -> filesList
            // The strip already shows the history here.
            home -> null
            else -> historyList
        }
        LibraryUiState(
            query = input.query,
            filter = input.filter,
            recents = if (home) history.take(RECENT_STRIP) else emptyList(),
            items = source?.matching(input.query)?.filter { input.filter == FileFilter.Recent || byType(it) }.orEmpty(),
            supportsFolders = device.supportsFolders,
            folders = input.folders,
            scanning = input.scanning,
            limitReached = input.limitReached,
            noFolderFiles = files.isEmpty(),
            libraryEmpty = history.isEmpty() && files.isEmpty(),
            addFolderFailed = failed,
            searchesContent = contentSearch != null && input.filter != FileFilter.Recent,
        )
    }

    val state: StateFlow<LibraryUiState> = combine(
        listState,
        contentResults,
        contentSearch?.progress ?: flowOf(IndexProgress()),
    ) { list, results, progress ->
        if (!list.searchesContent || list.query.length < MIN_CONTENT_QUERY) return@combine list
        val (asked, hits) = results
        val listed = list.items.mapTo(HashSet()) { it.ref.identity }
        val typeShown = { type: FileType -> list.filter == FileFilter.All || list.filter in type.filters }
        list.copy(
            // Results for an older query stay until the new ones arrive, so the list doesn't flicker while typing.
            contentHits = if (asked.isEmpty()) emptyList() else hits.asSequence()
                .filter { it.ref.identity !in listed }
                .map { ContentItem(toItem(it.ref, null, null), it.snippet, it.highlights, it.page) }
                .filter { typeShown(it.item.type) }
                .toList(),
            indexing = progress.takeIf { it.running && it.indexed < it.total },
        )
    }.flowOn(computation).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LibraryUiState(
            supportsFolders = device.supportsFolders,
            folders = device.folders.value,
            // With folders, a scan is about to start: don't announce "No files" before it does.
            scanning = device.folders.value.isNotEmpty(),
        ),
    )

    init {
        // A file in a folder that's gone can't be reopened, so its recents entry would only fail.
        viewModelScope.launch {
            var known = device.folders.value.map { it.uri }
            device.folders.collect { folders ->
                val current = folders.map { it.uri }
                val gone = known - current.toSet()
                known = current
                if (gone.isNotEmpty()) forgetRecentsUnder(gone)
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterSelected(value: FileFilter) {
        filter.value = value
    }

    /** Re-lists folder files; called when the screen resumes. */
    fun refreshDevice() {
        viewModelScope.launch { device.refresh() }
    }

    /** The library is on screen, or was left: the search index is built only while it shows. */
    fun onVisibleChange(visible: Boolean) {
        contentSearch?.setActive(visible)
    }

    fun addFolder(uri: String) {
        viewModelScope.launch { addFolderFailed.value = !device.addFolder(uri) }
    }

    fun onFolderPickerUnavailable() {
        addFolderFailed.value = true
    }

    fun onAddFolderFailureSeen() {
        addFolderFailed.value = false
    }

    fun removeFolder(uri: String) {
        viewModelScope.launch { device.removeFolder(uri) }
    }

    fun remove(ref: DocumentRef) {
        viewModelScope.launch { recents.remove(ref.identity) }
    }

    fun clearAll() {
        viewModelScope.launch { recents.clear() }
    }

    private suspend fun forgetRecentsUnder(folders: List<String>) {
        recents.documents.value
            .filter { recent -> folders.any { recent.ref.uri.startsWith("$it/") } }
            .forEach { recents.remove(it.ref.identity) }
    }

    private fun toItem(ref: DocumentRef, recent: RecentDocument?, format: com.ezfiles.reader.domain.DocumentFormat?): LibraryItem {
        val date = ref.modifiedAt?.let { "Modified ${formatDate(it)}" }
            ?: recent?.let { "Opened ${formatDate(it.openedAt)}" }
        val meta = listOfNotNull(ref.sizeBytes?.let(::formatBytes), date).joinToString("  •  ")
        return LibraryItem(ref, FileType.of(ref.displayName, format), meta)
    }

    private data class Inputs(
        val query: String,
        val filter: FileFilter,
        val folders: List<LibraryFolder>,
        val scanning: Boolean,
        val limitReached: Boolean,
    )

    private companion object {
        const val RECENT_STRIP = 12
        /** One letter matches too much text to be useful. */
        const val MIN_CONTENT_QUERY = 2
        const val CONTENT_LIMIT = 20
        const val CONTENT_DEBOUNCE_MILLIS = 150L
        const val REQUERY_EVERY = 10
    }
}
