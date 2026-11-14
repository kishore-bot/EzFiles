package com.ezfiles.reader.presentation

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.platform.PdfBox
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfTextMatch
import com.ezfiles.reader.platform.normalisedBox
import com.ezfiles.reader.presentation.viewers.PdfMatches
import com.ezfiles.reader.presentation.viewers.PdfSearchResult
import com.ezfiles.reader.presentation.viewers.PdfSearchStatus
import com.ezfiles.reader.presentation.viewers.message
import com.ezfiles.reader.presentation.viewers.searchPdf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun match(id: Float) = PdfTextMatch(listOf(PdfBox(id / 100, 0f, id / 100, 1f)))

private class FakePdfPages(
    override val pageCount: Int,
    private val matches: Map<Int, List<PdfTextMatch>> = emptyMap(),
    private val pagesWithText: Set<Int> = emptySet(),
    private val failing: Set<Int> = emptySet(),
    private val failHasText: Boolean = false,
    private val onSearch: (Int) -> Unit = {},
) : PdfPages {
    val searched = mutableListOf<Int>()
    val textChecked = mutableListOf<Int>()
    override val canSearch = true

    override suspend fun search(index: Int, query: String): List<PdfTextMatch> {
        searched += index
        onSearch(index)
        if (index in failing) throw IllegalStateException("damaged page")
        return matches[index].orEmpty()
    }

    override suspend fun hasText(index: Int): Boolean {
        textChecked += index
        if (failHasText) throw IllegalStateException("damaged page")
        return index in pagesWithText
    }

    override suspend fun aspectRatio(index: Int) = 1f
    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = null
    override suspend fun close() = Unit
}

class PdfMatchesTest {
    private fun hits(vararg perPage: Pair<Int, Int>): PdfMatches =
        perPage.fold(PdfMatches.None) { all, (page, count) ->
            all.withPage(page, List(count) { match(page * 10f + it) })
        }

    @Test
    fun emptyHasNoCurrentAndNavigationDoesNothing() {
        assertNull(PdfMatches.None.current)
        assertEquals(0, PdfMatches.None.count)
        assertEquals("", PdfMatches.None.label)
        assertSame(PdfMatches.None, PdfMatches.None.next())
        assertSame(PdfMatches.None, PdfMatches.None.previous())
    }

    @Test
    fun firstMatchBecomesCurrent() {
        val all = hits(4 to 2)
        assertEquals(4, all.current?.page)
        assertEquals("1 / 2", all.label)
    }

    @Test
    fun pageWithoutMatchesReturnsTheSameInstance() {
        val all = hits(1 to 1)
        assertSame(all, all.withPage(2, emptyList()))
    }

    @Test
    fun nextAndPreviousWrapAround() {
        val all = hits(0 to 1, 3 to 2)
        assertEquals("2 / 3", all.next().label)
        assertEquals("3 / 3", all.next().next().label)
        assertEquals("1 / 3", all.next().next().next().label)
        assertEquals("3 / 3", all.previous().label)
    }

    @Test
    fun nextFollowsDocumentOrderNotDiscoveryOrder() {
        var all = hits(5 to 1, 1 to 1, 3 to 1)
        val visited = List(3) { all.current!!.page.also { all = all.next() } }
        assertEquals(listOf(5, 1, 3), visited)
    }

    @Test
    fun earlierPagesKeepTheCurrentMatchAndShiftItsNumber() {
        val all = hits(5 to 1, 2 to 2)
        assertEquals(5, all.current?.page)
        assertEquals("3 / 3", all.label)
    }

    @Test
    fun laterPagesLeaveTheNumberAlone() {
        val all = hits(5 to 1, 9 to 4)
        assertEquals(5, all.current?.page)
        assertEquals("1 / 5", all.label)
    }

    @Test
    fun onPageGivesOnlyThatPagesMatches() {
        val all = hits(1 to 2, 2 to 1)
        assertEquals(2, all.onPage(1).size)
        assertEquals(listOf(2), all.onPage(2).map { it.page })
        assertTrue(all.onPage(3).isEmpty())
    }

    @Test
    fun matchesOfOnePageKeepTheirOrder() {
        val first = match(1f)
        val second = match(2f)
        val all = PdfMatches.None.withPage(0, listOf(first, second))
        assertEquals(first.boxes, all.current?.boxes)
        assertEquals(second.boxes, all.next().current?.boxes)
    }
}

class PdfSearchRunTest {
    private suspend fun search(pages: FakePdfPages, start: Int = 0): Triple<PdfSearchStatus.Done, PdfMatches, List<Int>> {
        val progress = mutableListOf<Int>()
        var all = PdfMatches.None
        val done = searchPdf(pages, "q", start, { progress += it.page }, { page, found -> all = all.withPage(page, found) })
        return Triple(done, all, progress)
    }

    @Test
    fun searchesFromTheStartPageThenWraps() = runTest {
        val pages = FakePdfPages(4)
        val (_, _, progress) = search(pages, start = 2)
        assertEquals(listOf(2, 3, 0, 1), pages.searched)
        assertEquals(listOf(3, 4, 1, 2), progress)
    }

    @Test
    fun outOfRangeStartIsClamped() = runTest {
        val pages = FakePdfPages(2)
        search(pages, start = 9)
        assertEquals(listOf(1, 0), pages.searched)
    }

    @Test
    fun firstMatchFromStartIsCurrentAndTheListStaysInDocumentOrder() = runTest {
        val (done, all) = search(FakePdfPages(5, mapOf(1 to listOf(match(1f)), 3 to listOf(match(3f)))), start = 2)
        assertEquals(PdfSearchResult.Found, done.result)
        assertEquals(3, all.current?.page)
        assertEquals("2 / 2", all.label)
    }

    @Test
    fun zeroMatchesWithATextLayerSaysNoMatchesAndStopsCheckingAtFirstText() = runTest {
        val pages = FakePdfPages(5, pagesWithText = setOf(1))
        val (done) = search(pages)
        assertEquals(PdfSearchResult.NoMatches, done.result)
        assertEquals(listOf(0, 1), pages.textChecked)
    }

    @Test
    fun zeroMatchesWithoutAnyTextSaysNoText() = runTest {
        val (done) = search(FakePdfPages(3))
        assertEquals(PdfSearchResult.NoText, done.result)
        assertEquals("No text found in this PDF", done.message())
    }

    @Test
    fun matchesSkipTheTextCheck() = runTest {
        val pages = FakePdfPages(2, mapOf(0 to listOf(match(1f))))
        search(pages)
        assertTrue(pages.textChecked.isEmpty())
    }

    @Test
    fun aFailedTextCheckIsNotClaimedAsNoText() = runTest {
        val (done) = search(FakePdfPages(2, failHasText = true))
        assertEquals(PdfSearchResult.NoMatches, done.result)
    }

    @Test
    fun aDamagedPageIsSkippedAndCounted() = runTest {
        val (done, all) = search(FakePdfPages(3, mapOf(2 to listOf(match(1f))), failing = setOf(0)))
        assertEquals(PdfSearchResult.Found, done.result)
        assertEquals(1, done.unreadablePages)
        assertEquals(1, all.count)
        assertEquals("Couldn't search 1 page", done.message())
    }

    @Test
    fun damagedPagesPreventTheNoTextClaim() = runTest {
        val (done) = search(FakePdfPages(3, failing = setOf(1)))
        assertEquals(PdfSearchResult.NoMatches, done.result)
        assertEquals("No matches, couldn't search 1 page", done.message())
    }

    @Test
    fun everyPageFailingIsAFailure() = runTest {
        val (done) = search(FakePdfPages(2, failing = setOf(0, 1)))
        assertEquals(PdfSearchResult.Failed, done.result)
    }

    @Test
    fun cancellationStopsBeforeTheNextPage() = runTest {
        lateinit var job: Job
        val pages = FakePdfPages(5, onSearch = { if (it == 1) job.cancel() })
        job = launch { searchPdf(pages, "q", 0, {}, { _, _ -> }) }
        job.join()
        assertEquals(listOf(0, 1), pages.searched)
    }

    @Test
    fun aCancellationThrownByThePlatformIsNotCountedAsDamage() = runTest {
        val pages = FakePdfPages(2, onSearch = { throw CancellationException("stop") })
        assertFailsWith<CancellationException> { searchPdf(pages, "q", 0, {}, { _, _ -> }) }
    }

    @Test
    fun statusMessages() {
        assertNull(PdfSearchStatus.Idle.message())
        assertEquals("Searching page 12 of 240…", PdfSearchStatus.Searching(12, 240).message())
        assertNull(PdfSearchStatus.Done(PdfSearchResult.Found).message())
        assertEquals("No matches", PdfSearchStatus.Done(PdfSearchResult.NoMatches).message())
        assertEquals(
            "No matches, couldn't search 2 pages",
            PdfSearchStatus.Done(PdfSearchResult.NoMatches, unreadablePages = 2).message(),
        )
    }
}

class PdfBoxTest {
    @Test
    fun scalesToFractionsOfThePage() {
        assertEquals(PdfBox(0.1f, 0.25f, 0.5f, 0.5f), normalisedBox(10f, 50f, 50f, 100f, 100f, 200f))
    }

    @Test
    fun ordersFlippedEdgesAndClampsToThePage() {
        assertEquals(PdfBox(0f, 0.5f, 1f, 1f), normalisedBox(150f, 300f, -5f, 100f, 100f, 200f))
    }

    @Test
    fun rejectsEmptyBoxesAndEmptyPages() {
        assertNull(normalisedBox(10f, 10f, 10f, 20f, 100f, 100f))
        assertNull(normalisedBox(0f, 0f, 10f, 10f, 0f, 100f))
    }
}
