package com.ezfiles.reader.presentation.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezfiles.reader.data.RustImaging
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfTextLine
import com.ezfiles.reader.domain.RecognizedLine
import com.ezfiles.reader.domain.TextRecognizer
import com.ezfiles.reader.platform.CameraCapturer
import com.ezfiles.reader.platform.LumaImage
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A straightened page: what it was cut from is kept, so the outline and filter can change. */
@Serializable
data class ScanPage(
    val id: String,
    val quad: PageQuad,
    /** False when the corners are only the whole picture because no outline was found. */
    val detected: Boolean,
    val filter: ScanFilter = ScanFilter.Enhanced,
    /** Goes up each time the output picture is replaced, so previews reload. */
    val version: Int = 0,
)

sealed interface ScanStep {
    /** Taking a picture; with [retaking], it replaces that page. */
    data class Capture(val retaking: String? = null) : ScanStep

    data class Adjust(val pageId: String) : ScanStep

    data object Pages : ScanStep

    data object Saved : ScanStep
}

sealed interface ScanBusy {
    data object Preparing : ScanBusy

    data class Reading(val page: Int, val total: Int) : ScanBusy

    data object Writing : ScanBusy
}

data class ScanSavedPdf(val uri: String, val note: String?)

data class ScanUiState(
    val step: ScanStep = ScanStep.Capture(),
    val pages: List<ScanPage> = emptyList(),
    val searchable: Boolean = false,
    val busy: ScanBusy? = null,
    /** Pages whose output is being redrawn; their previews are out of date. */
    val rendering: Set<String> = emptySet(),
    val message: String? = null,
    val saved: ScanSavedPdf? = null,
)

sealed interface ScanAction {
    data class TakePicture(val capturer: CameraCapturer) : ScanAction

    data class ImportPictures(val uris: List<String>) : ScanAction

    /** Back to the camera to add a page. */
    data object AddPage : ScanAction

    data class Retake(val id: String) : ScanAction

    data class Adjust(val id: String) : ScanAction

    data class SetOutline(val id: String, val quad: PageQuad) : ScanAction

    data class SetFilter(val id: String, val filter: ScanFilter) : ScanAction

    data class SetFilterOnAll(val filter: ScanFilter) : ScanAction

    data class Rotate(val id: String) : ScanAction

    /** Moves a page by [by] places. */
    data class Move(val id: String, val by: Int) : ScanAction

    data class Delete(val id: String) : ScanAction

    data class SetSearchable(val searchable: Boolean) : ScanAction

    /** Back from the camera or the corner editor. */
    data object ShowPages : ScanAction

    /** The save dialog's new file. */
    data class SaveTo(val target: String) : ScanAction

    /** Stops whatever [ScanUiState.busy] shows. */
    data object CancelWork : ScanAction

    data object DismissMessage : ScanAction

    /** Ends the session: every working file goes. */
    data object Discard : ScanAction
}

/** The saved part of a session, read back if the process is killed in the background. */
@Serializable
private class SavedSession(val pages: List<ScanPage>, val searchable: Boolean)

/** Longest side of the picture the outline is searched in. */
const val OUTLINE_SEARCH_SIDE = 384

/** The blur check looks at the picture at this size: fine detail survives, work stays small. */
private const val BLUR_CHECK_SIDE = 1600
private const val BLURRY_PICTURE = "This picture looks blurry. If the words aren't sharp, retake it and hold the phone still."

/**
 * Limit on one document. Saving holds every encoded page in memory at once
 * (see [pdfPageOf]), and each page is about a megabyte.
 */
const val MAX_SCAN_PAGES = 50

/**
 * Drives the scanner. One piece of picture work runs at a time, so at most one
 * full-size picture is in memory, and the session is kept in the app-private
 * workspace so it outlives process death.
 */
class ScanViewModel(
    private val workspace: ScanWorkspace,
    private val recognizer: TextRecognizer,
    private val pdf: PdfCreator,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = { Random.nextLong().toULong().toString(16) },
    private val isBlurry: (LumaImage) -> Boolean = RustImaging::isBlurry,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val work = Mutex()
    private var session = newSession()
    private var clearing: Job? = null
    private var foreground: Job? = null
    private val renders = HashMap<String, Job>()

    init {
        launchWork { restore() }
    }

    fun onAction(action: ScanAction) {
        when (action) {
            is ScanAction.TakePicture -> takePicture(action.capturer)
            is ScanAction.ImportPictures -> importPictures(action.uris)
            ScanAction.AddPage -> _state.update { it.copy(step = ScanStep.Capture()) }
            is ScanAction.Retake -> _state.update { it.copy(step = ScanStep.Capture(retaking = action.id)) }
            is ScanAction.Adjust -> _state.update { it.copy(step = ScanStep.Adjust(action.id)) }
            is ScanAction.SetOutline -> {
                _state.update { it.copy(step = ScanStep.Pages) }
                edit(action.id) { it.copy(quad = action.quad, detected = true) }
            }
            is ScanAction.SetFilter -> edit(action.id) { it.copy(filter = action.filter) }
            is ScanAction.SetFilterOnAll -> _state.value.pages.forEach { page ->
                if (page.filter != action.filter) edit(page.id) { it.copy(filter = action.filter) }
            }
            is ScanAction.Rotate -> edit(action.id) { it.copy(quad = it.quad.rotatedClockwise()) }
            is ScanAction.Move -> move(action.id, action.by)
            is ScanAction.Delete -> delete(action.id)
            is ScanAction.SetSearchable -> {
                _state.update { it.copy(searchable = action.searchable) }
                launchWork { persist() }
            }
            ScanAction.ShowPages -> _state.update { it.copy(step = ScanStep.Pages) }
            is ScanAction.SaveTo -> save(action.target)
            ScanAction.CancelWork -> foreground?.cancel()
            ScanAction.DismissMessage -> _state.update { it.copy(message = null) }
            ScanAction.Discard -> discard()
        }
    }

    private fun newSession() = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext.job))

    private fun launchWork(block: suspend () -> Unit): Job {
        val pendingClear = clearing
        return session.launch {
            pendingClear?.join()
            work.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocumentException) {
                    showMessage(e.error.detail)
                } catch (_: Throwable) {
                    // Includes running out of memory on a huge batch; the session is still on disk.
                    showMessage("Something went wrong with this page. Your other pages are safe.")
                }
            }
        }
    }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private suspend fun restore() {
        val saved = workspace.readState() ?: return
        val restored = try {
            Json.decodeFromString<SavedSession>(saved)
        } catch (_: SerializationException) {
            workspace.clear()
            return
        } catch (_: IllegalArgumentException) {
            workspace.clear()
            return
        }
        val pages = restored.pages.filter { workspace.exists(it.id) }
        _state.update {
            it.copy(
                pages = pages,
                searchable = restored.searchable,
                step = if (pages.isEmpty()) ScanStep.Capture() else ScanStep.Pages,
            )
        }
    }

    private suspend fun persist() {
        val current = _state.value
        workspace.writeState(Json.encodeToString(SavedSession(current.pages, current.searchable)))
    }

    private fun takePicture(capturer: CameraCapturer) {
        if (_state.value.busy != null) return
        val retaking = (_state.value.step as? ScanStep.Capture)?.retaking
        if (retaking == null && atPageLimit()) return
        foreground = launchWork {
            busy(ScanBusy.Preparing) {
                val id = newId()
                try {
                    val target = workspace.captureTarget(id)
                    capturer.capture(target)
                    val page = preparePage(id, target)
                    placePage(page, retaking)
                    persist()
                    val blurry = looksBlurry(id)
                    _state.update { it.copy(step = ScanStep.Adjust(id), message = if (blurry) BLURRY_PICTURE else it.message) }
                } catch (e: Throwable) {
                    withContext(NonCancellable) { workspace.deletePage(id) }
                    throw e
                }
            }
        }
    }

    private fun importPictures(uris: List<String>) {
        if (uris.isEmpty()) return
        var retaking = (_state.value.step as? ScanStep.Capture)?.retaking
        foreground = launchWork {
            busy(ScanBusy.Preparing) {
                var failed = 0
                var skipped = 0
                var lastAdded: String? = null
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    if (retaking == null && _state.value.pages.size >= MAX_SCAN_PAGES) {
                        skipped++
                        continue
                    }
                    val id = newId()
                    try {
                        val page = preparePage(id, uri)
                        placePage(page, retaking)
                        retaking = null
                        lastAdded = id
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { workspace.deletePage(id) }
                        throw e
                    } catch (_: DocumentException) {
                        workspace.deletePage(id)
                        failed++
                    }
                    persist()
                }
                val notes = listOfNotNull(
                    if (failed > 0) "$failed picture${if (failed == 1) "" else "s"} couldn't be read." else null,
                    if (skipped > 0) "A document can have $MAX_SCAN_PAGES pages; $skipped picture${if (skipped == 1) " wasn't" else "s weren't"} added." else null,
                )
                val added = lastAdded
                _state.update { current ->
                    current.copy(
                        step = when {
                            added == null -> current.step
                            uris.size == 1 -> ScanStep.Adjust(added)
                            else -> ScanStep.Pages
                        },
                        message = notes.joinToString(" ").ifEmpty { null },
                    )
                }
            }
        }
    }

    /** A camera picture that came out blurred, so the user can retake it before moving on. */
    private suspend fun looksBlurry(id: String): Boolean = try {
        val luma = workspace.sourceLuma(id, BLUR_CHECK_SIDE)
        withContext(computeDispatcher) { isBlurry(luma) }
    } catch (_: DocumentException) {
        false
    }

    /** Brings a picture in, looks for the page and draws the first output. */
    private suspend fun preparePage(id: String, uri: String): ScanPage {
        workspace.importSource(id, uri)
        val luma = workspace.sourceLuma(id, OUTLINE_SEARCH_SIDE)
        val found = withContext(computeDispatcher) { detectPageOutline(luma.bytes, luma.width, luma.height) }
        val page = ScanPage(id, found ?: PageQuad.FullFrame, detected = found != null)
        workspace.render(id, page.quad, page.filter)
        return page
    }

    private suspend fun placePage(page: ScanPage, replacing: String?) {
        _state.update { current ->
            val index = current.pages.indexOfFirst { it.id == replacing }
            current.copy(
                pages = if (index >= 0) {
                    current.pages.toMutableList().also { it[index] = page }
                } else {
                    current.pages + page
                },
            )
        }
        if (replacing != null) workspace.deletePage(replacing)
    }

    private fun atPageLimit(): Boolean {
        if (_state.value.pages.size < MAX_SCAN_PAGES) return false
        showMessage("A document can have $MAX_SCAN_PAGES pages. Save this one and start another.")
        return true
    }

    /** Applies a change to a page and redraws it; a newer change to the same page replaces a waiting one. */
    private fun edit(id: String, change: (ScanPage) -> ScanPage) {
        _state.update { current ->
            current.copy(
                pages = current.pages.map { if (it.id == id) change(it) else it },
                rendering = current.rendering + id,
            )
        }
        renders.remove(id)?.cancel()
        renders[id] = launchWork {
            val page = _state.value.pages.firstOrNull { it.id == id }
            try {
                if (page != null) {
                    workspace.render(id, page.quad, page.filter)
                    _state.update { current ->
                        current.copy(pages = current.pages.map { if (it.id == id) it.copy(version = it.version + 1) else it })
                    }
                    persist()
                }
            } finally {
                // When cancelled, a newer redraw of this page is waiting and still owns the flag.
                if (currentCoroutineContext().isActive) _state.update { it.copy(rendering = it.rendering - id) }
            }
        }
    }

    private fun move(id: String, by: Int) {
        _state.update { current ->
            val from = current.pages.indexOfFirst { it.id == id }
            if (from < 0) return@update current
            val to = (from + by).coerceIn(0, current.pages.lastIndex)
            current.copy(pages = current.pages.toMutableList().apply { add(to, removeAt(from)) })
        }
        launchWork { persist() }
    }

    private fun delete(id: String) {
        renders.remove(id)?.cancel()
        _state.update { current ->
            val pages = current.pages.filterNot { it.id == id }
            current.copy(pages = pages, rendering = current.rendering - id, step = if (pages.isEmpty()) ScanStep.Capture() else current.step)
        }
        launchWork {
            workspace.deletePage(id)
            persist()
        }
    }

    private fun save(target: String) {
        if (_state.value.busy != null) return
        foreground = launchWork {
            busy(ScanBusy.Writing) {
                val pages = _state.value.pages
                val saved = try {
                    val note: String?
                    val layers: List<List<PdfTextLine>>
                    if (_state.value.searchable) {
                        val read = readPages(pages)
                        layers = read.layers
                        note = read.note
                    } else {
                        layers = emptyList()
                        note = null
                    }
                    _state.update { it.copy(busy = ScanBusy.Writing) }
                    val images = workspace.readOutputs(pages.map { it.id }).map(::pdfPageOf)
                    pdf.imagesToPdf(images, PdfPageSize.FitImage, 0f, target, layers)
                    ScanSavedPdf(target, note)
                } catch (e: Throwable) {
                    // The save dialog already created the file; don't leave it empty or half-written.
                    withContext(NonCancellable) { workspace.deleteDocument(target) }
                    throw e
                }
                _state.update { ScanUiState(step = ScanStep.Saved, saved = saved) }
                workspace.clear()
            }
        }
    }

    private class ReadPages(val layers: List<List<PdfTextLine>>, val note: String?)

    /** A page that can't be read is saved without text rather than losing the document. */
    private suspend fun readPages(pages: List<ScanPage>): ReadPages {
        var failed = 0
        var found = false
        val layers = pages.mapIndexed { index, page ->
            _state.update { it.copy(busy = ScanBusy.Reading(index + 1, pages.size)) }
            try {
                val lines = recognizer.recognize(workspace.pictureUri(page.id, ScanPictureKind.Output)).lines
                if (lines.isNotEmpty()) found = true
                lines.map(::pdfTextLineOf)
            } catch (_: DocumentException) {
                failed++
                emptyList()
            }
        }
        val note = when {
            failed > 0 -> "Text couldn't be read on $failed page${if (failed == 1) "" else "s"}, so ${if (failed == 1) "it isn't" else "they aren't"} searchable."
            !found -> "No text was found, so this PDF isn't searchable."
            else -> null
        }
        return ReadPages(layers, note)
    }

    private suspend fun busy(what: ScanBusy, block: suspend () -> Unit) {
        _state.update { it.copy(busy = what) }
        try {
            block()
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    private fun discard() {
        val old = session
        old.coroutineContext.job.cancel()
        renders.clear()
        foreground = null
        session = newSession()
        val previousClear = clearing
        clearing = viewModelScope.launch {
            previousClear?.join()
            old.coroutineContext.job.join()
            work.withLock { workspace.clear() }
        }
        _state.value = ScanUiState()
    }
}

/**
 * The one place a page becomes a [PdfPageImage]. It loads the encoded picture
 * fully, which is why [MAX_SCAN_PAGES] exists.
 */
private fun pdfPageOf(encoded: ByteArray) = PdfPageImage(encoded)

private fun pdfTextLineOf(line: RecognizedLine) = PdfTextLine(line.text, line.left, line.top, line.right, line.bottom)
