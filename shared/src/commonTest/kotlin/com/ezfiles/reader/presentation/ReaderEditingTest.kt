package com.ezfiles.reader.presentation

import com.ezfiles.reader.application.DocumentReadingService
import com.ezfiles.reader.domain.ContentBatch
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentReader
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ReadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEditingTest {
    private val ref = DocumentRef("test://notes.txt", "notes.txt")
    private val reader = VersionedReader()
    private val viewModel = ReaderViewModel(DocumentReadingService(reader))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun ready() = assertIs<ReaderUiState.Ready>(viewModel.state.value)

    private fun shownText() = (ready().pages.single().single() as ContentBlock.Paragraph).text

    @Test
    fun edit_is_offered_only_for_text_formats() {
        val editable = setOf(
            DocumentFormat.PlainText,
            DocumentFormat.Markdown,
            DocumentFormat.Csv,
            DocumentFormat.Json,
            DocumentFormat.SourceCode,
            DocumentFormat.Html,
        )
        for (format in DocumentFormat.entries) {
            reader.info = DocumentInfo("file", format, DocumentKind.Text, 3, null)
            viewModel.onAction(ReaderAction.DocumentPicked(ref))
            assertEquals(format in editable, ready().canEdit, "$format")
            viewModel.onAction(ReaderAction.OpenEditor)
            assertEquals(format in editable, ready().editing, "$format")
            viewModel.onAction(ReaderAction.CloseEditor(null))
        }
    }

    @Test
    fun closing_unsaved_returns_to_the_same_session_without_reopening() {
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenEditor)
        assertTrue(ready().editing)
        viewModel.onAction(ReaderAction.CloseEditor(null))
        assertFalse(ready().editing)
        assertEquals(1, reader.opens)
    }

    @Test
    fun opening_the_editor_closes_search() {
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenSearch)
        viewModel.onAction(ReaderAction.OpenEditor)
        assertNull(ready().search)
    }

    @Test
    fun a_save_reopens_the_document_so_the_reader_shows_the_new_content() {
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals("version 1", shownText())
        viewModel.onAction(ReaderAction.OpenEditor)
        val saved = ref.copy(sizeBytes = 99)
        viewModel.onAction(ReaderAction.CloseEditor(saved))
        assertEquals("version 2", shownText())
        assertFalse(ready().editing)
        assertEquals(saved, ready().ref)
    }

    @Test
    fun a_save_keeps_the_source_view_of_the_same_file_only() {
        reader.info = DocumentInfo("notes.md", DocumentFormat.Markdown, DocumentKind.Text, 3, null)
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.ToggleSource)
        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.CloseEditor(ref))
        assertTrue(ready().source)

        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.CloseEditor(DocumentRef("test://copy.md", "copy.md")))
        assertFalse(ready().source)
        assertEquals("test://copy.md", ready().ref.uri)
    }

    @Test
    fun a_file_handed_over_during_editing_waits_until_the_editor_closes() {
        val other = DocumentRef("test://other.txt", "other.txt")
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenEditor)

        viewModel.onAction(ReaderAction.DocumentPicked(other))
        assertTrue(ready().editing)
        assertEquals(ref, ready().ref)
        assertEquals(other, ready().pendingOpen)
        assertEquals(1, reader.opens)

        viewModel.onAction(ReaderAction.CloseEditor(null))
        assertFalse(ready().editing)
        assertEquals(other, ready().ref)
    }
}

/** Each open shows newer content, like a file that was saved in between. */
private class VersionedReader : DocumentReader {
    var info = DocumentInfo("notes.txt", DocumentFormat.PlainText, DocumentKind.Text, 3, "utf-8")
    var opens = 0

    override suspend fun open(ref: DocumentRef, limits: ReadLimits, asSource: Boolean, password: String?): DocumentSession {
        opens++
        val shown = info
        val text = "version $opens"
        return object : DocumentSession {
            private var served = false
            override val info = shown

            override suspend fun nextBatch(): ContentBatch? {
                if (served) return null
                served = true
                return ContentBatch(listOf(ContentBlock.Paragraph(text)), isLast = true, truncated = false)
            }

            override fun cancel() {}

            override fun close() {}
        }
    }
}
