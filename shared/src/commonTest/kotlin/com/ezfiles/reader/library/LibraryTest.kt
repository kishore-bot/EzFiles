package com.ezfiles.reader.library

import com.ezfiles.reader.data.ChosenFolderDocuments
import com.ezfiles.reader.data.DocumentTrees
import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.data.TreeEntry
import com.ezfiles.reader.data.TreeLimits
import com.ezfiles.reader.data.TreeStatus
import com.ezfiles.reader.data.walkDocumentTree
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.presentation.library.FileFilter
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.library.LibraryViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStorage : KeyValueStorage {
    val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class FakeDevice(
    files: List<DocumentRef>,
    folders: List<LibraryFolder>,
    override val supportsFolders: Boolean = true,
) : DeviceDocuments {
    override val folders = MutableStateFlow(folders)
    override val files = MutableStateFlow(files)
    override val scanning = MutableStateFlow(false)
    override val limitReached = MutableStateFlow(false)
    var acceptsFolders = true

    override suspend fun refresh() = Unit

    override suspend fun addFolder(uri: String): Boolean {
        if (acceptsFolders) folders.value += LibraryFolder(uri, uri)
        return acceptsFolders
    }

    override suspend fun removeFolder(uri: String) {
        folders.value = folders.value.filterNot { it.uri == uri }
    }
}

/** Folders as maps from directory id to its rows. */
private class FakeTrees : DocumentTrees {
    val contents = mutableMapOf<String, List<TreeEntry>>()
    val statuses = mutableMapOf<String, TreeStatus>()
    val granted = mutableSetOf<String>()
    var refuseGrants = false
    var rowsRead = 0
    var onRow: () -> Unit = {}

    override fun take(uri: String): LibraryFolder? {
        if (refuseGrants) return null
        granted += uri
        return LibraryFolder(uri, uri.substringAfterLast('/'))
    }

    override fun release(uri: String) {
        granted -= uri
    }

    override fun status(uri: String) = statuses[uri] ?: TreeStatus.Readable(uri)

    override fun forEachChild(uri: String, parentId: String, onEntry: (TreeEntry) -> Boolean) {
        for (entry in contents[parentId].orEmpty()) {
            rowsRead++
            onRow()
            if (!onEntry(entry)) break
        }
    }
}

private fun file(name: String, size: Long? = 10, modified: Long? = null, folder: String = "") =
    TreeEntry(id = "$folder/$name", uri = "$folder/document/$name", name = name, isDirectory = false, size = size, modifiedAt = modified)

private fun directory(id: String) = TreeEntry(id = id, uri = id, name = id.substringAfterLast('/'), isDirectory = true)

private val folderA = LibraryFolder("tree/a", "a")

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTest {
    private var clock = 1_000L
    private val storage = MemoryStorage()
    private fun store() = RecentDocumentsStore(storage) { clock++ }

    private fun TestScope.chosen(trees: FakeTrees, limits: TreeLimits = TreeLimits()) =
        ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), limits)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun recents_are_newest_first_deduplicated_and_persisted() = runTest {
        val recents = store()
        recents.record(DocumentRef("content://a", "a.pdf", sizeBytes = 10), DocumentFormat.Pdf)
        recents.record(DocumentRef("content://b", "b.docx"), DocumentFormat.Docx)
        recents.record(DocumentRef("content://a", "a.pdf", sizeBytes = 12), DocumentFormat.Pdf)

        assertEquals(listOf("a.pdf", "b.docx"), recents.documents.value.map { it.ref.displayName })
        assertEquals(12, recents.documents.value.first().ref.sizeBytes)

        val reloaded = store().documents.value
        assertEquals(recents.documents.value, reloaded)
    }

    @Test
    fun images_appear_under_their_own_category_only() = runTest {
        val files = listOf(DocumentRef("u1", "photo.jpg"), DocumentRef("u2", "report.pdf"))
        val viewModel = LibraryViewModel(store(), FakeDevice(files, listOf(folderA)), Dispatchers.Main)
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        assertEquals(listOf("report.pdf"), viewModel.state.value.items.map { it.ref.displayName })
        viewModel.onFilterSelected(FileFilter.Images)
        assertEquals(listOf("photo.jpg"), viewModel.state.value.items.map { it.ref.displayName })
        collector.cancel()
    }

    @Test
    fun recents_are_capped_and_survive_corrupt_storage() = runTest {
        val recents = store()
        repeat(60) { recents.record(DocumentRef("u$it", "f$it.txt"), DocumentFormat.PlainText) }
        assertEquals(50, recents.documents.value.size)
        assertEquals("f59.txt", recents.documents.value.first().ref.displayName)

        storage.values["recent_documents_v1"] = "{not json"
        assertTrue(store().documents.value.isEmpty())
    }

    @Test
    fun file_types_follow_extension_before_format() {
        assertEquals(FileType.Log, FileType.of("server.log", DocumentFormat.PlainText))
        assertEquals(FileType.Csv, FileType.of("customers.CSV", DocumentFormat.PlainText))
        assertEquals(FileType.Docx, FileType.of("download", DocumentFormat.Docx))
        assertEquals(FileType.Doc, FileType.of("old.DOC", DocumentFormat.Doc))
        assertEquals(FileType.Xls, FileType.of("download", DocumentFormat.Xls))
        assertEquals(FileType.Ppt, FileType.of("talk.pps", null))
        assertEquals(FileType.Other, FileType.of("mystery", null))
    }

    @Test
    fun library_filters_and_searches() = runTest {
        val recents = store()
        recents.record(DocumentRef("1", "Annual_Report.pdf"), DocumentFormat.Pdf)
        recents.record(DocumentRef("2", "Budget.xlsx"), DocumentFormat.Xlsx)
        recents.record(DocumentRef("3", "customers.csv"), DocumentFormat.PlainText)
        recents.record(DocumentRef("4", "notes.txt"), DocumentFormat.PlainText)
        val viewModel = LibraryViewModel(recents, FakeDevice(emptyList(), emptyList(), supportsFolders = false), Dispatchers.Main)

        viewModel.onFilterSelected(FileFilter.Sheets)
        assertEquals(listOf("customers.csv", "Budget.xlsx"), viewModel.state.value.items.map { it.ref.displayName })

        viewModel.onFilterSelected(FileFilter.All)
        viewModel.onQueryChange("REPORT")
        assertEquals(listOf("Annual_Report.pdf"), viewModel.state.value.items.map { it.ref.displayName })

        viewModel.onQueryChange("zzz")
        assertTrue(viewModel.state.value.items.isEmpty())
        assertEquals(false, viewModel.state.value.libraryEmpty)

        viewModel.clearAll()
        assertTrue(viewModel.state.value.libraryEmpty)
    }

    @Test
    fun folder_files_fill_categories_and_history_shows_under_recent() = runTest {
        val recents = store()
        recents.record(DocumentRef("r1", "notes.md"), DocumentFormat.Markdown)
        val device = FakeDevice(
            listOf(DocumentRef("d1", "deck.pptx"), DocumentRef("d2", "thesis.pdf"), DocumentRef("d3", "budget.xlsx")),
            listOf(folderA),
        )
        val viewModel = LibraryViewModel(recents, device, Dispatchers.Main)

        val all = viewModel.state.value
        assertEquals(listOf("deck.pptx", "thesis.pdf", "budget.xlsx"), all.items.map { it.ref.displayName })
        assertEquals(listOf("notes.md"), all.recents.map { it.ref.displayName })
        assertFalse(all.showsHistory)

        viewModel.onFilterSelected(FileFilter.Slides)
        assertEquals(listOf("deck.pptx"), viewModel.state.value.items.map { it.ref.displayName })

        viewModel.onFilterSelected(FileFilter.Recent)
        assertEquals(listOf("notes.md"), viewModel.state.value.items.map { it.ref.displayName })
    }

    @Test
    fun without_folders_home_offers_folders_and_shows_history_only_in_the_strip() = runTest {
        val recents = store()
        recents.record(DocumentRef("r1", "photo.png"), DocumentFormat.Image)
        recents.record(DocumentRef("r2", "notes.md"), DocumentFormat.Markdown)
        val viewModel = LibraryViewModel(recents, FakeDevice(emptyList(), emptyList()), Dispatchers.Main)

        val home = viewModel.state.value
        assertTrue(home.showsFolderControls)
        assertTrue(home.folders.isEmpty())
        assertEquals(listOf("notes.md", "photo.png"), home.recents.map { it.ref.displayName })
        assertTrue(home.items.isEmpty())

        viewModel.onFilterSelected(FileFilter.Images)
        assertEquals(listOf("photo.png"), viewModel.state.value.items.map { it.ref.displayName })
        assertTrue(viewModel.state.value.showsHistory)

        viewModel.onFilterSelected(FileFilter.Recent)
        assertFalse(viewModel.state.value.showsFolderControls)
    }

    @Test
    fun a_refused_folder_is_reported_until_seen() = runTest {
        val device = FakeDevice(emptyList(), emptyList()).apply { acceptsFolders = false }
        val viewModel = LibraryViewModel(store(), device, Dispatchers.Main)

        viewModel.addFolder("tree/a")
        assertTrue(viewModel.state.value.addFolderFailed)
        assertTrue(viewModel.state.value.folders.isEmpty())

        viewModel.onAddFolderFailureSeen()
        assertFalse(viewModel.state.value.addFolderFailed)
    }

    @Test
    fun removing_a_folder_forgets_recents_opened_from_it() = runTest {
        val recents = store()
        recents.record(DocumentRef("tree/a/document/in.pdf", "in.pdf"), DocumentFormat.Pdf)
        recents.record(DocumentRef("tree/ab/document/near.pdf", "near.pdf"), DocumentFormat.Pdf)
        recents.record(DocumentRef("content://picked", "picked.pdf"), DocumentFormat.Pdf)
        val device = FakeDevice(emptyList(), listOf(folderA, LibraryFolder("tree/ab", "ab")))
        val viewModel = LibraryViewModel(recents, device, Dispatchers.Main)

        viewModel.removeFolder("tree/a")

        assertEquals(listOf("picked.pdf", "near.pdf"), recents.documents.value.map { it.ref.displayName })
    }

    @Test
    fun walk_finds_supported_files_and_skips_the_rest() = runTest {
        val trees = FakeTrees()
        trees.contents["root"] = listOf(
            file("A.PDF"),
            file("photo.jpg"),
            file("tool.exe"),
            file("noextension"),
            file("empty.pdf", size = 0),
            file(".secret.pdf"),
            directory(".cache"),
            directory("sub"),
        )
        trees.contents[".cache"] = listOf(file("hidden.pdf"))
        trees.contents["sub"] = listOf(file("deep.docx", size = null))

        val walk = walkDocumentTree(trees, "tree/a", "root")

        assertEquals(listOf("A.PDF", "photo.jpg", "deep.docx"), walk.refs.map { it.displayName })
        assertEquals("/document/A.PDF", walk.refs[0].identity)
        assertEquals("/document/deep.docx", walk.refs[2].identity)
        assertFalse(walk.limitReached)
    }

    @Test
    fun walk_is_bounded_by_depth_and_survives_cycles() = runTest {
        val trees = FakeTrees()
        trees.contents["root"] = listOf(directory("one"), file("top.pdf"))
        trees.contents["one"] = listOf(directory("two"), directory("root"), file("mid.pdf"))
        trees.contents["two"] = listOf(file("deep.pdf"))

        val shallow = walkDocumentTree(trees, "tree/a", "root", TreeLimits(maxDepth = 1))
        assertEquals(listOf("top.pdf", "mid.pdf"), shallow.refs.map { it.displayName })
        assertTrue(shallow.limitReached)

        val full = walkDocumentTree(trees, "tree/a", "root")
        assertEquals(listOf("top.pdf", "mid.pdf", "deep.pdf"), full.refs.map { it.displayName })
        assertFalse(full.limitReached)
    }

    @Test
    fun walk_caps_documents_images_and_rows_separately() = runTest {
        val trees = FakeTrees()
        trees.contents["root"] = List(5) { file("d$it.pdf") } + List(5) { file("i$it.png") }

        val walk = walkDocumentTree(trees, "tree/a", "root", TreeLimits(maxDocuments = 2, maxImages = 3))
        assertEquals(listOf("d0.pdf", "d1.pdf", "i0.png", "i1.png", "i2.png"), walk.refs.map { it.displayName })
        assertTrue(walk.limitReached)

        trees.rowsRead = 0
        val rows = walkDocumentTree(trees, "tree/a", "root", TreeLimits(maxEntries = 3))
        assertEquals(3, rows.refs.size)
        assertEquals(4, trees.rowsRead)
        assertTrue(rows.limitReached)
    }

    @Test
    fun walk_stops_when_cancelled() = runTest {
        val trees = FakeTrees()
        trees.contents["root"] = List(100) { file("f$it.pdf") }
        var job: Job? = null
        trees.onRow = { if (trees.rowsRead == 5) job?.cancel() }

        job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) { walkDocumentTree(trees, "tree/a", "root") }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(5, trees.rowsRead)
    }

    @Test
    fun folders_merge_by_identity_newest_first_and_persist() = runTest {
        val trees = FakeTrees()
        trees.contents["tree/a"] = listOf(file("old.pdf", modified = 1, folder = "a"), file("same.pdf", modified = 5, folder = "a"))
        trees.contents["tree/b"] = listOf(file("same.pdf", modified = 5, folder = "b"), file("new.pdf", modified = 9, folder = "b"))
        val documents = chosen(trees)

        assertTrue(documents.addFolder("tree/a"))
        assertTrue(documents.addFolder("tree/b"))
        assertTrue(documents.addFolder("tree/b"))

        assertEquals(listOf("tree/a", "tree/b"), documents.folders.value.map { it.uri })
        assertEquals(listOf("new.pdf", "same.pdf", "same.pdf", "old.pdf"), documents.files.value.map { it.displayName })
        assertEquals(2, documents.files.value.filter { it.displayName == "same.pdf" }.map { it.identity }.distinct().size)
        assertFalse(documents.scanning.value)

        val reloaded = chosen(trees)
        assertEquals(listOf("tree/a", "tree/b"), reloaded.folders.value.map { it.uri })
        assertTrue(reloaded.scanning.value)
        assertTrue(reloaded.files.value.isEmpty())
        reloaded.refresh()
        assertEquals(4, reloaded.files.value.size)
    }

    @Test
    fun the_last_scan_shows_at_once_on_the_next_launch_until_a_new_scan_replaces_it() = runTest {
        val trees = FakeTrees()
        val cache = MemoryStorage()
        trees.contents["tree/a"] = listOf(file("one.pdf", modified = 1, folder = "a"), file("two.docx", modified = 2, folder = "a"))
        val first = ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache)
        first.addFolder("tree/a")
        assertEquals(listOf("two.docx", "one.pdf"), first.files.value.map { it.displayName })

        // Next launch: the cached list is there before any scan, sizes and dates included.
        val next = ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache)
        assertEquals(first.files.value, next.files.value)
        assertTrue(next.scanning.value)

        // The fresh scan wins, and is cached in turn.
        trees.contents["tree/a"] = listOf(file("three.pdf", modified = 3, folder = "a"))
        next.refresh()
        assertEquals(listOf("three.pdf"), next.files.value.map { it.displayName })
        val after = ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache)
        assertEquals(listOf("three.pdf"), after.files.value.map { it.displayName })

        // A removed folder's cached files don't come back, and a damaged cache is ignored.
        after.removeFolder("tree/a")
        assertTrue(ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache).files.value.isEmpty())
        cache.put("library_scan_v2", "{broken")
        assertTrue(ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache).files.value.isEmpty())
    }

    @Test
    fun libraryCacheDecodingDoesNotRunOnTheConstructingThread() = runTest {
        val trees = FakeTrees()
        val cache = MemoryStorage()
        trees.contents["tree/a"] = listOf(file("cached.pdf", folder = "a"))
        val first = ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache)
        first.addFolder("tree/a")
        var reads = 0
        val measured = object : KeyValueStorage {
            override fun get(key: String): String? { reads++; return cache.get(key) }
            override fun put(key: String, value: String?) = cache.put(key, value)
        }
        val next = ChosenFolderDocuments(storage, trees, StandardTestDispatcher(testScheduler), scanCache = measured)
        assertEquals(0, reads)
        assertFalse(next.initialMetadataReady.value)
        assertTrue(next.files.value.isEmpty())
        runCurrent()
        assertEquals(1, reads)
        assertTrue(next.initialMetadataReady.value)
        assertEquals(first.files.value, next.files.value)
    }

    @Test
    fun aDelayedCacheCannotResurrectAnUnavailableFoldersFiles() = runTest {
        val trees = FakeTrees()
        val cache = MemoryStorage()
        trees.contents["tree/a"] = listOf(file("cached.pdf", folder = "a"))
        ChosenFolderDocuments(storage, trees, UnconfinedTestDispatcher(testScheduler), scanCache = cache).addFolder("tree/a")
        trees.statuses["tree/a"] = TreeStatus.Unavailable
        lateinit var next: ChosenFolderDocuments
        val delayed = object : KeyValueStorage {
            override fun get(key: String): String? {
                val value = cache.get(key)
                this@runTest.launch(start = CoroutineStart.UNDISPATCHED) { next.refresh() }
                return value
            }
            override fun put(key: String, value: String?) = cache.put(key, value)
        }
        next = ChosenFolderDocuments(storage, trees, StandardTestDispatcher(testScheduler), scanCache = delayed)
        runCurrent()
        assertTrue(next.initialMetadataReady.value)
        assertTrue(next.files.value.isEmpty())
        assertFalse(next.folders.value.single().available)
    }

    @Test
    fun aLibraryWithoutChosenFoldersDoesNotReadAnIrrelevantCache() = runTest {
        var reads = 0
        val cache = object : KeyValueStorage {
            override fun get(key: String): String? { reads++; return null }
            override fun put(key: String, value: String?) = Unit
        }
        val next = ChosenFolderDocuments(MemoryStorage(), FakeTrees(), StandardTestDispatcher(testScheduler), scanCache = cache)
        runCurrent()
        assertTrue(next.initialMetadataReady.value)
        assertEquals(0, reads)
    }

    @Test
    fun removing_a_folder_releases_its_grant_and_brings_back_a_shared_copy() = runTest {
        val trees = FakeTrees()
        trees.contents["tree/a"] = listOf(file("same.pdf", folder = "a"), file("only-a.pdf", folder = "a"))
        trees.contents["tree/b"] = listOf(file("same.pdf", folder = "b"))
        val documents = chosen(trees)
        documents.addFolder("tree/a")
        documents.addFolder("tree/b")
        assertEquals(setOf("tree/a", "tree/b"), trees.granted)

        documents.removeFolder("tree/a")

        assertEquals(setOf("tree/b"), trees.granted)
        assertEquals(listOf("b/document/same.pdf"), documents.files.value.map { it.uri })
        assertEquals(listOf("tree/b"), chosen(trees).folders.value.map { it.uri })
    }

    @Test
    fun lost_folders_drop_quietly_and_unavailable_ones_stay() = runTest {
        val trees = FakeTrees()
        trees.contents["tree/a"] = listOf(file("a.pdf", folder = "a"))
        trees.contents["tree/b"] = listOf(file("b.pdf", folder = "b"))
        val documents = chosen(trees)
        documents.addFolder("tree/a")
        documents.addFolder("tree/b")

        trees.statuses["tree/a"] = TreeStatus.Lost
        trees.statuses["tree/b"] = TreeStatus.Unavailable
        documents.refresh()

        assertEquals(listOf(LibraryFolder("tree/b", "b", available = false)), documents.folders.value)
        assertTrue(documents.files.value.isEmpty())
        assertEquals(setOf("tree/b"), trees.granted)
        assertEquals(listOf("tree/b"), chosen(trees).folders.value.map { it.uri })

        trees.statuses.remove("tree/b")
        documents.refresh()
        assertTrue(documents.folders.value.single().available)
        assertEquals(listOf("b.pdf"), documents.files.value.map { it.displayName })
    }

    @Test
    fun a_refused_grant_adds_nothing() = runTest {
        val trees = FakeTrees().apply { refuseGrants = true }
        val documents = chosen(trees)

        assertFalse(documents.addFolder("tree/a"))

        assertTrue(documents.folders.value.isEmpty())
        assertNull(storage.values["library_folders_v1"])
    }

    @Test
    fun folders_share_the_limits_and_corrupt_storage_starts_empty() = runTest {
        val trees = FakeTrees()
        trees.contents["tree/a"] = List(4) { file("a$it.pdf", folder = "a") }
        trees.contents["tree/b"] = List(4) { file("b$it.pdf", folder = "b") }
        val documents = chosen(trees, TreeLimits(maxDocuments = 4))
        documents.addFolder("tree/a")
        assertFalse(documents.limitReached.value)
        documents.addFolder("tree/b")

        assertEquals(listOf("a0.pdf", "a1.pdf", "b0.pdf", "b1.pdf"), documents.files.value.map { it.displayName })
        assertTrue(documents.limitReached.value)

        storage.values["library_folders_v1"] = "{not json"
        assertTrue(chosen(trees).folders.value.isEmpty())
    }
}
