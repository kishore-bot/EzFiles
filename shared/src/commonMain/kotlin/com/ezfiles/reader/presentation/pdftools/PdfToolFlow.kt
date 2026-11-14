package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfFileInfo
import com.ezfiles.reader.domain.PdfSource
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A PDF ready to use. The password stays in memory, is never saved, and doesn't print. */
class PickedPdf internal constructor(val id: Long, val ref: DocumentRef, val info: PdfFileInfo, val password: String?) {
    val source: PdfSource get() = PdfSource(ref.uri, password)

    internal fun withPassword(password: String) = PickedPdf(id, ref, info, password)

    override fun toString() = "PickedPdf(${ref.displayName})"
}

/** The chosen files, in the order the tool will use them. */
data class PdfSourceList(val items: List<PickedPdf> = emptyList()) {
    val totalPages: Int get() = items.sumOf { it.info.pageCount }

    /** Merging needs at least two files. */
    val canMerge: Boolean get() = items.size >= 2

    fun added(item: PickedPdf) = PdfSourceList(items + item)

    fun removed(id: Long) = PdfSourceList(items.filterNot { it.id == id })

    /** Moves one file [by] places, stopping at the ends. */
    fun moved(id: Long, by: Int): PdfSourceList {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + by).coerceIn(items.indices)
        return PdfSourceList(items.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun replaced(item: PickedPdf) = PdfSourceList(items.map { if (it.id == item.id) item else it })
}

/** Where a tool's run stands. */
sealed interface PdfRunStatus {
    data object Idle : PdfRunStatus

    /** [files] says [done] counts output files; otherwise it's whatever the tool reports, usually pages. */
    data class Running(val done: Int, val total: Int, val files: Boolean, val cancelling: Boolean = false) : PdfRunStatus

    /** Finished; [uri] can be opened, unless several files were written. */
    data class Done(val message: String, val uri: String?) : PdfRunStatus

    /** Cancelled or failed; [message] says so, and what was written if anything was. */
    data class Stopped(val message: String) : PdfRunStatus
}

data class PdfPasswordPrompt(
    val name: String,
    val incorrect: Boolean,
    /** The file opens without a password but changing it needs the owner password. */
    val ownerPassword: Boolean,
    val busy: Boolean = false,
)

/** A system dialog the UI should show, once. */
sealed interface PdfSaveDialog {
    data class File(val suggestedName: String) : PdfSaveDialog

    data object Folder : PdfSaveDialog
}

data class PdfFlowState(
    val sources: PdfSourceList = PdfSourceList(),
    /** The file being read, while one is. */
    val inspecting: String? = null,
    val prompt: PdfPasswordPrompt? = null,
    /** Asks before changing a signed or archival file. */
    val warning: String? = null,
    val saveDialog: PdfSaveDialog? = null,
    val run: PdfRunStatus = PdfRunStatus.Idle,
    /** About a file that couldn't be used, or anything else outside a run. */
    val notice: String? = null,
) {
    val working: Boolean get() = inspecting != null || run is PdfRunStatus.Running
}

/** One file a job writes. [write] gets the sources with any password, in order, and must only touch [target] when complete. */
class PdfToolOutput(
    val name: String,
    val write: suspend (sources: List<PdfSource>, target: String, onProgress: (Int, Int) -> Unit) -> Unit,
)

/**
 * What a tool wants written. One output goes through Save As, or with [toFolder]
 * any number go into a chosen folder as new files. [savedMessage] gets the count
 * of files written; [onSaved] is where a tool forgets anything sensitive.
 */
class PdfToolJob(
    val outputs: List<PdfToolOutput>,
    val toFolder: Boolean = false,
    val mimeType: String = PDF_MIME,
    val savedMessage: (Int) -> String,
    val onSaved: () -> Unit = {},
) {
    init {
        require(toFolder || outputs.size == 1) { "Save As writes one file" }
    }
}

internal const val PDF_MIME = "application/pdf"

/** The warning to show before changing [sources], or `null` if there's nothing to warn about. */
internal fun changeWarning(sources: List<PickedPdf>): String? {
    val signed = sources.filter { it.info.signed }.map { it.ref.displayName }
    val archival = sources.filter { it.info.archival }.map { it.ref.displayName }
    val parts = buildList {
        if (signed.isNotEmpty()) add("${signed.joined()} has a digital signature. The new file's signature becomes invalid.")
        if (archival.isNotEmpty()) add("${archival.joined()} is a PDF/A file. The new file may no longer conform to PDF/A.")
    }
    return if (parts.isEmpty()) null else (parts + "The original isn't changed.").joinToString(" ")
}

private fun List<String>.joined() = joinToString(", ") { "\"$it\"" }

private const val NAMES_IN_REPORT = 5

/** Says how far a run into a folder got, and which files are there. */
internal fun partialReport(lead: String, written: List<String>, total: Int): String {
    if (written.isEmpty()) return "$lead No files were written."
    val names = written.take(NAMES_IN_REPORT).joinToString(", ") + if (written.size > NAMES_IN_REPORT) " and ${written.size - NAMES_IN_REPORT} more" else ""
    return "$lead ${written.size} of $total files were written: $names."
}

private fun hasPdfName(name: String): Boolean {
    val extension = name.substringAfterLast('/').substringAfterLast('.', "")
    return extension.isEmpty() || extension.equals("pdf", ignoreCase = true)
}

/** Why a picked file can't be used. The engine decides by content; the name only makes the message clearer. */
internal fun pickFailureMessage(name: String, error: DocumentError): String = when {
    (error is DocumentError.Unsupported || error is DocumentError.Malformed) && !hasPdfName(name) ->
        "\"$name\" isn't a PDF. Choose a PDF file."
    error is DocumentError.Malformed -> "\"$name\" couldn't be read as a PDF. ${error.detail}"
    else -> error.detail
}

/**
 * The steps every PDF tool shares: pick, password, warn, Save As, run with
 * progress and Cancel, and a result to open. Tools add their own settings and
 * hand over a [PdfToolJob]; [PdfToolHost] draws all of it. Keep one in a
 * [PdfToolViewModel] so a run survives the activity being recreated.
 *
 * A target from Save As that was empty when chosen is one the dialog just
 * created, so it's removed again if nothing ends up in it. One that already had
 * content is left to the [PdfTools] promise of not touching it until complete.
 *
 * Runs on [scope]'s single thread; only [PdfToolJob] progress arrives from elsewhere.
 */
class PdfToolFlow(
    private val scope: CoroutineScope,
    val tools: PdfTools,
    private val outputs: DocumentOutputs,
    /** Picking replaces the file instead of adding to the list. */
    private val singleSource: Boolean = true,
) {
    private val _state = MutableStateFlow(PdfFlowState())
    val state: StateFlow<PdfFlowState> = _state.asStateFlow()

    private var job: Job? = null
    private val queue = ArrayDeque<DocumentRef>()
    private var nextId = 0L
    private var preselected = false

    /** Waiting for a save dialog's answer. */
    private var pending: PdfToolJob? = null
    private var active: ActiveRun? = null
    private var asking: PasswordAsk? = null

    private sealed interface PasswordAsk {
        /** For a file being read; [replaces] when it's already in the list. */
        class ToRead(val ref: DocumentRef, val replaces: PickedPdf?) : PasswordAsk

        class ToRun(val source: PickedPdf) : PasswordAsk
    }

    private class ActiveRun(val job: PdfToolJob, val target: String?, val folder: String?) {
        /** The Save As target was just created by the dialog. */
        var createdTarget: Boolean? = null

        /** The file in the folder being written. */
        var inProgress: String? = null
        var next = 0
        var ownerPassword = false
        val written = ArrayList<String>()
    }

    /** Offers [ref] once per visit to the tool, so removing it stays removed. */
    fun preselect(ref: DocumentRef) = preselect(listOf(ref))

    /** Offers the ordered list once; single-source tools still take only its first file. */
    fun preselect(refs: List<DocumentRef>) {
        if (preselected || refs.isEmpty()) return
        preselected = true
        addPicked(refs)
    }

    fun addPicked(refs: List<DocumentRef>) {
        if (refs.isEmpty() || _state.value.working || active != null) return
        _state.update { it.copy(notice = null, run = PdfRunStatus.Idle) }
        queue.addAll(if (singleSource) refs.take(1) else refs)
        readNext()
    }

    fun removeSource(id: Long) = _state.update { it.copy(sources = it.sources.removed(id)) }

    fun moveSource(id: Long, by: Int) = _state.update { it.copy(sources = it.sources.moved(id, by)) }

    /** Asks for [pdf]'s owner password up front, for a file that opens without one. */
    fun askOwnerPassword(pdf: PickedPdf) {
        asking = PasswordAsk.ToRead(pdf.ref, pdf)
        _state.update { it.copy(prompt = PdfPasswordPrompt(pdf.ref.displayName, incorrect = false, ownerPassword = true)) }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        when (val ask = asking) {
            null -> Unit
            is PasswordAsk.ToRead -> {
                _state.update { state -> state.copy(prompt = state.prompt?.copy(busy = true, incorrect = false)) }
                read(ask.ref, password, ask.replaces)
            }
            is PasswordAsk.ToRun -> {
                asking = null
                val run = active ?: return
                _state.update { it.copy(prompt = null, sources = it.sources.replaced(ask.source.withPassword(password))) }
                launchRun(run)
            }
        }
    }

    fun dismissPassword() {
        val ask = asking
        asking = null
        _state.update { it.copy(prompt = null) }
        when (ask) {
            null -> Unit
            is PasswordAsk.ToRead -> {
                job?.cancel()
                _state.update { it.copy(inspecting = null) }
                readNext()
            }
            is PasswordAsk.ToRun -> {
                val run = active ?: return
                active = null
                scope.launch { _state.update { it.copy(run = PdfRunStatus.Stopped(discard(run, "Nothing was saved."))) } }
            }
        }
    }

    /** Starts saving: warns first if the file needs it, then asks where. */
    fun requestRun(job: PdfToolJob) {
        val current = _state.value
        if (current.working || active != null) return
        pending = job
        val warning = changeWarning(current.sources.items)
        _state.update { state ->
            state.copy(
                notice = null,
                run = PdfRunStatus.Idle,
                warning = warning,
                saveDialog = if (warning == null) job.dialog() else null,
            )
        }
    }

    fun confirmWarning() {
        val job = pending ?: return
        _state.update { it.copy(warning = null, saveDialog = job.dialog()) }
    }

    fun dismissWarning() {
        pending = null
        _state.update { it.copy(warning = null) }
    }

    /** The UI has shown [PdfFlowState.saveDialog]. */
    fun saveDialogShown() = _state.update { it.copy(saveDialog = null) }

    /** The answer from Save As; `null` if dismissed. */
    fun onTargetPicked(uri: String?) {
        val job = pending
        pending = null
        if (uri == null) return
        if (job == null) {
            // The app was closed behind the dialog, and with it what was going to be saved.
            scope.launch {
                if (outputs.sizeOf(uri) == 0L) outputs.delete(uri)
                _state.update { it.copy(notice = LOST_WORK) }
            }
            return
        }
        launchRun(ActiveRun(job, target = uri, folder = null).also { active = it })
    }

    fun onFolderPicked(folder: String) {
        val job = pending
        pending = null
        if (job == null) {
            _state.update { it.copy(notice = LOST_WORK) }
            return
        }
        launchRun(ActiveRun(job, target = null, folder = folder).also { active = it })
    }

    fun notice(message: String) = _state.update { it.copy(notice = message) }

    /** Stops reading files or running; the run reports what it left behind. */
    fun cancel() {
        queue.clear()
        if (_state.value.inspecting != null) {
            job?.cancel()
            _state.update { it.copy(inspecting = null) }
        }
        _state.update { state ->
            val running = state.run as? PdfRunStatus.Running ?: return@update state
            state.copy(run = running.copy(cancelling = true))
        }
        if (active != null) job?.cancel()
    }

    /** Back to the start after a result, staying on the tool. */
    fun startOver() {
        if (_state.value.working || active != null) return
        pending = null
        _state.value = PdfFlowState()
    }

    /** The tool was left: stops any run, cleans up, and forgets everything, passwords included. */
    fun leave() {
        val run = active
        val waiting = asking is PasswordAsk.ToRun
        active = null
        asking = null
        pending = null
        queue.clear()
        preselected = false
        job?.cancel()
        _state.value = PdfFlowState()
        // A run that's working cleans up as it's cancelled; one waiting for a password can't.
        if (run != null && waiting) scope.launch { discard(run, "") }
    }

    private fun PdfToolJob.dialog(): PdfSaveDialog = if (toFolder) PdfSaveDialog.Folder else PdfSaveDialog.File(outputs.single().name)

    private fun readNext() {
        val ref = queue.removeFirstOrNull()
        if (ref != null) read(ref, password = null, replaces = null)
    }

    private fun read(ref: DocumentRef, password: String?, replaces: PickedPdf?) {
        _state.update { it.copy(inspecting = ref.displayName) }
        job = scope.launch {
            try {
                val info = tools.inspect(PdfSource(ref.uri, password))
                asking = null
                val picked = PickedPdf(replaces?.id ?: nextId++, ref, info, password)
                _state.update { state ->
                    val sources = when {
                        replaces != null -> state.sources.replaced(picked)
                        singleSource -> PdfSourceList(listOf(picked))
                        else -> state.sources.added(picked)
                    }
                    state.copy(sources = sources, inspecting = null, prompt = null)
                }
                readNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentException) {
                onReadFailed(ref, password, replaces, e.error)
            } catch (_: Exception) {
                onReadFailed(ref, password, replaces, DocumentError.Internal(UNEXPECTED))
            }
        }
    }

    private fun onReadFailed(ref: DocumentRef, password: String?, replaces: PickedPdf?, error: DocumentError) {
        if (error is DocumentError.PasswordRequired || error is DocumentError.WrongPassword) {
            asking = PasswordAsk.ToRead(ref, replaces)
            val owner = replaces != null || error.detail.mentionsOwner()
            _state.update {
                it.copy(
                    inspecting = null,
                    prompt = PdfPasswordPrompt(ref.displayName, incorrect = password != null, ownerPassword = owner),
                )
            }
            return
        }
        asking = null
        _state.update {
            val message = pickFailureMessage(ref.displayName, error)
            it.copy(inspecting = null, prompt = null, notice = listOfNotNull(it.notice, message).joinToString("\n"))
        }
        readNext()
    }

    private fun launchRun(run: ActiveRun) {
        val files = run.folder != null
        _state.update { it.copy(notice = null, run = PdfRunStatus.Running(run.next, if (files) run.job.outputs.size else 0, files)) }
        job = scope.launch {
            try {
                if (run.folder != null) writeToFolder(run, run.folder) else writeToTarget(run, checkNotNull(run.target))
                finish(run)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val lead = if (run.folder != null) "Cancelled." else "Cancelled. Nothing was saved."
                    val message = discard(run, lead)
                    settle(run) { it.copy(run = PdfRunStatus.Stopped(message)) }
                }
                throw e
            } catch (e: DocumentException) {
                onRunFailed(run, e.error)
            } catch (_: Exception) {
                onRunFailed(run, DocumentError.Internal(UNEXPECTED))
            }
        }
    }

    private suspend fun writeToTarget(run: ActiveRun, target: String) {
        if (run.createdTarget == null) run.createdTarget = outputs.sizeOf(target) == 0L
        run.job.outputs.single().write(sourcesOfRun(), target) { done, total -> progress(run, done, total) }
    }

    private suspend fun writeToFolder(run: ActiveRun, folder: String) {
        if (!outputs.canCreateIn(folder)) {
            throw DocumentException(DocumentError.FileAccess("Files can't be created in this folder. Choose another one."))
        }
        val total = run.job.outputs.size
        while (run.next < total) {
            val output = run.job.outputs[run.next]
            val target = outputs.createIn(folder, output.name, run.job.mimeType)
            run.inProgress = target
            output.write(sourcesOfRun(), target) { _, _ -> }
            currentCoroutineContext().ensureActive()
            run.written += outputs.nameOf(target) ?: output.name
            run.inProgress = null
            run.next++
            progress(run, run.next, total)
        }
    }

    private fun sourcesOfRun() = _state.value.sources.items.map { it.source }

    /** From any thread, and possibly late: it only ever refines a run still going. */
    private fun progress(run: ActiveRun, done: Int, total: Int) {
        _state.update { state ->
            val running = state.run as? PdfRunStatus.Running
            if (running == null || active !== run) state else state.copy(run = running.copy(done = done, total = total))
        }
    }

    private fun finish(run: ActiveRun) {
        val count = if (run.folder != null) run.written.size else 1
        run.job.onSaved()
        // Done with the sources, and so with their passwords.
        settle(run) { PdfFlowState(run = PdfRunStatus.Done(run.job.savedMessage(count), run.target)) }
    }

    private suspend fun onRunFailed(run: ActiveRun, error: DocumentError) {
        if (error is DocumentError.PasswordRequired || error is DocumentError.WrongPassword) {
            val source = passwordSource(error)
            if (source != null) {
                // Whatever is in progress is empty and can go; the target and what's written stay for the retry.
                withContext(NonCancellable) {
                    run.inProgress?.let { outputs.delete(it) }
                    run.inProgress = null
                    if (active !== run) discard(run, "")
                }
                if (active !== run) return
                asking = PasswordAsk.ToRun(source)
                // A file that opened without a password can only be asking for the owner's.
                run.ownerPassword = run.ownerPassword || (source.info.encrypted && source.password == null) || error.detail.mentionsOwner()
                _state.update {
                    it.copy(
                        run = PdfRunStatus.Idle,
                        prompt = PdfPasswordPrompt(source.ref.displayName, incorrect = source.password != null, ownerPassword = run.ownerPassword),
                    )
                }
                return
            }
        }
        val message = withContext(NonCancellable) { discard(run, error.detail) }
        settle(run) { it.copy(run = PdfRunStatus.Stopped(message)) }
    }

    /** Which file a password problem is about; with several, the likeliest, or `null` if nothing points to one. */
    private fun passwordSource(error: DocumentError): PickedPdf? {
        val items = _state.value.sources.items
        return items.singleOrNull()
            ?: if (error is DocumentError.WrongPassword) {
                items.firstOrNull { it.password != null }
            } else {
                items.firstOrNull { it.info.encrypted && it.password == null }
            }
    }

    /** Removes what a run that didn't finish left behind, and returns [lead] with what the user should know. */
    private suspend fun discard(run: ActiveRun, lead: String): String {
        run.inProgress?.let { outputs.delete(it) }
        run.inProgress = null
        if (run.folder != null) return partialReport(lead, run.written, run.job.outputs.size)
        val target = run.target
        if (target != null && run.createdTarget == true && !outputs.delete(target)) {
            return "$lead An empty file may be left where you chose to save."
        }
        return lead
    }

    private fun settle(run: ActiveRun, change: (PdfFlowState) -> PdfFlowState) {
        // Once the tool has been left, a run still ending has nothing to show.
        if (active !== run) return
        active = null
        asking = null
        _state.update(change)
    }

    private fun String.mentionsOwner() = contains("owner", ignoreCase = true)

    private companion object {
        const val UNEXPECTED = "Something went wrong with this file."
        const val LOST_WORK = "EzFiles was closed while that dialog was open, so nothing was saved. Please try again."
    }
}
