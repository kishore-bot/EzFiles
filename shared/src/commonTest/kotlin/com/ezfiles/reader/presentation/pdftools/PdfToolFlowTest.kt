package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfFileInfo
import com.ezfiles.reader.domain.PdfPageEdit
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfSource
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

private const val LOCKED = "test://locked.pdf"

/** Everything is as the port promises, with hooks for what a test wants to go wrong. */
private class FakeTools : PdfTools {
    var infos = mutableMapOf<String, PdfFileInfo>()
    var passwords = mutableMapOf(LOCKED to "secret")
    var inspectFailure: DocumentError? = null
    var onWrite: suspend (PdfSource, String) -> Unit = { _, _ -> }
    val merged = mutableListOf<List<PdfSource>>()
    val rebuilt = mutableListOf<String>()

    private fun check(source: PdfSource) {
        val needed = passwords[source.uri] ?: return
        if (source.password == null) throw DocumentException(DocumentError.PasswordRequired("Needs a password"))
        if (source.password != needed) throw DocumentException(DocumentError.WrongPassword("Wrong password"))
    }

    override suspend fun inspect(source: PdfSource): PdfFileInfo {
        inspectFailure?.let { throw DocumentException(it) }
        check(source)
        return infos[source.uri] ?: PdfFileInfo(3, passwords.containsKey(source.uri), signed = false, archival = false)
    }

    override suspend fun merge(sources: List<PdfSource>, target: String, onProgress: (Int, Int) -> Unit) {
        sources.forEach(::check)
        onProgress(1, 2)
        onWrite(sources.first(), target)
        merged += sources
    }

    override suspend fun rebuild(source: PdfSource, pages: List<PdfPageEdit>, target: String, onProgress: (Int, Int) -> Unit) {
        check(source)
        onWrite(source, target)
        rebuilt += target
    }

    override suspend fun resizePages(source: PdfSource, size: PdfPageSize, target: String, onProgress: (Int, Int) -> Unit) = error("unused")

    override suspend fun insertImages(source: PdfSource, images: List<String>, atPage: Int, target: String, onProgress: (Int, Int) -> Unit) = error("unused")

    override suspend fun addPassword(source: PdfSource, password: String, target: String, onProgress: (Int, Int) -> Unit) = error("unused")

    override suspend fun removePassword(source: PdfSource, target: String, onProgress: (Int, Int) -> Unit) = error("unused")
}

private class FakeOutputs : DocumentOutputs {
    val sizes = mutableMapOf<String, Long?>()
    val deleted = mutableListOf<String>()
    val created = mutableListOf<String>()
    var canCreate = true
    var canDelete = true

    override suspend fun sizeOf(uri: String) = if (uri in sizes) sizes[uri] else 0L

    override suspend fun delete(uri: String): Boolean {
        if (canDelete) deleted += uri
        return canDelete
    }

    override suspend fun canCreateIn(folder: String) = canCreate

    override suspend fun createIn(folder: String, name: String, mimeType: String): String {
        created += name
        return "$folder/$name"
    }

    override suspend fun nameOf(uri: String) = uri.substringAfterLast('/')
}

@OptIn(ExperimentalCoroutinesApi::class)
class PdfToolFlowTest {
    private val tools = FakeTools()
    private val outputs = FakeOutputs()
    private val plain = DocumentRef("test://plain.pdf", "plain.pdf")
    private val other = DocumentRef("test://other.pdf", "other.pdf")
    private val locked = DocumentRef(LOCKED, "locked.pdf")

    private fun flowTest(singleSource: Boolean = true, test: suspend TestScope.(PdfToolFlow) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            test(PdfToolFlow(backgroundScope, tools, outputs, singleSource))
        }

    private fun mergeJob(onSaved: () -> Unit = {}) = PdfToolJob(
        outputs = listOf(PdfToolOutput("plain (merged).pdf") { pdfs, target, progress -> tools.merge(pdfs, target, progress) }),
        savedMessage = { "Merged." },
        onSaved = onSaved,
    )

    private fun rebuildOutput(name: String) = PdfToolOutput(name) { pdfs, target, progress ->
        tools.rebuild(pdfs.first(), listOf(PdfPageEdit(0)), target, progress)
    }

    private fun folderJob(vararg names: String) = PdfToolJob(
        outputs = names.map(::rebuildOutput),
        toFolder = true,
        savedMessage = { "Saved $it files." },
    )

    private val PdfToolFlow.current get() = state.value

    // Reading files

    @Test
    fun picks_a_file_and_replaces_it_in_a_single_file_tool() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        assertEquals("plain.pdf", flow.current.sources.items.single().ref.displayName)
        assertEquals(3, flow.current.sources.items.single().info.pageCount)
        flow.addPicked(listOf(other))
        assertEquals("other.pdf", flow.current.sources.items.single().ref.displayName)
    }

    @Test
    fun adds_several_files_to_a_merge_keeping_order() = flowTest(singleSource = false) { flow ->
        flow.addPicked(listOf(plain, other))
        assertEquals(listOf("plain.pdf", "other.pdf"), flow.current.sources.items.map { it.ref.displayName })
        flow.moveSource(flow.current.sources.items.last().id, -1)
        assertEquals(listOf("other.pdf", "plain.pdf"), flow.current.sources.items.map { it.ref.displayName })
    }

    @Test
    fun asks_for_a_password_then_says_when_it_is_wrong() = flowTest { flow ->
        flow.addPicked(listOf(locked))
        val prompt = assertNotNull(flow.current.prompt)
        assertFalse(prompt.incorrect)
        assertTrue(flow.current.sources.items.isEmpty())

        flow.submitPassword("nope")
        assertEquals(true, flow.current.prompt?.incorrect)
        assertTrue(flow.current.sources.items.isEmpty())

        flow.submitPassword("secret")
        assertNull(flow.current.prompt)
        assertEquals("secret", flow.current.sources.items.single().password)
    }

    @Test
    fun dismissing_the_password_drops_the_file_and_reads_the_next() = flowTest(singleSource = false) { flow ->
        flow.addPicked(listOf(locked, plain))
        assertNotNull(flow.current.prompt)
        flow.dismissPassword()
        assertNull(flow.current.prompt)
        assertEquals(listOf("plain.pdf"), flow.current.sources.items.map { it.ref.displayName })
    }

    @Test
    fun a_file_that_is_not_a_pdf_gets_a_clear_message() = flowTest { flow ->
        tools.inspectFailure = DocumentError.Unsupported("Unknown format")
        flow.addPicked(listOf(DocumentRef("test://a.docx", "a.docx")))
        assertEquals("\"a.docx\" isn't a PDF. Choose a PDF file.", flow.current.notice)
        assertTrue(flow.current.sources.items.isEmpty())
        assertNull(flow.current.inspecting)
    }

    @Test
    fun shows_the_port_typed_errors_as_they_are() = flowTest { flow ->
        tools.inspectFailure = DocumentError.Unsupported("This tool isn't ready yet.")
        flow.addPicked(listOf(plain))
        assertEquals("This tool isn't ready yet.", flow.current.notice)

        tools.inspectFailure = DocumentError.UnsupportedEncryption("Uses unknown encryption")
        flow.addPicked(listOf(plain))
        assertEquals("Uses unknown encryption", flow.current.notice)
    }

    @Test
    fun an_unexpected_exception_is_a_message_and_not_a_crash() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        val job = PdfToolJob(listOf(PdfToolOutput("x.pdf") { _, _, _ -> error("bug") }), savedMessage = { "" })
        flow.requestRun(job)
        flow.saveDialogShown()
        flow.onTargetPicked("test://x")
        assertIs<PdfRunStatus.Stopped>(flow.current.run)
    }

    @Test
    fun cancelling_a_read_forgets_the_queue() = flowTest(singleSource = false) { flow ->
        val slow = object : PdfTools by tools {
            override suspend fun inspect(source: PdfSource): PdfFileInfo = awaitCancellation()
        }
        val slowFlow = PdfToolFlow(backgroundScope, slow, outputs, singleSource = false)
        slowFlow.addPicked(listOf(plain, other))
        assertEquals("plain.pdf", slowFlow.current.inspecting)
        slowFlow.cancel()
        assertNull(slowFlow.current.inspecting)
        assertTrue(slowFlow.current.sources.items.isEmpty())
        assertTrue(flow.current.sources.items.isEmpty())
    }

    // Warnings

    @Test
    fun warns_before_changing_a_signed_file_and_goes_on_only_if_told_to() = flowTest { flow ->
        tools.infos[plain.uri] = PdfFileInfo(1, encrypted = false, signed = true, archival = false)
        flow.addPicked(listOf(plain))

        flow.requestRun(mergeJob())
        assertTrue("signature" in flow.current.warning.orEmpty())
        assertNull(flow.current.saveDialog)

        flow.dismissWarning()
        assertNull(flow.current.warning)
        assertNull(flow.current.saveDialog)

        flow.requestRun(mergeJob())
        flow.confirmWarning()
        assertNull(flow.current.warning)
        assertEquals(PdfSaveDialog.File("plain (merged).pdf"), flow.current.saveDialog)
    }

    @Test
    fun does_not_warn_about_an_ordinary_file() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        assertNull(flow.current.warning)
        assertNotNull(flow.current.saveDialog)
    }

    // Saving

    @Test
    fun saves_then_offers_the_file_and_forgets_the_sources() = flowTest(singleSource = false) { flow ->
        var saved = 0
        flow.addPicked(listOf(plain, locked))
        flow.submitPassword("secret")
        flow.requestRun(mergeJob { saved++ })
        flow.saveDialogShown()
        flow.onTargetPicked("test://out")

        assertEquals(PdfRunStatus.Done("Merged.", "test://out"), flow.current.run)
        assertEquals(1, saved)
        assertEquals(listOf(PdfSource(plain.uri), PdfSource(LOCKED, "secret")), tools.merged.single())
        assertTrue(flow.current.sources.items.isEmpty())
        assertTrue(outputs.deleted.isEmpty())
    }

    @Test
    fun dismissing_save_as_changes_nothing() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.saveDialogShown()
        flow.onTargetPicked(null)
        assertEquals(PdfRunStatus.Idle, flow.current.run)
        assertEquals(1, flow.current.sources.items.size)
        assertTrue(tools.merged.isEmpty())
    }

    @Test
    fun progress_is_reported_and_a_late_callback_cannot_undo_the_result() = flowTest { flow ->
        val gate = CompletableDeferred<Unit>()
        var progress: ((Int, Int) -> Unit)? = null
        flow.addPicked(listOf(plain))
        flow.requestRun(
            PdfToolJob(
                listOf(
                    PdfToolOutput("x.pdf") { _, _, onProgress ->
                        progress = onProgress
                        onProgress(2, 5)
                        gate.await()
                    },
                ),
                savedMessage = { "Done." },
            ),
        )
        flow.onTargetPicked("test://out")
        assertEquals(PdfRunStatus.Running(2, 5, files = false), flow.current.run)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertIs<PdfRunStatus.Done>(flow.current.run)
        progress?.invoke(4, 5)
        assertIs<PdfRunStatus.Done>(flow.current.run)
    }

    @Test
    fun cancelling_removes_the_file_the_dialog_created() = flowTest { flow ->
        tools.onWrite = { _, _ -> awaitCancellation() }
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://out")
        assertIs<PdfRunStatus.Running>(flow.current.run)

        flow.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("test://out"), outputs.deleted)
        assertEquals(PdfRunStatus.Stopped("Cancelled. Nothing was saved."), flow.current.run)
        // The sources stay, so the user can try again.
        assertEquals(1, flow.current.sources.items.size)
    }

    @Test
    fun cancelling_never_removes_a_file_that_had_content() = flowTest { flow ->
        tools.onWrite = { _, _ -> awaitCancellation() }
        outputs.sizes["test://existing"] = 1234L
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://existing")
        flow.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(outputs.deleted.isEmpty())
        assertIs<PdfRunStatus.Stopped>(flow.current.run)
    }

    @Test
    fun a_provider_that_does_not_report_size_is_left_alone() = flowTest { flow ->
        tools.onWrite = { _, _ -> throw DocumentException(DocumentError.Internal("broke")) }
        outputs.sizes["test://unknown"] = null
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://unknown")
        assertTrue(outputs.deleted.isEmpty())
        assertEquals(PdfRunStatus.Stopped("broke"), flow.current.run)
    }

    @Test
    fun failure_removes_the_created_file_and_shows_the_typed_error() = flowTest { flow ->
        tools.onWrite = { _, _ -> throw DocumentException(DocumentError.LimitExceeded("Too big")) }
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://out")
        assertEquals(listOf("test://out"), outputs.deleted)
        assertEquals(PdfRunStatus.Stopped("Too big"), flow.current.run)
    }

    @Test
    fun says_so_when_the_empty_file_cannot_be_removed() = flowTest { flow ->
        tools.onWrite = { _, _ -> throw DocumentException(DocumentError.Internal("broke")) }
        outputs.canDelete = false
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://out")
        val stopped = assertIs<PdfRunStatus.Stopped>(flow.current.run)
        assertTrue("broke" in stopped.message && "empty file" in stopped.message)
    }

    @Test
    fun a_save_answer_with_nothing_to_save_cleans_up_and_explains() = flowTest { flow ->
        // The process died behind the dialog: this is a new flow getting the old dialog's answer.
        flow.onTargetPicked("test://orphan")
        assertEquals(listOf("test://orphan"), outputs.deleted)
        assertTrue("try again" in flow.current.notice.orEmpty())
    }

    @Test
    fun an_orphan_answer_never_removes_an_existing_file() = flowTest { flow ->
        outputs.sizes["test://existing"] = 10L
        flow.onTargetPicked("test://existing")
        assertTrue(outputs.deleted.isEmpty())
    }

    // Passwords during a run

    private fun lockedJob(): PdfToolJob = PdfToolJob(listOf(rebuildOutput("x.pdf")), savedMessage = { "Saved." })

    @Test
    fun an_operation_asking_for_a_password_retries_on_the_same_target() = flowTest { flow ->
        // The file opens without a password, but the operation wants one.
        tools.infos[LOCKED] = PdfFileInfo(1, encrypted = true, signed = false, archival = false)
        val lenient = object : PdfTools by tools {
            override suspend fun inspect(source: PdfSource) = tools.infos.getValue(source.uri)
        }
        val owner = PdfToolFlow(backgroundScope, lenient, outputs)
        owner.addPicked(listOf(locked))
        assertNull(owner.current.prompt)

        owner.requestRun(lockedJob())
        owner.onTargetPicked("test://out")
        val prompt = assertNotNull(owner.current.prompt)
        assertTrue(prompt.ownerPassword)
        assertFalse(prompt.incorrect)
        assertEquals(PdfRunStatus.Idle, owner.current.run)
        assertTrue(outputs.deleted.isEmpty())

        owner.submitPassword("nope")
        assertEquals(true, owner.current.prompt?.incorrect)
        assertEquals(true, owner.current.prompt?.ownerPassword)

        owner.submitPassword("secret")
        assertNull(owner.current.prompt)
        assertEquals(PdfRunStatus.Done("Saved.", "test://out"), owner.current.run)
        assertEquals(listOf("test://out"), tools.rebuilt)
    }

    @Test
    fun declining_the_password_during_a_run_removes_the_target() = flowTest { flow ->
        val lenient = object : PdfTools by tools {
            override suspend fun inspect(source: PdfSource) = PdfFileInfo(1, encrypted = true, signed = false, archival = false)
        }
        val owner = PdfToolFlow(backgroundScope, lenient, outputs)
        owner.addPicked(listOf(locked))
        owner.requestRun(lockedJob())
        owner.onTargetPicked("test://out")
        owner.dismissPassword()
        assertEquals(listOf("test://out"), outputs.deleted)
        assertEquals(PdfRunStatus.Stopped("Nothing was saved."), owner.current.run)
        assertNull(flow.current.prompt)
    }

    @Test
    fun asks_for_the_owner_password_up_front() = flowTest { flow ->
        val lenient = object : PdfTools by tools {
            override suspend fun inspect(source: PdfSource) = when (source.password) {
                null, "secret" -> PdfFileInfo(1, encrypted = true, signed = false, archival = false)
                else -> throw DocumentException(DocumentError.WrongPassword("no"))
            }
        }
        val owner = PdfToolFlow(backgroundScope, lenient, outputs)
        owner.addPicked(listOf(locked))
        val pdf = owner.current.sources.items.single()
        owner.askOwnerPassword(pdf)
        assertEquals(true, owner.current.prompt?.ownerPassword)
        owner.submitPassword("wrong")
        assertEquals(true, owner.current.prompt?.incorrect)
        owner.submitPassword("secret")
        assertNull(owner.current.prompt)
        assertEquals(pdf.id, owner.current.sources.items.single().id)
        assertEquals("secret", owner.current.sources.items.single().password)
        assertTrue(flow.current.sources.items.isEmpty())
    }

    @Test
    fun an_engine_message_about_the_owner_is_understood() = flowTest { flow ->
        tools.inspectFailure = DocumentError.PasswordRequired("Needs the owner password")
        flow.addPicked(listOf(plain))
        assertEquals(true, flow.current.prompt?.ownerPassword)
    }

    // Writing to a folder

    @Test
    fun writes_each_file_to_the_folder_and_reports_the_count() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        flow.requestRun(folderJob("a.pdf", "b.pdf", "c.pdf"))
        assertEquals(PdfSaveDialog.Folder, flow.current.saveDialog)
        flow.saveDialogShown()
        flow.onFolderPicked("tree:")
        assertEquals(PdfRunStatus.Done("Saved 3 files.", null), flow.current.run)
        assertEquals(listOf("tree:/a.pdf", "tree:/b.pdf", "tree:/c.pdf"), tools.rebuilt)
    }

    @Test
    fun on_failure_says_which_files_were_written_and_removes_only_the_unfinished_one() = flowTest { flow ->
        tools.onWrite = { _, target -> if (target.endsWith("b.pdf")) throw DocumentException(DocumentError.FileAccess("Disk full.")) }
        flow.addPicked(listOf(plain))
        flow.requestRun(folderJob("a.pdf", "b.pdf", "c.pdf"))
        flow.onFolderPicked("tree:")

        val stopped = assertIs<PdfRunStatus.Stopped>(flow.current.run)
        assertEquals("Disk full. 1 of 3 files were written: a.pdf.", stopped.message)
        assertEquals(listOf("tree:/b.pdf"), outputs.deleted)
        assertEquals(listOf("a.pdf", "b.pdf"), outputs.created)
    }

    @Test
    fun cancelling_a_folder_run_reports_what_is_there() = flowTest { flow ->
        tools.onWrite = { _, target -> if (target.endsWith("b.pdf")) awaitCancellation() }
        flow.addPicked(listOf(plain))
        flow.requestRun(folderJob("a.pdf", "b.pdf", "c.pdf"))
        flow.onFolderPicked("tree:")
        assertEquals(PdfRunStatus.Running(1, 3, files = true), flow.current.run)

        flow.cancel()
        testScheduler.advanceUntilIdle()

        val stopped = assertIs<PdfRunStatus.Stopped>(flow.current.run)
        assertEquals("Cancelled. 1 of 3 files were written: a.pdf.", stopped.message)
        assertEquals(listOf("tree:/b.pdf"), outputs.deleted)
    }

    @Test
    fun a_failure_before_any_file_says_none_were_written() = flowTest { flow ->
        outputs.canCreate = false
        flow.addPicked(listOf(plain))
        flow.requestRun(folderJob("a.pdf"))
        flow.onFolderPicked("tree:")
        val stopped = assertIs<PdfRunStatus.Stopped>(flow.current.run)
        assertTrue("Choose another" in stopped.message && "No files were written" in stopped.message)
        assertTrue(outputs.created.isEmpty())
    }

    @Test
    fun long_reports_name_the_first_files_and_count_the_rest() {
        val names = List(8) { "f$it.pdf" }
        assertEquals(
            "Stopped. 8 of 9 files were written: f0.pdf, f1.pdf, f2.pdf, f3.pdf, f4.pdf and 3 more.",
            partialReport("Stopped.", names, 9),
        )
    }

    // Leaving

    @Test
    fun leaving_stops_the_run_cleans_up_and_forgets_everything() = flowTest { flow ->
        tools.onWrite = { _, _ -> awaitCancellation() }
        flow.addPicked(listOf(locked))
        flow.submitPassword("secret")
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://out")

        flow.leave()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("test://out"), outputs.deleted)
        assertEquals(PdfFlowState(), flow.current)
    }

    @Test
    fun leaving_during_a_password_prompt_for_a_run_cleans_up() = flowTest { flow ->
        val lenient = object : PdfTools by tools {
            override suspend fun inspect(source: PdfSource) = PdfFileInfo(1, encrypted = true, signed = false, archival = false)
        }
        val owner = PdfToolFlow(backgroundScope, lenient, outputs)
        owner.addPicked(listOf(locked))
        owner.requestRun(lockedJob())
        owner.onTargetPicked("test://out")
        assertNotNull(owner.current.prompt)
        owner.leave()
        assertEquals(listOf("test://out"), outputs.deleted)
        assertEquals(PdfFlowState(), owner.current)
        assertNull(flow.current.prompt)
    }

    @Test
    fun preselects_once_per_visit() = flowTest { flow ->
        flow.preselect(plain)
        val first = flow.current.sources.items.single()
        flow.removeSource(first.id)
        flow.preselect(plain)
        assertTrue(flow.current.sources.items.isEmpty())
        flow.leave()
        flow.preselect(plain)
        assertEquals(1, flow.current.sources.items.size)
    }

    @Test
    fun preselects_an_ordered_merge_list_without_restoring_removed_sources() = flowTest(singleSource = false) { flow ->
        flow.preselect(listOf(other, plain))
        assertEquals(listOf(other, plain), flow.current.sources.items.map { it.ref })
        flow.removeSource(flow.current.sources.items.first().id)
        flow.preselect(listOf(other, plain))
        assertEquals(listOf(plain), flow.current.sources.items.map { it.ref })
        flow.leave()
        flow.preselect(listOf(other, plain))
        assertEquals(listOf(other, plain), flow.current.sources.items.map { it.ref })
    }

    @Test
    fun preselects_only_the_first_ordered_source_for_a_single_file_tool() = flowTest { flow ->
        flow.preselect(listOf(other, plain))
        assertEquals(listOf(other), flow.current.sources.items.map { it.ref })
    }

    @Test
    fun start_over_clears_a_result() = flowTest { flow ->
        flow.addPicked(listOf(plain))
        flow.requestRun(mergeJob())
        flow.onTargetPicked("test://out")
        assertIs<PdfRunStatus.Done>(flow.current.run)
        flow.startOver()
        assertEquals(PdfFlowState(), flow.current)
    }
}
