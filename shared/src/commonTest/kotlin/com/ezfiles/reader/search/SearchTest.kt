package com.ezfiles.reader.search

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.data.DocumentAccess
import com.ezfiles.reader.data.EngineDocumentText
import com.ezfiles.reader.data.FullTextIndex
import com.ezfiles.reader.data.IndexEntry
import com.ezfiles.reader.data.IndexHit
import com.ezfiles.reader.data.LibraryContentSearch
import com.ezfiles.reader.data.PagedText
import com.ezfiles.reader.data.appendCollapsed
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.RecentDocument
import com.ezfiles.reader.domain.SubstringFileNameSearch
import com.ezfiles.reader.domain.byFrecency
import com.ezfiles.reader.platform.PdfOpener
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfPasswordRequired
import com.ezfiles.reader.platform.PdfTextMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 24L * 60 * 60 * 1000

/** A naive in-memory [FullTextIndex]: words are matched case-insensitively. */
private class FakeIndex : FullTextIndex {
    val docs = linkedMapOf<String, Pair<IndexEntry, String>>()
    var saves = 0
    var capacity = Int.MAX_VALUE

    override fun stale(entries: List<IndexEntry>) = entries.indices.filter { docs[entries[it].key]?.first != entries[it] }
    override fun retain(keys: List<String>) {
        docs.keys.retainAll(keys.toSet())
    }
    override fun hasRoom() = docs.size < capacity
    override fun add(entry: IndexEntry, text: String, pageStarts: List<Int>): Boolean {
        docs[entry.key] = entry to text
        return true
    }
    override fun query(query: String, limit: Int) = docs.values
        .filter { (_, text) -> text.contains(query.trim(), ignoreCase = true) }
        .take(limit)
        .map { (entry, text) ->
            val at = text.indexOf(query.trim(), ignoreCase = true)
            IndexHit(entry.key, text, listOf(at until at + query.trim().length), null)
        }
    override fun save() {
        saves++
    }
    override fun clear() = docs.clear()
}

private class FakeDevice(files: List<DocumentRef>) : DeviceDocuments {
    override val supportsFolders = true
    override val folders = MutableStateFlow(listOf(LibraryFolder("tree/a", "a")))
    override val files = MutableStateFlow(files)
    override val scanning = MutableStateFlow(false)
    override val limitReached = MutableStateFlow(false)
    override suspend fun refresh() = Unit
    override suspend fun addFolder(uri: String) = true
    override suspend fun removeFolder(uri: String) = Unit
}

private fun ref(name: String, size: Long = 10, modified: Long = 1) = DocumentRef("u/$name", name, size, modified, identity = name)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {

    @Test
    fun frecency_weighs_repeat_visits_and_decays_them() {
        val now = 100 * DAY
        val once = RecentDocument(ref("once.pdf"), null, now, listOf(now))
        // Opened five times last week: worth more than one visit today.
        val often = RecentDocument(ref("often.pdf"), null, now - 7 * DAY, List(5) { now - 7 * DAY - it })
        // Opened five times two months ago: worth less.
        val stale = RecentDocument(ref("stale.pdf"), null, now - 60 * DAY, List(5) { now - 60 * DAY - it })
        val ordered = listOf(once, often, stale).byFrecency().map { it.ref.displayName }
        assertEquals(listOf("often.pdf", "once.pdf", "stale.pdf"), ordered)
    }

    @Test
    fun frecency_with_single_visits_is_plain_recency() {
        val docs = (0 until 5).map { RecentDocument(ref("f$it.txt"), null, 1_000L - it) }
        assertEquals(docs, docs.byFrecency())
        assertEquals(emptyList(), emptyList<RecentDocument>().byFrecency())
    }

    @Test
    fun substring_name_search_keeps_order() {
        val matcher = SubstringFileNameSearch.prepare(listOf("Report.pdf", "notes.txt", "old report.docx"))
        assertEquals(listOf(0, 2), matcher.rank("REPORT"))
        assertEquals(listOf(0, 1, 2), matcher.rank(""))
    }

    @Test
    fun collapsed_page_text_respects_the_limit_and_surrogates() {
        val out = StringBuilder()
        appendCollapsed(out, "  Hello \n\t world  ", 100)
        assertEquals("Hello world", out.toString())
        val capped = StringBuilder()
        appendCollapsed(capped, "ab😀c", 3)
        assertEquals("ab", capped.toString())
    }

    private fun TestScope.search(device: DeviceDocuments, index: FakeIndex, read: suspend (DocumentRef, Int) -> PagedText?): LibraryContentSearch {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LibraryContentSearch(device, read, { index }, indexing = dispatcher, queries = dispatcher, startDelayMillis = 5_000, pauseMillis = 0)
    }

    @Test
    fun indexes_lazily_only_while_active_and_only_changed_files() = runTest {
        val device = FakeDevice(listOf(ref("a.txt"), ref("b.docx"), ref("photo.jpg")))
        val index = FakeIndex()
        val reads = mutableListOf<String>()
        val search = search(device, index) { ref, _ ->
            reads += ref.displayName
            PagedText("text of ${ref.displayName}", emptyList())
        }
        advanceUntilIdle()
        assertTrue(reads.isEmpty(), "nothing before the library shows")

        search.setActive(true)
        advanceTimeBy(4_000)
        assertTrue(reads.isEmpty(), "waits a moment after the library shows")
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "b.docx"), reads, "pictures are skipped")
        assertEquals(2, search.progress.value.indexed)
        assertFalse(search.progress.value.running)
        assertTrue(index.saves > 0)

        // Only the changed file is read again; a removed one is dropped.
        reads.clear()
        device.files.value = listOf(ref("a.txt", size = 11), ref("c.md"))
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "c.md"), reads)
        assertEquals(setOf("a.txt", "c.md"), index.docs.keys)

        val hits = search.search("text of c", 10)
        assertEquals(listOf("c.md"), hits.map { it.ref.displayName })
        assertEquals(listOf(0 until 9), hits.single().highlights)
    }

    @Test
    fun leaving_the_library_pauses_and_searching_starts_at_once() = runTest {
        val device = FakeDevice(listOf(ref("a.txt"), ref("b.txt")))
        val index = FakeIndex()
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val search = search(device, index) { ref, _ ->
            reads += ref.displayName
            gate.await()
            PagedText("x", emptyList())
        }
        search.setActive(true)
        // Searching skips the start delay.
        val first = async { search.search("x", 10) }
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf("a.txt"), reads)
        first.await()

        // Leaving cancels the file being read: it isn't recorded as empty.
        search.setActive(false)
        advanceUntilIdle()
        assertTrue(index.docs.isEmpty())
        assertFalse(search.progress.value.running)

        gate.complete(Unit)
        search.setActive(true)
        advanceUntilIdle()
        assertEquals(setOf("a.txt", "b.txt"), index.docs.keys)
    }

    @Test
    fun unreadable_files_are_recorded_so_they_are_not_retried() = runTest {
        val device = FakeDevice(listOf(ref("locked.pdf")))
        val index = FakeIndex()
        var reads = 0
        val search = search(device, index) { _, _ ->
            reads++
            null
        }
        search.setActive(true)
        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals("", index.docs["locked.pdf"]?.second)
        search.setActive(false)
        search.setActive(true)
        advanceUntilIdle()
        assertEquals(1, reads)
    }

    @Test
    fun clearing_deletes_and_waits_for_the_next_search() = runTest {
        val device = FakeDevice(listOf(ref("a.txt")))
        val index = FakeIndex()
        val search = search(device, index) { _, _ -> PagedText("hello", emptyList()) }
        search.setActive(true)
        advanceUntilIdle()
        assertEquals(1, index.docs.size)

        search.clear()
        advanceUntilIdle()
        assertTrue(index.docs.isEmpty())
        // Not rebuilt on its own, even when the library shows again.
        search.setActive(false)
        search.setActive(true)
        advanceUntilIdle()
        assertTrue(index.docs.isEmpty())

        val hits = async { search.search("hello", 5) }
        advanceUntilIdle()
        hits.await()
        assertEquals(1, index.docs.size)
    }

    @Test
    fun a_full_index_stops_indexing() = runTest {
        val device = FakeDevice(listOf(ref("a.txt"), ref("b.txt"), ref("c.txt")))
        val index = FakeIndex().apply { capacity = 2 }
        val search = search(device, index) { _, _ -> PagedText("t", emptyList()) }
        search.setActive(true)
        advanceUntilIdle()
        assertEquals(2, index.docs.size)
        assertFalse(search.progress.value.running)
    }

    private class FakePdf(private val texts: List<String?>, override val canSearch: Boolean = true) : PdfPages {
        var closed = false
        override val pageCount get() = texts.size
        override suspend fun aspectRatio(index: Int) = 1f
        override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = null
        override suspend fun search(index: Int, query: String) = emptyList<PdfTextMatch>()
        override suspend fun hasText(index: Int) = true
        override suspend fun pageText(index: Int) = texts[index]
        override suspend fun close() {
            closed = true
        }
    }

    private val noAccess = object : DocumentAccess {
        override fun openDescriptor(ref: DocumentRef, limits: com.ezfiles.reader.domain.ReadLimits): Int =
            throw DocumentException(DocumentError.FileAccess("gone"))
    }

    @Test
    fun pdf_text_comes_from_the_pages_with_their_starts() = runTest {
        val pdf = FakePdf(listOf("  First   page ", "", "Third\npage"))
        val text = EngineDocumentText(noAccess, PdfOpener { _, _ -> pdf }, StandardTestDispatcher(testScheduler))
        val read = text.read(ref("doc.PDF"), 1000)!!
        assertEquals("First page\n\nThird page", read.text)
        assertEquals(listOf(0, 11, 12), read.pageStarts)
        assertTrue(pdf.closed)
        assertEquals("First", text.plainText(ref("doc.pdf"), 5))
    }

    @Test
    fun locked_unsearchable_and_unreadable_files_give_null() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val locked = EngineDocumentText(noAccess, PdfOpener { _, _ -> throw PdfPasswordRequired(incorrect = false) }, io)
        assertNull(locked.plainText(ref("secret.pdf")))
        val scanned = FakePdf(listOf("x"), canSearch = false)
        assertNull(EngineDocumentText(noAccess, PdfOpener { _, _ -> scanned }, io).plainText(ref("scan.pdf")))
        assertTrue(scanned.closed)
        assertNull(EngineDocumentText(noAccess, PdfOpener { _, _ -> error("unused") }, io).plainText(ref("gone.docx")))
        assertNull(locked.plainText(ref("blank.pdf"), maxChars = 0))
    }
}
