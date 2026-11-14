package com.ezfiles.reader.presentation.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.DocumentFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TextEditorViewModelTest {
    private val uri = "test://notes.txt"
    private val document = DocumentRef(uri, "notes.txt", identity = "old-identity")
    private val files = FakeFiles()
    private var clock = 0L
    private val editor = TextEditorViewModel(files) { clock }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun load(content: String, writable: Boolean = true) {
        files.content[uri] = content.encodeToByteArray()
        files.writable = writable
        editor.open(document, content.length.toLong())
        assertEquals(EditorPhase.Editing, editor.state.phase)
    }

    private fun type(text: String) {
        clock += 5000
        editor.onFieldChange(TextFieldValue(text, TextRange(text.length)))
    }

    private fun onDisk(target: String = uri) = files.content.getValue(target).decodeToString()

    @Test
    fun loading_shows_the_text_unmodified_without_writing() {
        load("one\ntwo\n")
        assertEquals("one\ntwo\n", editor.state.field.text)
        assertFalse(editor.state.modified)
        assertTrue(editor.state.canOverwrite)
        assertEquals(0, files.writes)
    }

    @Test
    fun oversized_files_are_refused_before_being_read() {
        files.content[uri] = ByteArray(10)
        editor.open(document, MAX_EDITABLE_BYTES + 1L)
        val phase = assertIs<EditorPhase.Refused>(editor.state.phase)
        assertTrue("too large" in phase.title)
        assertEquals(0, files.reads)
    }

    @Test
    fun a_file_turning_out_larger_than_reported_is_refused() {
        files.content[uri] = ByteArray(MAX_EDITABLE_BYTES + 1) { 'a'.code.toByte() }
        editor.open(document, knownSize = 1)
        assertIs<EditorPhase.Refused>(editor.state.phase)
    }

    @Test
    fun a_file_at_the_limit_opens() {
        files.content[uri] = ByteArray(MAX_EDITABLE_BYTES) { 'a'.code.toByte() }
        editor.open(document, MAX_EDITABLE_BYTES.toLong())
        assertEquals(EditorPhase.Editing, editor.state.phase)
    }

    @Test
    fun undecodable_files_are_refused_and_left_alone() {
        files.content[uri] = byteArrayOf(0x63, 0xE9.toByte())
        editor.open(document, 2)
        assertIs<EditorPhase.Refused>(editor.state.phase)
        assertEquals(0, files.writes)
        editor.save()
        editor.onFieldChange(TextFieldValue("x"))
        assertEquals(0, files.writes)
        assertEquals("", editor.state.field.text)
    }

    @Test
    fun a_read_failure_is_a_refusal_with_its_message() {
        files.failReads = true
        editor.open(document, 1)
        assertEquals("gone", assertIs<EditorPhase.Refused>(editor.state.phase).detail)
    }

    @Test
    fun modified_follows_the_text_not_the_history() {
        load("abc")
        type("abcd")
        assertTrue(editor.state.modified)
        editor.undo()
        assertFalse(editor.state.modified)
        assertEquals("abc", editor.state.field.text)
        assertTrue(editor.state.canRedo)
        editor.redo()
        assertTrue(editor.state.modified)
    }

    @Test
    fun moving_the_caret_is_not_a_modification() {
        load("abc")
        editor.onFieldChange(TextFieldValue("abc", TextRange(1)))
        assertFalse(editor.state.modified)
        assertFalse(editor.state.canUndo)
        assertEquals(TextRange(1), editor.state.field.selection)
    }

    @Test
    fun pasted_carriage_returns_are_normalized_with_the_caret_in_place() {
        load("")
        editor.onFieldChange(TextFieldValue("a\r\nb\rc", TextRange(4)))
        assertEquals("a\nb\nc", editor.state.field.text)
        assertEquals(TextRange(3), editor.state.field.selection)
    }

    @Test
    fun text_beyond_the_limit_is_rejected_with_a_notice() {
        load("abc")
        editor.onFieldChange(TextFieldValue("x".repeat(MAX_EDITABLE_BYTES + 1)))
        assertEquals("abc", editor.state.field.text)
        assertEquals(EditorNotice.TooLong, editor.state.notice)
        assertFalse(editor.state.modified)
    }

    @Test
    fun save_writes_exact_bytes_and_clears_modified() {
        files.content[uri] = "a\r\nb\r\n".encodeToByteArray()
        editor.open(document, 6)
        type("a\nB\n")
        editor.save()
        assertEquals("a\r\nB\r\n", onDisk())
        assertFalse(editor.state.modified)
        assertEquals(EditorNotice.Saved, editor.state.notice)
    }

    @Test
    fun save_does_nothing_unless_the_file_is_writable() {
        load("abc", writable = false)
        assertFalse(editor.state.canOverwrite)
        type("abcd")
        editor.save()
        assertEquals(0, files.writes)
        assertEquals("abc", onDisk())
    }

    @Test
    fun losing_write_access_after_loading_stops_the_save() {
        load("abc")
        type("abcd")
        files.writable = false
        editor.save()
        assertEquals(0, files.writes)
        assertFalse(editor.state.canOverwrite)
        assertEquals(EditorNotice.WriteAccessLost, editor.state.notice)
        assertTrue(editor.state.modified)
    }

    @Test
    fun text_that_cant_be_encoded_never_opens_the_file() {
        load("abc")
        type("abc\u0000")
        editor.save()
        assertEquals(0, files.writes)
        assertEquals("abc", onDisk())
        assertEquals(EditorNotice.CannotEncode, editor.state.notice)
        assertFalse(editor.prepareSaveAs("x.txt"), "no save dialog either")
    }

    @Test
    fun a_file_changed_elsewhere_asks_before_overwriting() {
        load("abc")
        type("abcd")
        files.content[uri] = "from another app".encodeToByteArray()
        editor.save()
        assertEquals(EditorPrompt.ChangedOnDisk, editor.state.prompt)
        assertEquals(0, files.writes)
        assertEquals("from another app", onDisk())

        editor.dismissPrompt()
        assertTrue(editor.state.modified)
        assertEquals(0, files.writes)

        editor.save()
        editor.save(force = true)
        assertEquals("abcd", onDisk())
        assertFalse(editor.state.modified)
    }

    @Test
    fun a_failure_before_writing_leaves_the_file_and_says_so() {
        load("abc")
        type("abcd")
        files.failWrites = FakeFiles.WriteFailure.BeforeOpening
        editor.save()
        assertEquals("abc", onDisk())
        assertEquals(EditorNotice.WriteFailedUnchanged, editor.state.notice)
        assertEquals("abcd", editor.state.field.text)
        assertTrue(editor.state.modified)
    }

    @Test
    fun a_failure_part_way_reports_a_possibly_incomplete_file_and_can_be_retried() {
        load("hello world")
        type("hello there")
        files.failWrites = FakeFiles.WriteFailure.PartWay
        editor.save()
        assertEquals("hello", onDisk())
        assertEquals(EditorNotice.WritePartial, editor.state.notice)
        assertEquals("hello there", editor.state.field.text)

        // Undoing to the old text must not look clean: the file no longer holds it.
        editor.undo()
        assertEquals("hello world", editor.state.field.text)
        assertTrue(editor.state.modified)
        editor.requestClose()
        assertEquals(EditorPrompt.UnsavedChanges, editor.state.prompt)
        editor.dismissPrompt()

        // The retry isn't mistaken for another app's change.
        files.failWrites = null
        editor.save()
        assertNull(editor.state.prompt)
        assertEquals("hello world", onDisk())
        assertFalse(editor.state.modified)
    }

    @Test
    fun a_provider_that_silently_keeps_other_bytes_is_not_reported_as_saved() {
        load("abc")
        type("abcd")
        files.failWrites = FakeFiles.WriteFailure.SilentlyWrong
        editor.save()
        assertEquals(EditorNotice.WriteUnconfirmed, editor.state.notice)
        assertTrue(editor.state.modified)
    }

    @Test
    fun an_error_reported_after_all_bytes_arrived_counts_as_saved() {
        load("abc")
        type("abcd")
        files.failWrites = FakeFiles.WriteFailure.OnClose
        editor.save()
        assertEquals("abcd", onDisk())
        assertEquals(EditorNotice.Saved, editor.state.notice)
    }

    @Test
    fun saving_makes_the_text_read_only_until_it_ends() {
        load("abc")
        type("abcd")
        val gate = CompletableDeferred<Unit>()
        files.beforeWrite = { gate.await() }
        editor.save()
        assertTrue(editor.state.busy)
        editor.onFieldChange(TextFieldValue("changed during the save"))
        editor.undo()
        editor.requestClose()
        assertEquals("abcd", editor.state.field.text)
        assertFalse(editor.state.finished)

        gate.complete(Unit)
        assertFalse(editor.state.busy)
        assertEquals("abcd", onDisk())
    }

    @Test
    fun back_closes_at_once_without_changes() {
        load("abc")
        editor.requestClose()
        assertTrue(editor.state.finished)
        assertNull(editor.takeResult())
    }

    @Test
    fun back_with_changes_asks_and_keep_editing_stays() {
        load("abc")
        type("abcd")
        editor.requestClose()
        assertEquals(EditorPrompt.UnsavedChanges, editor.state.prompt)
        assertFalse(editor.state.finished)
        editor.dismissPrompt()
        assertNull(editor.state.prompt)
        assertEquals("abcd", editor.state.field.text)
    }

    @Test
    fun discard_leaves_the_file_alone() {
        load("abc")
        type("abcd")
        editor.requestClose()
        editor.discard()
        assertTrue(editor.state.finished)
        assertEquals(0, files.writes)
        assertNull(editor.takeResult())
    }

    @Test
    fun save_from_the_prompt_closes_and_hands_over_the_document() {
        load("abc")
        type("abcd")
        editor.requestClose()
        editor.save(closeAfter = true)
        assertTrue(editor.state.finished)
        val saved = assertNotNull(editor.takeResult())
        assertEquals(uri, saved.uri)
        assertEquals(4, saved.sizeBytes)
        assertEquals("old-identity", saved.identity, "keeps its recents entry")
    }

    @Test
    fun a_failed_save_from_the_prompt_stays_open() {
        load("abc")
        type("abcd")
        editor.requestClose()
        files.failWrites = FakeFiles.WriteFailure.BeforeOpening
        editor.save(closeAfter = true)
        assertFalse(editor.state.finished)
        assertEquals("abcd", editor.state.field.text)
    }

    @Test
    fun discarding_after_a_save_still_hands_over_what_was_saved() {
        load("abc")
        type("abcd")
        editor.save()
        type("abcde")
        editor.requestClose()
        editor.discard()
        assertNotNull(editor.takeResult())
        assertEquals("abcd", onDisk())
    }

    @Test
    fun save_as_keeps_the_original_and_moves_on_to_the_copy() {
        load("abc", writable = false)
        type("abcd")
        assertTrue(editor.prepareSaveAs("copy.txt"))
        files.writable = true
        editor.onSaveAsResult("test://copy.txt")
        assertEquals("abc", onDisk())
        assertEquals("abcd", onDisk("test://copy.txt"))
        assertFalse(editor.state.modified)
        assertTrue(editor.state.canOverwrite, "the copy allows Save")
        assertEquals("copy.txt", editor.state.name)
        editor.requestClose()
        assertEquals("test://copy.txt", editor.takeResult()?.uri)
    }

    @Test
    fun dismissing_the_save_dialog_changes_nothing() {
        load("abc", writable = false)
        type("abcd")
        assertTrue(editor.prepareSaveAs("copy.txt"))
        editor.onSaveAsResult(null)
        assertTrue(editor.state.modified)
        assertEquals(0, files.writes)
    }

    @Test
    fun a_failed_save_as_keeps_the_text_and_the_original() {
        load("abc", writable = false)
        type("abcd")
        assertTrue(editor.prepareSaveAs("copy.txt"))
        files.failWrites = FakeFiles.WriteFailure.BeforeOpening
        editor.onSaveAsResult("test://copy.txt")
        assertEquals(EditorNotice.SaveAsFailed, editor.state.notice)
        assertTrue(editor.state.modified)
        assertEquals("abc", onDisk())
        editor.requestClose()
        assertEquals(EditorPrompt.UnsavedChanges, editor.state.prompt)
    }

    @Test
    fun a_new_file_is_utf8_lf_saved_with_save_as_then_opened() {
        editor.startNew()
        assertFalse(editor.state.canOverwrite)
        assertFalse(editor.state.modified)
        type("# Title\n\nbody\n")
        editor.save()
        assertEquals(0, files.writes, "a new file has nothing to overwrite")
        assertTrue(editor.prepareSaveAs("Untitled.md"))
        editor.onSaveAsResult("test://Untitled.md")
        assertEquals("# Title\n\nbody\n", onDisk("test://Untitled.md"))
        assertTrue(editor.state.finished)
        assertEquals("test://Untitled.md", editor.takeResult()?.uri)
    }

    @Test
    fun an_empty_new_file_closes_without_asking_and_a_written_one_asks() {
        editor.startNew()
        type("x")
        editor.requestClose()
        assertEquals(EditorPrompt.UnsavedChanges, editor.state.prompt)
        editor.dismissPrompt()
        type("")
        editor.requestClose()
        assertTrue(editor.state.finished)
    }

    @Test
    fun the_session_survives_being_asked_to_start_again() {
        editor.startNew()
        type("work in progress")
        editor.startNew()
        assertEquals("work in progress", editor.state.field.text)

        val reader = TextEditorViewModel(files) { clock }
        files.content[uri] = "abc".encodeToByteArray()
        reader.open(document, 3)
        reader.onFieldChange(TextFieldValue("abcd"))
        reader.open(document, 3)
        assertEquals("abcd", reader.state.field.text)
    }

    @Test
    fun taking_the_result_ends_the_session() {
        load("abc")
        type("abcd")
        editor.requestClose()
        editor.discard()
        editor.takeResult()
        assertEquals(EditorUiState(), editor.state)
        files.content[uri] = "new".encodeToByteArray()
        editor.open(document, 3)
        assertEquals("new", editor.state.field.text)
        assertFalse(editor.state.canUndo)
        assertContentEquals("new".encodeToByteArray(), files.content[uri])
    }
}

private class FakeFiles : DocumentFiles {
    enum class WriteFailure { BeforeOpening, PartWay, SilentlyWrong, OnClose }

    val content = mutableMapOf<String, ByteArray>()
    var writable = true
    var failReads = false
    var failWrites: WriteFailure? = null
    var beforeWrite: suspend () -> Unit = {}
    var reads = 0
    var writes = 0

    override suspend fun read(uri: String): ByteArray {
        reads++
        if (failReads) throw DocumentException(DocumentError.FileAccess("gone"))
        return content[uri] ?: throw DocumentException(DocumentError.FileAccess("missing"))
    }

    override suspend fun write(uri: String, bytes: ByteArray) {
        beforeWrite()
        when (failWrites) {
            WriteFailure.BeforeOpening -> throw DocumentException(DocumentError.FileAccess("refused"))
            WriteFailure.PartWay -> {
                writes++
                content[uri] = bytes.copyOf(5)
                throw DocumentException(DocumentError.FileAccess("disk full"))
            }
            WriteFailure.SilentlyWrong -> {
                writes++
                content[uri] = bytes + bytes
            }
            WriteFailure.OnClose -> {
                writes++
                content[uri] = bytes
                throw DocumentException(DocumentError.FileAccess("close failed"))
            }
            null -> {
                writes++
                content[uri] = bytes
            }
        }
    }

    override suspend fun canWrite(uri: String) = writable

    override suspend fun refOf(uri: String) =
        DocumentRef(uri, uri.substringAfterLast('/'), content[uri]?.size?.toLong(), identity = "fresh-identity")
}
