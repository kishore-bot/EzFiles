package com.ezfiles.reader.library

import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.domain.ContentHit
import com.ezfiles.reader.domain.ContentSearch
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.FileNameMatcher
import com.ezfiles.reader.domain.FileNameSearch
import com.ezfiles.reader.domain.IndexProgress
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.presentation.library.FileFilter
import com.ezfiles.reader.presentation.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Storage : KeyValueStorage {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class Device(files: List<DocumentRef>) : DeviceDocuments {
    override val supportsFolders = true
    override val folders = MutableStateFlow(listOf(LibraryFolder("tree/a", "a")))
    override val files = MutableStateFlow(files)
    override val scanning = MutableStateFlow(false)
    override val limitReached = MutableStateFlow(false)
    override suspend fun refresh() = Unit
    override suspend fun addFolder(uri: String) = true
    override suspend fun removeFolder(uri: String) = Unit
}

private class FakeContent(val results: (String) -> List<ContentHit>) : ContentSearch {
    override val progress = MutableStateFlow(IndexProgress())
    var shown = false
    val asked = mutableListOf<String>()
    override fun setActive(active: Boolean) {
        shown = active
    }
    override suspend fun search(query: String, limit: Int): List<ContentHit> {
        asked += query
        return results(query)
    }
    override suspend fun clear() = Unit
}

/** Ranks names in reverse, to show the view model keeps the matcher's order. */
private object ReversedNames : FileNameSearch {
    override fun prepare(names: List<String>) = FileNameMatcher { q -> names.indices.filter { names[it].contains(q, true) }.reversed() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchTest {
    private val invoice = DocumentRef("u1", "Invoice_2025.pdf", identity = "i1")
    private val notes = DocumentRef("u2", "notes.txt", identity = "i2")
    private val budget = DocumentRef("u3", "budget.xlsx", identity = "i3")
    private val invoiceCopy = DocumentRef("u4", "invoice copy.pdf", identity = "i4")

    @Test
    fun typing_shows_file_names_then_inside_files() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val content = FakeContent { q ->
                if (q == "invoice") listOf(ContentHit(invoice, "Invoice total", listOf(0 until 7), 2), ContentHit(notes, "the invoice was paid", listOf(4 until 11), null))
                else emptyList()
            }
            val device = Device(listOf(invoice, notes, budget, invoiceCopy))
            val viewModel = LibraryViewModel(RecentDocumentsStore(Storage()) { 1L }, device, Dispatchers.Main, ReversedNames, content)
            val collector = launch { viewModel.state.collect {} }

            viewModel.onVisibleChange(true)
            assertTrue(content.shown)

            viewModel.onQueryChange("invoice")
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(listOf("invoice copy.pdf", "Invoice_2025.pdf"), state.items.map { it.ref.displayName })
            // Already listed by name, so only notes.txt shows under Inside files.
            assertEquals(listOf("notes.txt"), state.contentHits.map { it.item.ref.displayName })
            assertEquals(listOf(4 until 11), state.contentHits.single().highlights)
            assertNull(state.indexing)

            content.progress.value = IndexProgress(3, 10, running = true)
            advanceUntilIdle()
            assertEquals(IndexProgress(3, 10, running = true), viewModel.state.value.indexing)

            // A single letter doesn't search text; a cleared query shows no sections.
            viewModel.onQueryChange("i")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.contentHits.isEmpty())
            viewModel.onQueryChange("")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.contentHits.isEmpty())
            assertNull(viewModel.state.value.indexing)

            // Recent searches names only.
            viewModel.onFilterSelected(FileFilter.Recent)
            viewModel.onQueryChange("invoice")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.contentHits.isEmpty())
            assertTrue(!viewModel.state.value.searchesContent)

            viewModel.onVisibleChange(false)
            assertTrue(!content.shown)
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
