package com.ezfiles.reader.presentation

import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.presentation.viewers.MAX_HTML_PREVIEW_BYTES
import kotlinx.collections.immutable.PersistentList

sealed interface ReaderUiState {
    data object Idle : ReaderUiState

    /** The system picker is showing; [previous] stays visible behind it. */
    data class SelectingFile(val previous: ReaderUiState) : ReaderUiState

    /** Audio or video, played by the platform rather than read by the engine. */
    data class Playing(val ref: DocumentRef, val kind: MediaKind) : ReaderUiState

    /** Acquiring file access, detecting the format and opening a parser. */
    data class Loading(val name: String) : ReaderUiState

    /** Opened; extracting the first batch. */
    data class Reading(val info: DocumentInfo) : ReaderUiState

    data class Ready(
        val ref: DocumentRef,
        val info: DocumentInfo,
        /** One page per engine batch. Persistent, so appending a batch shares
         *  the pages already loaded instead of copying the list. */
        val pages: PersistentList<List<ContentBlock>>,
        val endReached: Boolean,
        val truncated: Boolean,
        val loadingMore: Boolean = false,
        val loadMoreError: DocumentError? = null,
        /** Showing the raw text of a rendered format; [info] then describes plain text, or code for HTML. */
        val source: Boolean = false,
        /** Find-in-document; `null` while the search bar is closed. */
        val search: ReaderSearch? = null,
        /** The text editor covers the document. */
        val editing: Boolean = false,
        /** A file handed over during editing, opened once the editor closes so no text is lost. */
        val pendingOpen: DocumentRef? = null,
        /** Differs for every open, even of the same file, whose content may have changed. */
        val openId: Int = 0,
        /** Bookmarks: where to open, when opened from a bookmark; the viewer goes there once. */
        val initialLocation: BookmarkLocation? = null,
    ) : ReaderUiState {
        /** Whether the file is text the editor can open; the editor still checks its bytes and size. */
        val canEdit: Boolean
            get() = info.format in setOf(
                DocumentFormat.PlainText,
                DocumentFormat.Markdown,
                DocumentFormat.Csv,
                DocumentFormat.Json,
                DocumentFormat.SourceCode,
                DocumentFormat.Html,
            )

        /** A web page small enough to render; a larger one only has its code to show. */
        val canPreviewHtml: Boolean
            get() = info.format == DocumentFormat.Html && info.byteSize <= MAX_HTML_PREVIEW_BYTES

        /**
         * Whether this document has both a rendered view and a source view.
         * Word, OpenDocument and e-book files show only as their authors laid
         * them out: there is no restyled reading view to switch to.
         */
        val canSwitchSource: Boolean
            get() = source || canPreviewHtml ||
                info.format in setOf(DocumentFormat.Markdown, DocumentFormat.Json, DocumentFormat.Csv, DocumentFormat.SourceCode) ||
                // A .txt also opens as source (line numbers, monospace); logs and other text don't.
                (info.format == DocumentFormat.PlainText && info.name.substringAfterLast('.', "").lowercase() in setOf("txt", "text"))

        /** Slides and images draw on a canvas, PDFs have their own reader, archives are only a listing. */
        val canSearch: Boolean
            get() = info.kind == DocumentKind.Text || info.kind == DocumentKind.Spreadsheet
    }

    /**
     * The file is encrypted; nothing is open. The password itself stays in the
     * sheet's field and is never held in state. [incorrect] when the last try
     * was refused, [unlocking] while a try is running.
     */
    data class PasswordNeeded(
        val ref: DocumentRef,
        val incorrect: Boolean = false,
        val unlocking: Boolean = false,
    ) : ReaderUiState

    /** [fromBookmark] (bookmarks) when the file was opened from one, so a lost file's bookmark can be removed. */
    data class Failed(val name: String, val error: DocumentError, val fromBookmark: BookmarkSpot? = null) : ReaderUiState

    data class Cancelled(val name: String) : ReaderUiState
}

/** The search bar's state. The text being typed stays in the bar until the debounce has passed. */
data class ReaderSearch(
    val results: SearchResults = SearchResults(""),
    /** Index into the matches, -1 if there are none. */
    val currentIndex: Int = -1,
    /** Still debouncing, scanning, or pulling further batches. */
    val searching: Boolean = false,
    /** Changes whenever the viewer should scroll to the current match, even if it is the same one. */
    val revealTick: Int = 0,
    /** The count from a viewer that searches its own page, in place of [results]. */
    val pageFind: PageFind? = null,
    /** Presses of next or previous, for such a viewer. */
    val pageStep: Int = 0,
    val pageStepForward: Boolean = true,
) {
    val currentMatch: SearchMatch? get() = results.matches.getOrNull(currentIndex)

    val hasMatches: Boolean get() = results.matches.isNotEmpty() || (pageFind?.total ?: 0) > 0

    /** "3 / 17", with a plus while more may follow; `null` if there is no match to count. */
    val countLabel: String?
        get() = if (pageFind != null) {
            pageFind.takeIf { it.total > 0 }?.let { "${it.active + 1} / ${it.total}${if (it.done) "" else "+"}" }
        } else if (currentIndex < 0) {
            null
        } else {
            "${currentIndex + 1} / ${results.matches.size}${if (searching || results.capped) "+" else ""}"
        }

    fun next(): ReaderSearch = step(1)

    fun previous(): ReaderSearch = step(-1)

    private fun step(delta: Int): ReaderSearch {
        if (pageFind != null) {
            return if (pageFind.total > 0) copy(pageStep = pageStep + 1, pageStepForward = delta > 0) else this
        }
        val count = results.matches.size
        if (count == 0) return this
        return copy(currentIndex = (currentIndex + delta + count) % count, revealTick = revealTick + 1)
    }

    /** New results keep the current match if the query is the same, else select the first. */
    fun withResults(results: SearchResults, searching: Boolean): ReaderSearch {
        val sameQuery = results.query == this.results.query
        val index = when {
            results.matches.isEmpty() -> -1
            sameQuery && currentIndex >= 0 -> currentIndex.coerceAtMost(results.matches.lastIndex)
            else -> 0
        }
        val moved = !sameQuery || index != currentIndex
        return copy(
            results = results,
            currentIndex = index,
            searching = searching,
            revealTick = if (moved) revealTick + 1 else revealTick,
            // Counted for the old query; the viewer reports afresh.
            pageFind = if (sameQuery) pageFind else null,
        )
    }
}

/** A rendered page's own find: [active] is the current match, from 0, of [total] so far. */
data class PageFind(val active: Int, val total: Int, val done: Boolean)

sealed interface ReaderAction {
    data object PickDocument : ReaderAction
    /** [at] (bookmarks): open at this place, when opened from a bookmark. */
    data class DocumentPicked(val ref: DocumentRef, val at: BookmarkLocation? = null) : ReaderAction
    data object PickerDismissed : ReaderAction
    /** The viewer scrolled near the end of the loaded content. */
    data object LoadMore : ReaderAction
    data object RetryLoadMore : ReaderAction
    /** Stop opening the current document. */
    data object Cancel : ReaderAction
    /** Try this password on the file in [ReaderUiState.PasswordNeeded]. */
    data class SubmitPassword(val password: String) : ReaderAction {
        // A data class would print the password wherever the action is logged.
        override fun toString() = "SubmitPassword"
    }
    /** Switch between the rendered view and the raw text. */
    data object ToggleSource : ReaderAction
    data object OpenSearch : ReaderAction
    data object CloseSearch : ReaderAction
    data class SearchQueryChanged(val query: String) : ReaderAction
    data object NextMatch : ReaderAction
    data object PreviousMatch : ReaderAction
    /** A viewer that searches its own page has a new count. */
    data class PageFindChanged(val find: PageFind) : ReaderAction
    data object OpenEditor : ReaderAction
    /** The editor closed; [saved] is the document to show if a save happened. */
    data class CloseEditor(val saved: DocumentRef?) : ReaderAction
    /** Leave the reader and return to the library. */
    data object CloseDocument : ReaderAction
    /** Extract a ZIP entry to a user-chosen location. */
    data class ExtractEntry(val path: String) : ReaderAction
    data class ShowFindReplace(val show: Boolean) : ReaderAction
    data class ReplaceAll(val find: String, val replace: String, val caseSensitive: Boolean) : ReaderAction
    data object ToggleDarkReading : ReaderAction
}
