package com.ezfiles.reader.presentation

import androidx.compose.runtime.Immutable
import com.ezfiles.reader.domain.ContentBlock
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Most matches kept per search. Searching for "e" in a 16 MiB file would
 * otherwise hold over a million of these on top of the document itself.
 */
const val MAX_SEARCH_MATCHES = 10_000

/**
 * One occurrence of the query. [page] and [block] index `Ready.pages`, [part]
 * is the table column (0 for blocks with a single text), and [start] until
 * [end] are UTF-16 indices into that text, like engine spans.
 */
data class SearchMatch(val page: Int, val block: Int, val part: Int, val start: Int, val end: Int)

/** The matches one list item has to draw, and the current one if it is among them. */
@Immutable
data class BlockHighlights(val matches: List<SearchMatch>, val current: SearchMatch?) {
    private val byPart by lazy(LazyThreadSafetyMode.NONE) { matches.groupBy { it.block to it.part } }

    fun inPart(block: Int, part: Int): List<SearchMatch> = byPart[block to part].orEmpty()

    fun currentIn(block: Int, part: Int): SearchMatch? = current?.takeIf { it.block == block && it.part == part }
}

/**
 * Immutable, append-only results for one query. Pages are scanned once and
 * appended with [plus] as batches arrive, so entries for earlier pages keep
 * their identity and items showing them need not recompose.
 */
class SearchResults private constructor(
    val query: String,
    private val matchList: PersistentList<SearchMatch>,
    private val byPage: PersistentList<Map<Int, List<SearchMatch>>>,
    /** Stopped at the match limit; later pages were not scanned. */
    val capped: Boolean,
    private val limit: Int,
) {
    constructor(query: String, limit: Int = MAX_SEARCH_MATCHES) :
        this(query, persistentListOf(), persistentListOf(), false, limit)

    val matches: List<SearchMatch> get() = matchList
    val pagesSearched: Int get() = byPage.size

    /**
     * Scans the pages of [pages] not searched yet. [checkpoint] runs before
     * each block, so a caller can abort by throwing from it.
     */
    fun plus(pages: List<List<ContentBlock>>, checkpoint: () -> Unit = {}): SearchResults {
        if (query.isEmpty() || capped || pages.size <= pagesSearched) return this
        val found = ArrayList<SearchMatch>()
        val scanned = ArrayList<Map<Int, List<SearchMatch>>>()
        var room = limit - matchList.size
        for (pageIndex in pagesSearched until pages.size) {
            val pageStart = found.size
            pages[pageIndex].forEachIndexed { blockIndex, block ->
                checkpoint()
                block.forEachSearchablePart { part, text ->
                    room -= scan(text, query, room) { start, end ->
                        found += SearchMatch(pageIndex, blockIndex, part, start, end)
                    }
                }
            }
            scanned += found.subList(pageStart, found.size).groupBy { it.block }
            if (room <= 0) break
        }
        return SearchResults(query, matchList.addAll(found), byPage.addAll(scanned), room <= 0, limit)
    }

    /** What the item showing [blocks] of [page] should highlight, or `null` if nothing. */
    fun highlights(page: Int, blocks: IntRange, current: SearchMatch?): BlockHighlights? {
        val byBlock = byPage.getOrNull(page) ?: return null
        if (byBlock.isEmpty()) return null
        val hits = if (blocks.first == blocks.last) byBlock[blocks.first].orEmpty() else blocks.flatMap { byBlock[it].orEmpty() }
        if (hits.isEmpty()) return null
        return BlockHighlights(hits, current?.takeIf { it.page == page && it.block in blocks })
    }
}

/**
 * Finds up to [room] non-overlapping matches and returns how many. Kotlin's
 * case folding compares char by char, so offsets stay valid in the original
 * text; lowercasing the text first would not (`İ` changes length).
 */
private inline fun scan(text: String, query: String, room: Int, onMatch: (start: Int, end: Int) -> Unit): Int {
    var count = 0
    var from = 0
    while (count < room) {
        val start = text.indexOf(query, from, ignoreCase = true)
        if (start < 0) break
        from = start + query.length
        onMatch(start, from)
        count++
    }
    return count
}

/** The visible text of a block. Slide shapes and archive entries are not searched. */
private inline fun ContentBlock.forEachSearchablePart(action: (part: Int, text: String) -> Unit) {
    when (this) {
        is ContentBlock.Heading -> action(0, text)
        is ContentBlock.Paragraph -> action(0, text)
        is ContentBlock.ListItem -> action(0, text)
        is ContentBlock.Quote -> action(0, text)
        is ContentBlock.Code -> action(0, text)
        is ContentBlock.Section -> action(0, title)
        is ContentBlock.TableRow -> cells.forEachIndexed { column, cell -> action(column, cell) }
        else -> {}
    }
}
