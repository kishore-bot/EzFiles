package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.platform.PdfBox
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfTextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One occurrence of the query, located on its [page]. */
data class PdfSearchHit(val page: Int, val boxes: List<PdfBox>)

/**
 * The matches found so far, in document order, and which one is current.
 * Immutable: every change returns a new instance. Pages may arrive in any
 * order, but each at most once; the current hit stays current as others are
 * inserted around it.
 */
class PdfMatches private constructor(private val hits: List<PdfSearchHit>, private val currentIndex: Int) {
    private val byPage by lazy { hits.groupBy { it.page } }

    val count: Int get() = hits.size
    val current: PdfSearchHit? get() = hits.getOrNull(currentIndex)

    /** "3 / 17", or empty while there are no matches. */
    val label: String get() = if (hits.isEmpty()) "" else "${currentIndex + 1} / ${hits.size}"

    fun onPage(page: Int): List<PdfSearchHit> = byPage[page].orEmpty()

    /** Adds the matches of [page]. The first match ever found becomes current. */
    fun withPage(page: Int, matches: List<PdfTextMatch>): PdfMatches {
        if (matches.isEmpty()) return this
        val at = hits.indexOfFirst { it.page > page }.let { if (it < 0) hits.size else it }
        val merged = buildList(hits.size + matches.size) {
            addAll(hits.subList(0, at))
            matches.forEach { add(PdfSearchHit(page, it.boxes)) }
            addAll(hits.subList(at, hits.size))
        }
        val current = when {
            currentIndex < 0 -> at
            currentIndex >= at -> currentIndex + matches.size
            else -> currentIndex
        }
        return PdfMatches(merged, current)
    }

    fun next(): PdfMatches = moved(1)

    fun previous(): PdfMatches = moved(-1)

    private fun moved(by: Int): PdfMatches =
        if (hits.isEmpty()) this else PdfMatches(hits, (currentIndex + by).mod(hits.size))

    companion object {
        val None = PdfMatches(emptyList(), -1)
    }
}

/**
 * While typing a search, the page gets the room the controls would take. This
 * follows the keyboard, not the search bar: with the keyboard down there is room
 * for both. Annotating keeps its tools, even if a search is open as well.
 */
fun searchHidesPageControls(searchOpen: Boolean, keyboardOpen: Boolean, annotating: Boolean): Boolean =
    searchOpen && keyboardOpen && !annotating

/** Whether [box] lies fully inside a viewport, for a page item at [itemOffset] of height [itemSize]. */
fun matchInViewport(box: PdfBox, itemOffset: Int, itemSize: Int, viewportHeight: Int): Boolean =
    itemOffset + itemSize * box.top >= 0 && itemOffset + itemSize * box.bottom <= viewportHeight

/** Where the current match sits in the page list's latest layout. */
data class PdfMatchView(val hit: PdfSearchHit, val viewportHeight: Int, val visible: Boolean)

/** [itemOffset] is null while the match's page isn't laid out. A match with no box has nothing to bring into view. */
fun pdfMatchView(hit: PdfSearchHit, viewportHeight: Int, itemOffset: Int?, itemSize: Int): PdfMatchView {
    val box = hit.boxes.firstOrNull()
    val visible = box == null || (itemOffset != null && matchInViewport(box, itemOffset, itemSize, viewportHeight))
    return PdfMatchView(hit, viewportHeight, visible)
}

/**
 * Tells a resize that pushed the current match out of view from a user who
 * scrolled away from it. Only the first is undone: the reader isn't pulled back
 * to a match they left, just because the keyboard closed. Immutable.
 */
data class PdfMatchTracker(
    private val last: PdfMatchView?,
    /** The match is meant to be on screen: it's new, or it was still showing when last scrolled. */
    private val wanted: Boolean,
    /** The height changed since [settled]. */
    val resized: Boolean,
) {
    val needsReveal: Boolean get() = resized && wanted && last?.visible == false

    fun after(view: PdfMatchView?): PdfMatchTracker = when {
        view == null -> None
        last?.hit != view.hit -> PdfMatchTracker(view, wanted = true, resized = false)
        last.viewportHeight != view.viewportHeight -> copy(last = view, resized = true)
        else -> copy(last = view, wanted = view.visible)
    }

    fun settled(): PdfMatchTracker = copy(resized = false)

    companion object {
        val None = PdfMatchTracker(null, wanted = false, resized = false)
    }
}

sealed interface PdfSearchStatus {
    data object Idle : PdfSearchStatus

    /** [page] is 1-based, as shown to the user. */
    data class Searching(val page: Int, val pageCount: Int) : PdfSearchStatus

    data class Done(val result: PdfSearchResult, val unreadablePages: Int = 0) : PdfSearchStatus
}

enum class PdfSearchResult { Found, NoMatches, NoText, Failed }

/** The line under the search field, if it has anything to say. */
fun PdfSearchStatus.message(): String? = when (this) {
    PdfSearchStatus.Idle -> null
    is PdfSearchStatus.Searching -> "Searching page $page of $pageCount…"
    is PdfSearchStatus.Done -> {
        val unreadable = when (unreadablePages) {
            0 -> null
            1 -> "couldn't search 1 page"
            else -> "couldn't search $unreadablePages pages"
        }
        when (result) {
            PdfSearchResult.Found -> unreadable?.replaceFirstChar(Char::uppercase)
            PdfSearchResult.NoMatches -> if (unreadable == null) "No matches" else "No matches, $unreadable"
            PdfSearchResult.NoText -> "No text found in this PDF"
            PdfSearchResult.Failed -> "Couldn't search this PDF"
        }
    }
}

/**
 * Searches one page at a time, from [startPage] to the end and then from the
 * beginning up to it, so the first match near the reader shows quickly. The
 * platform lock is released between pages, so rendering is not starved.
 * Cancel the calling coroutine to stop.
 */
suspend fun searchPdf(
    pages: PdfPages,
    query: String,
    startPage: Int,
    onProgress: (PdfSearchStatus.Searching) -> Unit,
    onMatches: (page: Int, matches: List<PdfTextMatch>) -> Unit,
): PdfSearchStatus.Done {
    val count = pages.pageCount
    val start = startPage.coerceIn(0, (count - 1).coerceAtLeast(0))
    var found = false
    var recognizedText = false
    var unreadable = 0
    for (page in (start until count) + (0 until start)) {
        currentCoroutineContext().ensureActive()
        onProgress(PdfSearchStatus.Searching(page + 1, count))
        try {
            val matches = pages.search(page, query)
            if (matches.isNotEmpty()) found = true
            // Check while this page is cached, rather than recognizing the PDF a second time.
            if (pages.usesTextRecognition && !recognizedText) recognizedText = !pages.pageText(page).isNullOrBlank()
            onMatches(page, matches)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A damaged page must not end the search; the count is reported.
            unreadable++
        }
    }
    val result = when {
        found -> PdfSearchResult.Found
        count > 0 && unreadable == count -> PdfSearchResult.Failed
        unreadable == 0 && (if (pages.usesTextRecognition) !recognizedText else hasNoText(pages)) -> PdfSearchResult.NoText
        else -> PdfSearchResult.NoMatches
    }
    return PdfSearchStatus.Done(result, unreadable)
}

/** Stops at the first page with text; any doubt means the PDF is not called text-free. */
private suspend fun hasNoText(pages: PdfPages): Boolean {
    for (page in 0 until pages.pageCount) {
        try {
            if (pages.hasText(page)) return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }
    return true
}
