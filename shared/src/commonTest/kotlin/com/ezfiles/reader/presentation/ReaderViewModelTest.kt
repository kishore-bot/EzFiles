package com.ezfiles.reader.presentation

import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.application.DocumentReadingService
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.ContentBatch
import com.ezfiles.reader.presentation.bookmarks.lostBookmark
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentReader
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.presentation.viewers.MAX_HTML_PREVIEW_BYTES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val ref = DocumentRef("test://notes.txt", "notes.txt")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun picking_then_dismissing_restores_the_previous_state() {
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader()))
        viewModel.onAction(ReaderAction.PickDocument)
        assertEquals(ReaderUiState.SelectingFile(ReaderUiState.Idle), viewModel.state.value)
        viewModel.onAction(ReaderAction.PickerDismissed)
        assertEquals(ReaderUiState.Idle, viewModel.state.value)
    }

    @Test
    fun audio_and_video_go_to_the_player_without_the_engine() = runTest {
        val reader = FakeReader()
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        val clip = DocumentRef("test://clip.MP4", "clip.MP4")
        viewModel.onAction(ReaderAction.DocumentPicked(clip))
        assertEquals(ReaderUiState.Playing(clip, MediaKind.Video), viewModel.state.value)
        assertTrue(reader.refs.isEmpty())
        viewModel.onAction(ReaderAction.CloseDocument)
        assertEquals(ReaderUiState.Idle, viewModel.state.value)
    }

    @Test
    fun opens_a_document_and_pages_through_it() = runTest {
        val session = FakeSession(batches = listOf(batch("a", last = false), batch("b", last = true)))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        val first = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals<List<List<ContentBlock>>>(listOf(listOf(ContentBlock.Paragraph("a"))), first.pages)
        assertEquals(false, first.endReached)

        viewModel.onAction(ReaderAction.LoadMore)
        val second = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(2, second.pages.size)
        assertTrue(second.endReached)

        viewModel.onAction(ReaderAction.LoadMore) // no-op at the end
        assertEquals(2, session.batchesServed)
    }

    @Test
    fun open_failures_become_failed_state() = runTest {
        val error = DocumentError.Unsupported("Pdf documents are not supported yet")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { throw DocumentException(error) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals(ReaderUiState.Failed("notes.txt", error), viewModel.state.value)
    }

    @Test
    fun load_more_failures_keep_content_and_allow_retry() = runTest {
        val session = FakeSession(batches = listOf(batch("a", last = false)), failAfterBatches = true)
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))

        viewModel.onAction(ReaderAction.LoadMore)
        val failed = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(1, failed.pages.size)
        assertIs<DocumentError.Malformed>(failed.loadMoreError)

        session.failAfterBatches = false
        session.batches += batch("b", last = true)
        viewModel.onAction(ReaderAction.RetryLoadMore)
        assertEquals(2, assertIs<ReaderUiState.Ready>(viewModel.state.value).pages.size)
    }

    @Test
    fun cancelling_while_opening_releases_the_session() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeSession(batches = listOf(batch("a", last = true)))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }, ReadLimits.Default))
        session.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertIs<ReaderUiState.Reading>(viewModel.state.value)
        viewModel.onAction(ReaderAction.Cancel)

        assertEquals(ReaderUiState.Cancelled("notes.txt"), viewModel.state.value)
        assertTrue(session.cancelled)
        assertTrue(session.closed)
    }

    @Test
    fun opening_another_document_closes_the_previous_one() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val viewModel = ReaderViewModel(
            DocumentReadingService(FakeReader { FakeSession(listOf(batch("x", last = true))).also(sessions::add) }),
        )
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.DocumentPicked(ref.copy(uri = "test://other.txt")))
        assertEquals(listOf(true, false), sessions.map { it.closed })
    }

    @Test
    fun closing_returns_to_the_library_and_releases_the_session() = runTest {
        val session = FakeSession(listOf(batch("a", last = true)))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.CloseDocument)
        assertEquals(ReaderUiState.Idle, viewModel.state.value)
        assertTrue(session.closed)
    }

    @Test
    fun switches_markdown_between_preview_and_source() = runTest {
        val markdown = DocumentInfo("notes.md", DocumentFormat.Markdown, DocumentKind.Text, 3, "utf-8")
        val plain = DocumentInfo("notes.md", DocumentFormat.PlainText, DocumentKind.Text, 3, "utf-8")
        lateinit var reader: FakeReader
        reader = FakeReader { FakeSession(emptyList(), info = if (reader.lastAsSource) plain else markdown) }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertTrue(assertIs<ReaderUiState.Ready>(viewModel.state.value).canSwitchSource)

        viewModel.onAction(ReaderAction.ToggleSource)
        val source = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(source.source)
        assertTrue(source.canSwitchSource)
        assertTrue(reader.lastAsSource)

        viewModel.onAction(ReaderAction.ToggleSource)
        assertEquals(false, assertIs<ReaderUiState.Ready>(viewModel.state.value).source)
        assertEquals(false, reader.lastAsSource)
    }

    @Test
    fun html_opens_rendered_and_switches_to_its_code() = runTest {
        val html = DocumentInfo("page.html", DocumentFormat.Html, DocumentKind.Text, 3, "utf-8")
        val code = DocumentInfo("page.html", DocumentFormat.SourceCode, DocumentKind.Text, 3, "utf-8")
        lateinit var reader: FakeReader
        reader = FakeReader { FakeSession(emptyList(), info = if (reader.lastAsSource) code else html) }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        val preview = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(preview.canPreviewHtml && preview.canSwitchSource && preview.canEdit && preview.canSearch)
        assertEquals(false, preview.source)

        viewModel.onAction(ReaderAction.ToggleSource)
        val source = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(source.source && source.canSwitchSource && source.canEdit && source.canSearch)
        assertEquals(false, source.canPreviewHtml)

        viewModel.onAction(ReaderAction.ToggleSource)
        assertTrue(assertIs<ReaderUiState.Ready>(viewModel.state.value).canPreviewHtml)
    }

    @Test
    fun html_over_the_preview_limit_has_no_preview_to_switch_to() = runTest {
        val big = DocumentInfo("big.html", DocumentFormat.Html, DocumentKind.Text, MAX_HTML_PREVIEW_BYTES + 1, "utf-8")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { FakeSession(emptyList(), info = big) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        val ready = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(false, ready.canPreviewHtml)
        assertEquals(false, ready.canSwitchSource)
        assertTrue(ready.canEdit)
    }

    @Test
    fun every_open_is_a_new_page_even_of_the_same_file() = runTest {
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader()))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        val first = assertIs<ReaderUiState.Ready>(viewModel.state.value).openId
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertTrue(assertIs<ReaderUiState.Ready>(viewModel.state.value).openId != first)
    }

    @Test
    fun a_viewer_searching_its_own_page_fills_the_search_bar() = runTest {
        val html = DocumentInfo("page.html", DocumentFormat.Html, DocumentKind.Text, 3, "utf-8")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { FakeSession(emptyList(), info = html) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenSearch)
        viewModel.onAction(ReaderAction.SearchQueryChanged("hit"))
        advanceUntilIdle()
        assertEquals("hit", readySearch(viewModel).results.query)
        assertEquals(false, readySearch(viewModel).searching)
        assertEquals(false, readySearch(viewModel).hasMatches)
        assertEquals(null, readySearch(viewModel).countLabel)

        viewModel.onAction(ReaderAction.PageFindChanged(PageFind(0, 3, done = false)))
        assertEquals("1 / 3+", readySearch(viewModel).countLabel)
        viewModel.onAction(ReaderAction.PageFindChanged(PageFind(1, 7, done = true)))
        assertEquals("2 / 7", readySearch(viewModel).countLabel)
        assertTrue(readySearch(viewModel).hasMatches)

        viewModel.onAction(ReaderAction.NextMatch)
        assertEquals(1, readySearch(viewModel).pageStep)
        assertTrue(readySearch(viewModel).pageStepForward)
        viewModel.onAction(ReaderAction.PreviousMatch)
        assertEquals(2, readySearch(viewModel).pageStep)
        assertEquals(false, readySearch(viewModel).pageStepForward)

        // The old count doesn't linger under a new query.
        viewModel.onAction(ReaderAction.SearchQueryChanged("other"))
        advanceUntilIdle()
        assertEquals(null, readySearch(viewModel).pageFind)
        assertEquals(false, readySearch(viewModel).hasMatches)

        viewModel.onAction(ReaderAction.PageFindChanged(PageFind(0, 0, done = true)))
        assertEquals(null, readySearch(viewModel).countLabel)
        viewModel.onAction(ReaderAction.NextMatch)
        assertEquals(2, readySearch(viewModel).pageStep)
    }

    @Test
    fun word_opendocument_and_books_show_only_as_designed() = runTest {
        for ((name, format) in listOf("a.docx" to DocumentFormat.Docx, "a.odt" to DocumentFormat.Odt, "a.epub" to DocumentFormat.Epub)) {
            val info = DocumentInfo(name, format, DocumentKind.Text, 3, null)
            val reader = FakeReader { FakeSession(emptyList(), info = info) }
            val viewModel = ReaderViewModel(DocumentReadingService(reader))
            viewModel.onAction(ReaderAction.DocumentPicked(ref))
            assertEquals(false, assertIs<ReaderUiState.Ready>(viewModel.state.value).canSwitchSource, name)

            // No restyled view to switch to.
            viewModel.onAction(ReaderAction.ToggleSource)
            assertEquals(false, assertIs<ReaderUiState.Ready>(viewModel.state.value).source, name)
            assertEquals(false, reader.lastAsSource, name)
        }
    }

    @Test
    fun plain_text_switches_to_source_and_back() = runTest {
        lateinit var reader: FakeReader
        reader = FakeReader {
            val format = if (reader.lastAsSource) DocumentFormat.SourceCode else DocumentFormat.PlainText
            FakeSession(emptyList(), info = DocumentInfo("notes.txt", format, DocumentKind.Text, 3, "utf-8"))
        }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertTrue(assertIs<ReaderUiState.Ready>(viewModel.state.value).canSwitchSource)

        viewModel.onAction(ReaderAction.ToggleSource)
        val source = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(source.source && source.canSwitchSource)
        assertEquals(DocumentFormat.SourceCode, source.info.format)
        assertTrue(reader.lastAsSource)

        viewModel.onAction(ReaderAction.ToggleSource)
        val preview = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(false, preview.source)
        assertEquals(DocumentFormat.PlainText, preview.info.format)
        assertEquals(false, reader.lastAsSource)
    }

    @Test
    fun unrelated_plain_text_files_have_no_source_switch() = runTest {
        val info = DocumentInfo("notes.log", DocumentFormat.PlainText, DocumentKind.Text, 3, "utf-8")
        val reader = FakeReader { FakeSession(emptyList(), info = info) }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        viewModel.onAction(ReaderAction.DocumentPicked(DocumentRef("test://notes.log", "notes.log")))
        assertEquals(false, assertIs<ReaderUiState.Ready>(viewModel.state.value).canSwitchSource)

        viewModel.onAction(ReaderAction.ToggleSource)
        assertEquals(false, reader.lastAsSource)
    }

    @Test
    fun source_code_files_can_switch_source() = runTest {
        val info = DocumentInfo("code.kt", DocumentFormat.SourceCode, DocumentKind.Text, 3, "utf-8")
        val reader = FakeReader { FakeSession(emptyList(), info = info) }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        viewModel.onAction(ReaderAction.DocumentPicked(DocumentRef("test://code.kt", "code.kt")))
        assertEquals(true, assertIs<ReaderUiState.Ready>(viewModel.state.value).canSwitchSource)
    }

    @Test
    fun search_pulls_every_batch_and_finds_later_matches() = runTest {
        val session = FakeSession(listOf(batch("no", false), batch("hit", false), batch("no", false), batch("HIT", true)))
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("hit"))
        assertEquals(true, readySearch(viewModel).searching)
        assertEquals(1, session.batchesServed) // still debouncing
        advanceUntilIdle()

        val search = readySearch(viewModel)
        assertEquals(4, session.batchesServed)
        assertEquals(false, search.searching)
        assertEquals(listOf(1, 3), search.results.matches.map { it.page })
        assertEquals("1 / 2", search.countLabel)
        assertEquals(SearchMatch(3, 0, 0, 0, 3), viewModel.also { it.onAction(ReaderAction.PreviousMatch) }.let { readySearch(it).currentMatch })
    }

    @Test
    fun a_new_query_cancels_the_running_search() = runTest {
        val session = FakeSession(listOf(batch("old new", false), batch("old new", true)))
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("old"))
        advanceTimeBy(100)
        viewModel.onAction(ReaderAction.SearchQueryChanged("new"))
        advanceTimeBy(100)
        assertEquals("", readySearch(viewModel).results.query) // neither debounce has passed
        advanceUntilIdle()

        assertEquals("new", readySearch(viewModel).results.query)
        assertEquals(2, readySearch(viewModel).results.matches.size)
    }

    @Test
    fun clearing_the_query_clears_the_results_and_stops_pulling() = runTest {
        val session = FakeSession(listOf(batch("x", false), batch("x", true)))
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        viewModel.onAction(ReaderAction.SearchQueryChanged(""))
        advanceUntilIdle()

        val search = readySearch(viewModel)
        assertEquals(1, session.batchesServed)
        assertEquals(false, search.searching)
        assertTrue(search.results.matches.isEmpty())
    }

    @Test
    fun closing_search_stops_it_and_keeps_the_document_open() = runTest {
        val session = FakeSession(listOf(batch("x", false), batch("x", true)))
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        viewModel.onAction(ReaderAction.CloseSearch)
        advanceUntilIdle()

        val ready = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(null, ready.search)
        assertEquals(1, session.batchesServed)
    }

    @Test
    fun switching_to_source_resets_search() = runTest {
        val markdown = DocumentInfo("notes.md", DocumentFormat.Markdown, DocumentKind.Text, 3, "utf-8")
        val viewModel = searchingViewModel(FakeSession(listOf(batch("x", true)), info = markdown))
        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()
        assertEquals(1, readySearch(viewModel).results.matches.size)

        viewModel.onAction(ReaderAction.ToggleSource)
        advanceUntilIdle()

        val source = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(source.source)
        assertEquals(null, source.search)
    }

    @Test
    fun search_stops_at_a_failed_batch_and_resumes_on_retry() = runTest {
        val session = FakeSession(listOf(batch("x", false)), failAfterBatches = true)
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()
        assertEquals(false, readySearch(viewModel).searching)
        assertIs<DocumentError.Malformed>(assertIs<ReaderUiState.Ready>(viewModel.state.value).loadMoreError)

        session.failAfterBatches = false
        session.batches += batch("x", last = true)
        viewModel.onAction(ReaderAction.RetryLoadMore)
        advanceUntilIdle()

        assertEquals(false, readySearch(viewModel).searching)
        assertEquals(2, readySearch(viewModel).results.matches.size)
    }

    @Test
    fun search_ends_where_the_output_budget_ends_the_document() = runTest {
        val truncatedEnd = ContentBatch(listOf(ContentBlock.Paragraph("x")), isLast = true, truncated = true)
        val viewModel = searchingViewModel(FakeSession(listOf(batch("x", false), truncatedEnd)))

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()

        val ready = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(ready.truncated)
        assertEquals(false, readySearch(viewModel).searching)
        assertEquals(2, readySearch(viewModel).results.matches.size)
    }

    @Test
    fun search_is_offered_only_for_documents_drawn_from_text_blocks() = runTest {
        for (kind in DocumentKind.entries) {
            val info = DocumentInfo("file", DocumentFormat.PlainText, kind, 3, null)
            val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { FakeSession(emptyList(), info = info) }))
            viewModel.onAction(ReaderAction.DocumentPicked(ref))
            viewModel.onAction(ReaderAction.OpenSearch)
            val offered = kind == DocumentKind.Text || kind == DocumentKind.Spreadsheet
            assertEquals(offered, assertIs<ReaderUiState.Ready>(viewModel.state.value).search != null, "$kind")
        }
    }

    /** A reader for a file that opens only with "right". */
    private fun protectedReader(session: FakeSession = FakeSession(listOf(batch("secret", true)))): FakeReader {
        lateinit var reader: FakeReader
        reader = FakeReader {
            when (reader.lastPassword) {
                null -> throw DocumentException(DocumentError.PasswordRequired("protected"))
                "right" -> session
                else -> throw DocumentException(DocumentError.WrongPassword("no"))
            }
        }
        return reader
    }

    @Test
    fun a_protected_file_asks_for_a_password_and_opens_with_the_right_one() = runTest {
        val reader = protectedReader()
        val viewModel = ReaderViewModel(DocumentReadingService(reader))

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals(ReaderUiState.PasswordNeeded(ref), viewModel.state.value)

        viewModel.onAction(ReaderAction.SubmitPassword("wrong"))
        assertEquals(ReaderUiState.PasswordNeeded(ref, incorrect = true), viewModel.state.value)

        viewModel.onAction(ReaderAction.SubmitPassword("right"))
        val ready = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals<List<List<ContentBlock>>>(listOf(listOf(ContentBlock.Paragraph("secret"))), ready.pages)
        // Every try reopens the file from its reference: a descriptor is used up by each open.
        assertEquals(listOf<String?>(null, "wrong", "right"), reader.passwords)
        assertEquals(listOf(ref, ref, ref), reader.refs)
    }

    @Test
    fun cancelling_the_password_sheet_stops_opening() = runTest {
        val viewModel = ReaderViewModel(DocumentReadingService(protectedReader()))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.Cancel)
        assertEquals(ReaderUiState.Cancelled("notes.txt"), viewModel.state.value)

        viewModel.onAction(ReaderAction.SubmitPassword("right")) // nothing is waiting for one now
        assertEquals(ReaderUiState.Cancelled("notes.txt"), viewModel.state.value)
    }

    @Test
    fun the_sheet_stays_up_during_a_try_and_ignores_another() = runTest {
        val gate = CompletableDeferred<Unit>()
        lateinit var reader: FakeReader
        reader = FakeReader {
            if (reader.lastPassword == null) throw DocumentException(DocumentError.PasswordRequired("protected"))
            FakeSession(listOf(batch("x", true)))
        }
        reader.beforeOpen = { if (reader.lastPassword != null) gate.await() }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))

        viewModel.onAction(ReaderAction.SubmitPassword("one"))
        assertEquals(ReaderUiState.PasswordNeeded(ref, unlocking = true), viewModel.state.value)
        viewModel.onAction(ReaderAction.SubmitPassword("two"))
        assertEquals(listOf<String?>(null, "one"), reader.passwords)

        gate.complete(Unit)
        assertIs<ReaderUiState.Ready>(viewModel.state.value)
    }

    @Test
    fun unsupported_encryption_is_a_failure_not_a_prompt() = runTest {
        val error = DocumentError.UnsupportedEncryption("old RC4")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { throw DocumentException(error) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals(ReaderUiState.Failed("notes.txt", error), viewModel.state.value)
    }

    @Test
    fun a_password_action_never_prints_the_password() {
        assertEquals("SubmitPassword", ReaderAction.SubmitPassword("hunter2").toString())
    }

    @Test
    fun a_batch_finishing_behind_the_picker_is_kept_and_loading_carries_on() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeSession(listOf(batch("a", false), batch("b", false), batch("c", true)))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        session.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.PickDocument)
        assertTrue(readyBehindPicker(viewModel).loadingMore)

        gate.complete(Unit)
        assertEquals(false, readyBehindPicker(viewModel).loadingMore)
        assertEquals(listOf("a", "b"), shownText(readyBehindPicker(viewModel)))

        viewModel.onAction(ReaderAction.PickerDismissed)
        assertEquals(listOf("a", "b"), shownText(assertIs<ReaderUiState.Ready>(viewModel.state.value)))

        viewModel.onAction(ReaderAction.LoadMore)
        val done = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(listOf("a", "b", "c"), shownText(done))
        assertTrue(done.endReached)
    }

    @Test
    fun a_batch_failing_behind_the_picker_can_be_retried_after_it_closes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeSession(listOf(batch("a", false)), failAfterBatches = true)
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        session.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.PickDocument)
        gate.complete(Unit)
        assertEquals(false, readyBehindPicker(viewModel).loadingMore)
        assertIs<DocumentError.Malformed>(readyBehindPicker(viewModel).loadMoreError)

        viewModel.onAction(ReaderAction.PickerDismissed)
        assertIs<DocumentError.Malformed>(assertIs<ReaderUiState.Ready>(viewModel.state.value).loadMoreError)

        session.failAfterBatches = false
        session.batches += batch("b", last = true)
        viewModel.onAction(ReaderAction.RetryLoadMore)
        val done = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(listOf("a", "b"), shownText(done))
        assertEquals(null, done.loadMoreError)
    }

    @Test
    fun the_viewer_asking_for_more_behind_the_picker_is_not_ignored() = runTest {
        val session = FakeSession(listOf(batch("a", false), batch("b", true)))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))

        viewModel.onAction(ReaderAction.PickDocument)
        viewModel.onAction(ReaderAction.LoadMore)

        assertEquals(listOf("a", "b"), shownText(readyBehindPicker(viewModel)))
    }

    @Test
    fun a_new_file_picked_during_a_batch_gets_none_of_it() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = FakeSession(listOf(batch("old", false), batch("late", true)))
        val new = FakeSession(listOf(batch("new", false), batch("more", true)))
        val sessions = ArrayDeque(listOf(old, new))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { sessions.removeFirst() }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        old.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.PickDocument)
        viewModel.onAction(ReaderAction.DocumentPicked(ref.copy(uri = "test://other.txt")))
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(old.closed)
        assertEquals(1, old.batchesServed)
        assertEquals(listOf("new"), shownText(assertIs<ReaderUiState.Ready>(viewModel.state.value)))

        viewModel.onAction(ReaderAction.LoadMore)
        assertEquals(listOf("new", "more"), shownText(assertIs<ReaderUiState.Ready>(viewModel.state.value)))
    }

    @Test
    fun a_batch_outliving_its_job_cannot_land_in_the_same_file_reopened() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = FakeSession(listOf(batch("old", false), batch("late", true)))
        val new = FakeSession(listOf(batch("new", false), batch("more", true)))
        val sessions = ArrayDeque(listOf(old, new))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { sessions.removeFirst() }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        old.beforeBatch = ignoringCancellation(gate)

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, old.batchesServed) // it really did finish
        val reopened = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(listOf("new"), shownText(reopened))
        assertEquals(false, reopened.endReached)

        viewModel.onAction(ReaderAction.LoadMore)
        assertEquals(listOf("new", "more"), shownText(assertIs<ReaderUiState.Ready>(viewModel.state.value)))
    }

    @Test
    fun a_failure_outliving_its_job_cannot_land_in_the_next_document() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = FakeSession(listOf(batch("old", false)), failAfterBatches = true)
        val new = FakeSession(listOf(batch("new", false), batch("more", true)))
        val sessions = ArrayDeque(listOf(old, new))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { sessions.removeFirst() }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        old.beforeBatch = ignoringCancellation(gate)

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        gate.complete(Unit)
        advanceUntilIdle()

        val reopened = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(null, reopened.loadMoreError)
        assertEquals(listOf("new"), shownText(reopened))
    }

    @Test
    fun switching_to_source_during_a_batch_gets_none_of_it() = runTest {
        val gate = CompletableDeferred<Unit>()
        val markdown = DocumentInfo("notes.md", DocumentFormat.Markdown, DocumentKind.Text, 3, "utf-8")
        val rendered = FakeSession(listOf(batch("# old", false), batch("late", true)), info = markdown)
        val source = FakeSession(listOf(batch("raw", true)))
        val sessions = ArrayDeque(listOf(rendered, source))
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { sessions.removeFirst() }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        rendered.beforeBatch = ignoringCancellation(gate)

        viewModel.onAction(ReaderAction.LoadMore)
        viewModel.onAction(ReaderAction.ToggleSource)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, rendered.batchesServed) // it really did finish
        val shown = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertTrue(shown.source)
        assertEquals(listOf("raw"), shownText(shown))
    }

    @Test
    fun a_failed_open_outliving_its_job_leaves_the_next_document_alone() = runTest {
        val gate = CompletableDeferred<Unit>()
        val next = FakeSession(listOf(batch("next", true)))
        lateinit var reader: FakeReader
        reader = FakeReader { next }
        reader.beforeOpen = {
            if (reader.refs.size == 1) {
                ignoringCancellation(gate)()
                throw DocumentException(DocumentError.Malformed("bad bytes"))
            }
        }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))

        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.DocumentPicked(ref.copy(uri = "test://other.txt")))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, next.closed)
        assertEquals(listOf("next"), shownText(assertIs<ReaderUiState.Ready>(viewModel.state.value)))
    }

    @Test
    fun a_refused_password_arriving_after_cancel_does_not_bring_the_sheet_back() = runTest {
        val gate = CompletableDeferred<Unit>()
        lateinit var reader: FakeReader
        reader = FakeReader { throw DocumentException(DocumentError.PasswordRequired("protected")) }
        reader.beforeOpen = {
            if (reader.lastPassword != null) {
                ignoringCancellation(gate)()
                throw DocumentException(DocumentError.WrongPassword("no"))
            }
        }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        viewModel.onAction(ReaderAction.DocumentPicked(ref))

        viewModel.onAction(ReaderAction.SubmitPassword("wrong"))
        viewModel.onAction(ReaderAction.Cancel)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ReaderUiState.Cancelled("notes.txt"), viewModel.state.value)
    }

    @Test
    fun search_waiting_on_a_batch_finishes_behind_the_picker() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeSession(listOf(batch("x", false), batch("x", false), batch("x", true)))
        val viewModel = searchingViewModel(session)
        session.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()
        assertEquals(1, session.batchesServed)

        viewModel.onAction(ReaderAction.PickDocument)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, session.batchesServed)
        val behind = checkNotNull(readyBehindPicker(viewModel).search)
        assertEquals(false, behind.searching)
        assertEquals(3, behind.results.matches.size)

        viewModel.onAction(ReaderAction.PickerDismissed)
        assertEquals(behind, readySearch(viewModel))
    }

    @Test
    fun search_still_debouncing_when_the_picker_opens_is_not_left_spinning() = runTest {
        val session = FakeSession(listOf(batch("x", false), batch("x", true)))
        val viewModel = searchingViewModel(session)

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        viewModel.onAction(ReaderAction.PickDocument)
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PickerDismissed)

        val search = readySearch(viewModel)
        assertEquals(false, search.searching)
        assertEquals(2, search.results.matches.size)
    }

    @Test
    fun search_stopped_by_a_failure_behind_the_picker_resumes_on_retry() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeSession(listOf(batch("x", false)), failAfterBatches = true)
        val viewModel = searchingViewModel(session)
        session.beforeBatch = { gate.await() }

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PickDocument)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, checkNotNull(readyBehindPicker(viewModel).search).searching)
        assertIs<DocumentError.Malformed>(readyBehindPicker(viewModel).loadMoreError)

        viewModel.onAction(ReaderAction.PickerDismissed)
        session.failAfterBatches = false
        session.batches += batch("x", last = true)
        viewModel.onAction(ReaderAction.RetryLoadMore)
        advanceUntilIdle()

        assertEquals(false, readySearch(viewModel).searching)
        assertEquals(2, readySearch(viewModel).results.matches.size)
    }

    @Test
    fun a_new_file_picked_during_a_search_is_not_searched_or_given_its_batch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = FakeSession(listOf(batch("x", false), batch("x", true)))
        val new = FakeSession(listOf(batch("x", false), batch("x", true)))
        val sessions = ArrayDeque(listOf(old, new))
        val viewModel = ReaderViewModel(
            DocumentReadingService(FakeReader { sessions.removeFirst() }),
            searchDispatcher = Dispatchers.Main,
        )
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenSearch)
        old.beforeBatch = ignoringCancellation(gate)

        viewModel.onAction(ReaderAction.SearchQueryChanged("x"))
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PickDocument)
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        gate.complete(Unit)
        advanceUntilIdle()

        val reopened = assertIs<ReaderUiState.Ready>(viewModel.state.value)
        assertEquals(null, reopened.search)
        assertEquals(listOf("x"), shownText(reopened))
        assertEquals(1, new.batchesServed)
    }

    /** Stands in for an engine call that is past the point of noticing cancellation. */
    private fun ignoringCancellation(gate: CompletableDeferred<Unit>): suspend () -> Unit =
        { withContext(NonCancellable) { gate.await() } }

    private fun readyBehindPicker(viewModel: ReaderViewModel): ReaderUiState.Ready =
        assertIs<ReaderUiState.Ready>(assertIs<ReaderUiState.SelectingFile>(viewModel.state.value).previous)

    private fun shownText(ready: ReaderUiState.Ready): List<String> =
        ready.pages.flatten().map { (it as ContentBlock.Paragraph).text }

    /** An open document with the search bar showing; searches run on the test scheduler. */
    private fun searchingViewModel(session: FakeSession): ReaderViewModel {
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { session }), searchDispatcher = Dispatchers.Main)
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        viewModel.onAction(ReaderAction.OpenSearch)
        return viewModel
    }

    private fun readySearch(viewModel: ReaderViewModel): ReaderSearch =
        checkNotNull(assertIs<ReaderUiState.Ready>(viewModel.state.value).search)

    @Test
    fun a_bookmark_opens_the_document_at_its_place_once() = runTest {
        val at = BookmarkLocation.Page(11)
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { FakeSession(listOf(batch("a", last = true))) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref, at = at))
        assertEquals(at, assertIs<ReaderUiState.Ready>(viewModel.state.value).initialLocation)
        // Opened any other way, it starts where the viewer would.
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals(null, assertIs<ReaderUiState.Ready>(viewModel.state.value).initialLocation)
    }

    @Test
    fun a_bookmark_place_survives_the_password_prompt() = runTest {
        var locked = true
        val reader = FakeReader {
            if (locked) {
                locked = false
                throw DocumentException(DocumentError.PasswordRequired("locked"))
            }
            FakeSession(listOf(batch("a", last = true)))
        }
        val viewModel = ReaderViewModel(DocumentReadingService(reader))
        val at = BookmarkLocation.BookOffset(2, 40)
        viewModel.onAction(ReaderAction.DocumentPicked(ref, at = at))
        assertIs<ReaderUiState.PasswordNeeded>(viewModel.state.value)
        viewModel.onAction(ReaderAction.SubmitPassword("pw"))
        assertEquals(at, assertIs<ReaderUiState.Ready>(viewModel.state.value).initialLocation)
    }

    @Test
    fun a_lost_file_from_a_bookmark_offers_to_remove_it() = runTest {
        val lost = DocumentError.FileAccess("EzFiles no longer has access to this file.")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { throw DocumentException(lost) }))
        val at = BookmarkLocation.Page(3)
        viewModel.onAction(ReaderAction.DocumentPicked(ref, at = at))
        val failed = assertIs<ReaderUiState.Failed>(viewModel.state.value)
        assertEquals(BookmarkSpot(ref.identity, at), lostBookmark(failed))
        // Not from a bookmark: nothing to remove.
        viewModel.onAction(ReaderAction.DocumentPicked(ref))
        assertEquals(null, lostBookmark(viewModel.state.value))
    }

    @Test
    fun a_damaged_file_from_a_bookmark_keeps_it() = runTest {
        val damaged = DocumentError.Malformed("bad bytes")
        val viewModel = ReaderViewModel(DocumentReadingService(FakeReader { throw DocumentException(damaged) }))
        viewModel.onAction(ReaderAction.DocumentPicked(ref, at = BookmarkLocation.Page(0)))
        assertEquals(null, lostBookmark(viewModel.state.value))
    }

    private fun batch(text: String, last: Boolean) =
        ContentBatch(listOf(ContentBlock.Paragraph(text)), isLast = last, truncated = false)
}

private class FakeReader(private val open: () -> DocumentSession = { FakeSession(emptyList()) }) : DocumentReader {
    var lastAsSource = false
    val passwords = mutableListOf<String?>()
    val refs = mutableListOf<DocumentRef>()
    val lastPassword: String? get() = passwords.lastOrNull()
    var beforeOpen: suspend () -> Unit = {}

    override suspend fun open(ref: DocumentRef, limits: ReadLimits, asSource: Boolean, password: String?): DocumentSession {
        lastAsSource = asSource
        passwords += password
        refs += ref
        beforeOpen()
        return open()
    }
}

private class FakeSession(
    var batches: List<ContentBatch>,
    var failAfterBatches: Boolean = false,
    override val info: DocumentInfo = DocumentInfo("notes.txt", DocumentFormat.PlainText, DocumentKind.Text, 3, "utf-8"),
) : DocumentSession {
    var beforeBatch: suspend () -> Unit = {}
    var batchesServed = 0
    var cancelled = false
    var closed = false

    override suspend fun nextBatch(): ContentBatch? {
        beforeBatch()
        if (batchesServed >= batches.size) {
            if (failAfterBatches) throw DocumentException(DocumentError.Malformed("bad bytes"))
            return null
        }
        return batches[batchesServed++]
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() {
        closed = true
    }
}
