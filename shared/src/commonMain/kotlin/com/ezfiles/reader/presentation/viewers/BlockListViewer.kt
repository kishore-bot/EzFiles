package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.SearchMatch

/** What the list shows: single blocks, or runs of blocks drawn together. */
private sealed interface DisplayItem {
    val key: String

    /** The page's blocks this item draws. */
    val blocks: IntRange

    data class Single(override val key: String, override val blocks: IntRange, val block: ContentBlock) : DisplayItem

    data class Table(override val key: String, override val blocks: IntRange, val rows: List<ContentBlock.TableRow>) : DisplayItem

    data class CodePart(
        override val key: String,
        override val blocks: IntRange,
        val code: ContentBlock.Code,
        val joinTop: Boolean,
        val joinBottom: Boolean,
    ) : DisplayItem
}

/** Groups one batch: consecutive table rows become a table, source chunks join up. */
private fun group(pageIndex: Int, page: List<ContentBlock>): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    var index = 0
    while (index < page.size) {
        val block = page[index]
        val key = "$pageIndex:$index"
        val first = index
        when {
            block is ContentBlock.TableRow -> {
                val rows = mutableListOf(block)
                while (index + 1 < page.size && rows.size < MAX_ROWS_PER_TABLE_ITEM) {
                    val next = page[index + 1] as? ContentBlock.TableRow ?: break
                    rows += next
                    index++
                }
                items += DisplayItem.Table(key, first..index, rows)
            }
            block is ContentBlock.Code && block.firstLine != null -> {
                val previous = page.getOrNull(index - 1)
                val next = page.getOrNull(index + 1)
                val joinTop = (previous as? ContentBlock.Code)?.firstLine != null || (index == 0 && pageIndex > 0)
                val joinBottom = (next as? ContentBlock.Code)?.firstLine != null
                items += DisplayItem.CodePart(key, first..index, block, joinTop && block.firstLine > 1, joinBottom)
            }
            else -> items += DisplayItem.Single(key, first..index, block)
        }
        index++
    }
    return items
}

/** Long tables are split into cards of this many rows to keep scrolling smooth. */
private const val MAX_ROWS_PER_TABLE_ITEM = 200

/** Position of [match]'s item in the list, whose first item is the header. */
private fun listIndexOf(grouped: List<List<DisplayItem>>, match: SearchMatch): Int? {
    val items = grouped.getOrNull(match.page) ?: return null
    val before = (0 until match.page).sumOf { grouped[it].size }
    val index = items.indexOfFirst { match.block in it.blocks }
    return if (index < 0) null else before + index
}

/**
 * Virtualized viewer for flowing documents and archive listings: only visible
 * items are composed, and further batches are requested as the user nears
 * the end of what is loaded.
 */
@Composable
fun BlockListViewer(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit, onEntryTap: ((String) -> Unit)? = null) {
    val listState = rememberLazyListState()
    ListScrollControl(listState)
    LoadMoreWhenNearEnd(listState, state, onLoadMore)
    val readingText = !state.source && state.info.format == DocumentFormat.PlainText &&
        state.ref.displayName.substringAfterLast('.', "").lowercase() in setOf("txt", "text")
    val monospace = state.info.format == DocumentFormat.PlainText && !readingText
    // Pages only ever append, so each is grouped once.
    val cache = remember(state.ref, state.source) { mutableListOf<List<DisplayItem>>() }
    val grouped = state.pages.mapIndexed { i, page ->
        cache.getOrNull(i) ?: group(i, page).also { if (cache.size == i) cache += it }
    }

    val search = state.search
    val target = search?.currentMatch
    val reveal = remember { MatchReveal() }
    LaunchedEffect(target, search?.revealTick) {
        val match = target ?: return@LaunchedEffect
        val index = listIndexOf(grouped, match) ?: return@LaunchedEffect
        reveal.request(match)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
    }

    CompositionLocalProvider(LocalMatchReveal provides reveal) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = if (state.canSwitchSource) 76.dp else 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        grouped.forEachIndexed { pageIndex, items ->
            items(items.size, key = { items[it].key }) { i ->
                val item = items[i]
                // Equal for every item the change does not touch, so those skip recomposing.
                val highlights = search?.results?.highlights(pageIndex, item.blocks, target)
                val spacing = when (item) {
                    is DisplayItem.CodePart -> if (item.joinTop) 0.dp else 8.dp
                    else -> if (monospace) 0.dp else 8.dp
                }
                Box(Modifier.fillMaxWidth().padding(top = spacing), contentAlignment = Alignment.TopCenter) {
                    Box((if (readingText) Modifier.widthIn(max = 680.dp) else Modifier.readingWidth()).fillMaxWidth()) {
                        when (item) {
                            is DisplayItem.Single -> BlockView(item.block, monospace, highlights = highlights, onEntryTap = onEntryTap)
                            is DisplayItem.Table -> TableCard(item.rows, item.blocks.first, highlights)
                            is DisplayItem.CodePart -> CodeCard(item.code, item.joinTop, item.joinBottom, highlights)
                        }
                    }
                }
            }
        }
        item(key = "footer") { DocumentFooter(state, onRetry) }
    }
    }
}
