package com.ezfiles.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezfiles.reader.application.DocumentReadingService
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.mediaKindOf
import com.ezfiles.reader.presentation.ReaderUiState.Cancelled
import com.ezfiles.reader.presentation.ReaderUiState.Failed
import com.ezfiles.reader.presentation.ReaderUiState.Idle
import com.ezfiles.reader.presentation.ReaderUiState.Loading
import com.ezfiles.reader.presentation.ReaderUiState.PasswordNeeded
import com.ezfiles.reader.presentation.ReaderUiState.Playing
import com.ezfiles.reader.presentation.ReaderUiState.Reading
import com.ezfiles.reader.presentation.ReaderUiState.Ready
import com.ezfiles.reader.presentation.ReaderUiState.SelectingFile
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns [ReaderAction]s into [ReaderUiState]s. Holds no parsing logic: all
 * document work goes through [DocumentReadingService].
 */
class ReaderViewModel(
    private val reading: DocumentReadingService,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(Idle)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var searchJob: Job? = null
    private var opens = 0

    /** Bookmarks: the place the file being opened was picked at; kept through a password prompt. */
    private var openAt: BookmarkLocation? = null

    fun onAction(action: ReaderAction) {
        when (action) {
            ReaderAction.PickDocument -> _state.update { current ->
                if (current is SelectingFile) current else SelectingFile(previous = current)
            }
            ReaderAction.PickerDismissed -> _state.update { (it as? SelectingFile)?.previous ?: it }
            is ReaderAction.DocumentPicked -> {
                val current = _state.value
                openAt = action.at
                if (current is Ready && current.editing) _state.value = current.copy(pendingOpen = action.ref) else open(action.ref)
            }
            ReaderAction.OpenEditor -> {
                searchJob?.cancel()
                _state.update { current ->
                    if (current is Ready && current.canEdit) current.copy(editing = true, search = null) else current
                }
            }
            is ReaderAction.CloseEditor -> closeEditor(action.saved)
            ReaderAction.LoadMore -> loadMore()
            ReaderAction.RetryLoadMore -> {
                updateReady { it.copy(loadMoreError = null) }
                loadMore()
            }
            ReaderAction.ToggleSource -> (_state.value as? Ready)
                ?.takeIf { it.canSwitchSource }
                ?.let {
                    openAt = null
                    open(it.ref, asSource = !it.source)
                }
            ReaderAction.OpenSearch -> _state.update { current ->
                if (current is Ready && current.canSearch && current.search == null) current.copy(search = ReaderSearch()) else current
            }
            ReaderAction.CloseSearch -> {
                searchJob?.cancel()
                updateReady { it.copy(search = null) }
            }
            is ReaderAction.SearchQueryChanged -> search(action.query)
            ReaderAction.NextMatch -> updateSearch { it.next() }
            ReaderAction.PreviousMatch -> updateSearch { it.previous() }
            is ReaderAction.PageFindChanged -> updateSearch { it.copy(pageFind = action.find) }
            ReaderAction.Cancel -> cancelOpening()
            is ReaderAction.SubmitPassword -> (_state.value as? PasswordNeeded)
                ?.takeIf { !it.unlocking }
                ?.let { open(it.ref, password = action.password) }
            ReaderAction.CloseDocument -> {
                searchJob?.cancel()
                job?.cancel()
                reading.cancel()
                reading.close()
                _state.value = Idle
            }
            is ReaderAction.ExtractEntry -> extractEntry(action.path)
            is ReaderAction.ShowFindReplace -> _showFindReplace.value = action.show
            is ReaderAction.ReplaceAll -> {} // wired to UI; engine call runs from App.kt
            ReaderAction.ToggleDarkReading -> _darkReading.value = !(_darkReading.value ?: themeDark)
        }
    }

    /** [password] is only passed on to this one open; a retry comes with its own. */
    private fun open(ref: DocumentRef, asSource: Boolean = false, password: String? = null) {
        searchJob?.cancel()
        job?.cancel()
        mediaKindOf(ref.displayName)?.let { kind ->
            reading.close()
            _state.value = Playing(ref, kind)
            return
        }
        job = viewModelScope.launch {
            // The sheet stays up during a try, so a wrong password doesn't flash it away.
            _state.value = if (password != null) PasswordNeeded(ref, unlocking = true) else Loading(ref.displayName)
            try {
                val info = reading.open(ref, asSource, password)
                ensureActive()
                _state.value = Reading(info)
                val batch = reading.nextBatch()
                ensureActive()
                _state.value = Ready(
                    ref = ref,
                    info = info,
                    pages = listOfNotNull(batch?.blocks).toPersistentList(),
                    endReached = batch?.isLast ?: true,
                    truncated = batch?.truncated ?: false,
                    source = asSource,
                    openId = ++opens,
                    initialLocation = openAt,
                )
            } catch (e: DocumentException) {
                // A failure outruns cancellation, and by now the session may be the next document's.
                ensureActive()
                reading.close()
                _state.value = when (e.error) {
                    is DocumentError.PasswordRequired -> PasswordNeeded(ref)
                    is DocumentError.WrongPassword -> PasswordNeeded(ref, incorrect = true)
                    else -> Failed(ref.displayName, e.error, openAt?.let { BookmarkSpot(ref.identity, it) })
                }
            }
        }
    }

    /** A saved file is opened again, so the reader shows what is now on disk. */
    private fun closeEditor(saved: DocumentRef?) {
        val current = _state.value as? Ready ?: return
        when {
            current.pendingOpen != null -> open(current.pendingOpen)
            saved != null -> {
                openAt = null
                open(saved, asSource = current.source && saved.uri == current.ref.uri)
            }
            else -> _state.value = current.copy(editing = false)
        }
    }

    /**
     * Stored in [job], so [open] cancels it. That cancellation, not the state's
     * type or its ref, keeps a batch out of the next document: the same file
     * can be open again by the time a batch returns.
     */
    private fun loadMore() {
        val current = _state.value.ready ?: return
        if (current.endReached || current.loadingMore || current.loadMoreError != null) return
        updateReady { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            try {
                val batch = reading.nextBatch()
                ensureActive()
                updateReady { state ->
                    state.copy(
                        pages = if (batch == null || batch.blocks.isEmpty()) state.pages else state.pages.add(batch.blocks),
                        endReached = batch?.isLast ?: true,
                        truncated = batch?.truncated ?: false,
                        loadingMore = false,
                    )
                }
            } catch (e: DocumentException) {
                ensureActive()
                updateReady { it.copy(loadingMore = false, loadMoreError = e.error) }
            }
        }
    }

    /**
     * Debounces [query], scans what is loaded off the main thread, then keeps
     * pulling batches until the end of the document or the engine's output
     * budget. Pages stay loaded, so a new query rescans without rereading.
     */
    private fun search(query: String) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            updateSearch { it.withResults(SearchResults(""), searching = false) }
            return
        }
        // Old highlights stay until the new ones are ready, so typing does not flicker.
        updateSearch { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            var results = SearchResults(query)
            while (true) {
                val ready = _state.value.ready ?: return@launch
                if (results.pagesSearched < ready.pages.size) {
                    val searched = results
                    results = withContext(searchDispatcher) { searched.plus(ready.pages) { ensureActive() } }
                    val published = results
                    updateSearch { it.withResults(published, searching = !published.capped) }
                    if (published.capped) return@launch
                } else if (ready.endReached) {
                    val published = results
                    updateSearch { it.withResults(published, searching = false) }
                    return@launch
                } else if (ready.loadMoreError != null) {
                    // The footer and the bar offer a retry; carry on if it is taken.
                    val published = results
                    updateSearch { it.withResults(published, searching = false) }
                    _state.first { it.ready?.loadMoreError == null }
                    updateSearch { it.copy(searching = true) }
                } else {
                    // Also waits out a batch the viewer already asked for.
                    loadMore()
                    val pages = results.pagesSearched
                    _state.first { state -> state.ready.let { it == null || !it.loadingMore || it.pages.size > pages } }
                }
            }
        }
    }

    private fun updateSearch(transform: (ReaderSearch) -> ReaderSearch) {
        updateReady { state -> state.search?.let { state.copy(search = transform(it)) } ?: state }
    }

    /** The open document, also while the picker is showing over it. */
    private val ReaderUiState.ready: Ready?
        get() = this as? Ready ?: (this as? SelectingFile)?.previous as? Ready

    /** Work on the open document goes on behind the picker, which may yet be dismissed. */
    private fun updateReady(transform: (Ready) -> Ready) {
        _state.update { state ->
            when {
                state is Ready -> transform(state)
                state is SelectingFile && state.previous is Ready -> state.copy(previous = transform(state.previous))
                else -> state
            }
        }
    }

    private fun cancelOpening() {
        val name = when (val state = _state.value) {
            is Loading -> state.name
            is Reading -> state.info.name
            is PasswordNeeded -> state.ref.displayName
            else -> return
        }
        reading.cancel()
        job?.cancel()
        reading.close()
        _state.value = Cancelled(name)
    }

    /** An embedded resource of the open document, such as a slide picture. */
    suspend fun loadResource(name: String): ByteArray? = reading.resource(name)

    private val _showFindReplace = MutableStateFlow(false)
    val showFindReplace: StateFlow<Boolean> = _showFindReplace.asStateFlow()

    /**
     * Null: documents follow the app theme (dark pages in dark mode). The reader menu's "Dark reading"
     * / "Light reading" sets it for the rest of the session.
     */
    private val _darkReading = MutableStateFlow<Boolean?>(null)
    val darkReadingOverride: StateFlow<Boolean?> = _darkReading.asStateFlow()

    /** The app theme, kept current by the UI, so the reader's toggle flips what is on screen. */
    var themeDark: Boolean = false

    data class ExtractedEntry(val name: String, val bytes: ByteArray)

    private val _extractedEntry = MutableStateFlow<ExtractedEntry?>(null)
    val extractedEntry: StateFlow<ExtractedEntry?> = _extractedEntry.asStateFlow()

    private fun extractEntry(path: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val bytes = reading.resource(path) ?: return@launch
            val name = path.trimEnd('/').substringAfterLast('/')
            _extractedEntry.value = ExtractedEntry(name, bytes)
        }
    }

    fun clearExtractedEntry() {
        _extractedEntry.value = null
    }

    override fun onCleared() {
        job?.cancel()
        reading.close()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
