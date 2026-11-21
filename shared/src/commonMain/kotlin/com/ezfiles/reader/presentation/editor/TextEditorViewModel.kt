package com.ezfiles.reader.presentation.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezfiles.app.currentTimeMillis
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.DocumentFiles
import com.ezfiles.reader.presentation.viewers.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface EditorPhase {
    data object Idle : EditorPhase
    data object Loading : EditorPhase
    data object Editing : EditorPhase

    /** The file stays untouched and can't be edited; [detail] says why. */
    data class Refused(val title: String, val detail: String) : EditorPhase
}

enum class EditorPrompt {
    /** Back with unsaved changes: Save, Discard or Keep editing. */
    UnsavedChanges,

    /** Something else changed the file since it was loaded: Overwrite, Save As or Keep editing. */
    ChangedOnDisk,
}

enum class EditorNotice(val message: String, val isError: Boolean = true) {
    Saved("Saved", isError = false),
    TooLong("That would make the text longer than this editor can handle, so it wasn't added."),
    CannotEncode("This text has characters this file's encoding can't store, so nothing was saved."),
    WriteAccessLost("Xfiles can't write to this file any more. It wasn't changed. Use Save As to keep your text."),
    CheckFailed("Couldn't check the file before saving, so it wasn't changed. Try again or use Save As."),
    WriteFailedUnchanged("Couldn't save. The file is unchanged and your text is still here."),
    WritePartial("Saving stopped part way, so the file may be incomplete. Your text is still here: try again or use Save As."),
    WriteUnconfirmed("Couldn't confirm the save, so the file may be incomplete. Your text is still here: try again or use Save As."),
    SaveAsFailed("Couldn't save the new file, which may be empty or incomplete. Your text is still here: try again or choose another place."),
}

data class EditorUiState(
    val phase: EditorPhase = EditorPhase.Idle,
    val name: String = "",
    val field: TextFieldValue = TextFieldValue(),
    val format: TextFileFormat = TextFileFormat.New,
    val modified: Boolean = false,
    /** "Save" may replace the file; otherwise only "Save As" is offered. */
    val canOverwrite: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** A save is running: the text is read-only until it ends. */
    val busy: Boolean = false,
    val prompt: EditorPrompt? = null,
    val notice: EditorNotice? = null,
    /** The editor should leave; collect the result with [TextEditorViewModel.takeResult]. */
    val finished: Boolean = false,
)

/**
 * The editing session. It lives here rather than in saved instance state,
 * because this much text would overflow the saved-state parcel when the app
 * goes to the background, and so that the text survives the screen being
 * replaced for a while, such as when another app hands over a file. It does not
 * survive the process being killed.
 *
 * The text field needs its value back synchronously, so the state is Compose
 * state rather than a flow.
 */
class TextEditorViewModel(
    private val files: DocumentFiles,
    private val now: () -> Long = ::currentTimeMillis,
) : ViewModel() {
    var state by mutableStateOf(EditorUiState())
        private set

    private var history = EditHistory()
    private var loadJob: Job? = null
    private var isNewFile = false

    /** The document being edited; `null` for a new file not saved yet. */
    private var ref: DocumentRef? = null

    /** What the file is believed to hold, to notice changes made by something else. */
    private var baseline = ByteArray(0)

    /** The text the file holds; `null` after a failed save, when that isn't known. */
    private var savedText: String? = ""

    /** The latest successful save, for the reader to show. */
    private var savedRef: DocumentRef? = null
    private var closeAfterSave = false
    private var saveAsBytes: ByteArray? = null
    private var saveAsText = ""
    private var saveAsName = ""

    /**
     * Loads [document] unless it is already being edited. [knownSize] is the
     * file's real size, checked first because reading loads the whole file.
     */
    fun open(document: DocumentRef, knownSize: Long?) {
        if (state.phase != EditorPhase.Idle) return
        ref = document
        state = EditorUiState(phase = EditorPhase.Loading, name = document.displayName)
        if (knownSize != null && knownSize > MAX_EDITABLE_BYTES) {
            state = state.copy(phase = tooLarge(knownSize))
            return
        }
        loadJob = viewModelScope.launch {
            val bytes = try {
                files.read(document.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentException) {
                state = state.copy(phase = EditorPhase.Refused("Couldn't open the file", e.error.detail))
                return@launch
            } catch (_: RuntimeException) {
                state = state.copy(phase = EditorPhase.Refused("Couldn't open the file", "The app that provides this file reported an error."))
                return@launch
            }
            if (bytes.size > MAX_EDITABLE_BYTES) {
                state = state.copy(phase = tooLarge(bytes.size.toLong()))
                return@launch
            }
            when (val decoded = TextFileCodec.decode(bytes)) {
                is TextDecodeResult.Refused -> state = state.copy(phase = refusal(decoded.reason))
                is TextDecodeResult.Decoded -> {
                    baseline = bytes
                    savedText = decoded.text
                    state = state.copy(
                        phase = EditorPhase.Editing,
                        field = TextFieldValue(decoded.text),
                        format = decoded.format,
                        canOverwrite = files.canWrite(document.uri),
                    )
                }
            }
        }
    }

    /** Starts an empty file, unless one is already being written. */
    fun startNew() {
        if (state.phase != EditorPhase.Idle) return
        isNewFile = true
        state = EditorUiState(phase = EditorPhase.Editing)
    }

    fun onFieldChange(value: TextFieldValue) {
        val old = state.field
        if (state.phase != EditorPhase.Editing || state.busy) return
        val field = normalized(value)
        if (field.text == old.text) {
            if (field.selection != old.selection) history.seal()
            state = state.copy(field = field)
            return
        }
        if (field.text.length > MAX_EDITABLE_BYTES && field.text.length > old.text.length) {
            state = state.copy(notice = EditorNotice.TooLong)
            return
        }
        history.record(old.snapshot(), field.snapshot(), now())
        showText(field)
    }

    fun undo() {
        if (state.busy) return
        history.undo(state.field.snapshot())?.let { showText(it.toField()) } ?: refreshHistory()
    }

    fun redo() {
        if (state.busy) return
        history.redo(state.field.snapshot())?.let { showText(it.toField()) } ?: refreshHistory()
    }

    /**
     * Replaces the file, after encoding the text and checking that nothing else
     * changed it. [force] skips that check once the user has chosen to overwrite.
     */
    fun save(force: Boolean = false, closeAfter: Boolean = false) {
        val target = ref ?: return
        if (state.busy || !state.canOverwrite) return
        val text = state.field.text
        val bytes = encoded(text) ?: return
        closeAfterSave = closeAfter
        state = state.copy(busy = true, prompt = null, notice = null)
        viewModelScope.launch {
            try {
                if (!files.canWrite(target.uri)) {
                    state = state.copy(canOverwrite = false, notice = EditorNotice.WriteAccessLost)
                    return@launch
                }
                if (!force) {
                    val onDisk = readOrNull(target.uri)
                    if (onDisk == null) {
                        state = state.copy(notice = EditorNotice.CheckFailed)
                        return@launch
                    }
                    if (!onDisk.contentEquals(baseline)) {
                        state = state.copy(prompt = EditorPrompt.ChangedOnDisk)
                        return@launch
                    }
                }
                if (writeVerified(target.uri, bytes)) {
                    val fresh = refOrNull(target.uri)
                    // Same identity, so the recents entry is updated rather than doubled.
                    val updated = target.copy(
                        sizeBytes = fresh?.sizeBytes ?: bytes.size.toLong(),
                        modifiedAt = fresh?.modifiedAt,
                    )
                    saved(updated, text, bytes)
                }
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    /**
     * Encodes the text ahead of the system save dialog. `false` means it can't
     * be stored, and the dialog must not open, so no empty file is left behind.
     */
    fun prepareSaveAs(suggestedName: String, closeAfter: Boolean = false): Boolean {
        if (state.busy) return false
        val text = state.field.text
        val bytes = encoded(text) ?: return false
        saveAsBytes = bytes
        saveAsText = text
        saveAsName = suggestedName
        closeAfterSave = closeAfter || isNewFile
        state = state.copy(prompt = null, notice = null)
        return true
    }

    /** The save dialog closed; [uri] is the chosen file, or `null` if dismissed. */
    fun onSaveAsResult(uri: String?) {
        val bytes = saveAsBytes
        saveAsBytes = null
        if (uri == null || bytes == null) return
        val text = saveAsText
        val name = saveAsName
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                if (writeVerified(uri, bytes, isCopy = true)) {
                    saved(refOrNull(uri) ?: DocumentRef(uri, name, bytes.size.toLong()), text, bytes)
                }
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    /** Back: leaves at once if nothing would be lost, otherwise asks. */
    fun requestClose() {
        if (state.busy) return
        if (state.modified) state = state.copy(prompt = EditorPrompt.UnsavedChanges) else finish()
    }

    fun discard() = finish()

    fun dismissPrompt() {
        closeAfterSave = false
        state = state.copy(prompt = null)
    }

    /** Whether to close once a save finishes, kept across the changed-on-disk prompt. */
    val closesAfterSave: Boolean get() = closeAfterSave

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    /** Hands over the latest saved document, if any, and ends the session. */
    fun takeResult(): DocumentRef? {
        val result = savedRef
        loadJob?.cancel()
        history = EditHistory()
        isNewFile = false
        ref = null
        baseline = ByteArray(0)
        savedText = ""
        savedRef = null
        closeAfterSave = false
        saveAsBytes = null
        saveAsText = ""
        state = EditorUiState()
        return result
    }

    private fun finish() {
        state = state.copy(prompt = null, finished = true)
    }

    private fun showText(field: TextFieldValue) {
        state = state.copy(
            field = field,
            modified = field.text != savedText,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            notice = state.notice?.takeIf { it.isError },
        )
    }

    private fun refreshHistory() {
        state = state.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    private fun encoded(text: String): ByteArray? {
        val bytes = TextFileCodec.encode(text, state.format)
        if (bytes == null) {
            closeAfterSave = false
            state = state.copy(prompt = null, notice = EditorNotice.CannotEncode)
        }
        return bytes
    }

    /**
     * Writes and reads the result back. Bytes that arrived count as saved even
     * if the provider reported an error on closing. A write that fails part way
     * can't be undone, because the file is truncated when it is opened, so the
     * notice says what state it was left in and the text stays in the editor.
     */
    private suspend fun writeVerified(uri: String, bytes: ByteArray, isCopy: Boolean = false): Boolean {
        val writeFailed = try {
            files.write(uri, bytes)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        val after = readOrNull(uri)
        if (after != null && after.contentEquals(bytes)) return true
        val notice = when {
            isCopy -> EditorNotice.SaveAsFailed
            after == null -> EditorNotice.WriteUnconfirmed
            after.contentEquals(baseline) -> EditorNotice.WriteFailedUnchanged
            writeFailed -> EditorNotice.WritePartial
            else -> EditorNotice.WriteUnconfirmed
        }
        if (!isCopy && notice != EditorNotice.WriteFailedUnchanged) {
            // The file no longer matches any text here: keep Save and the Back prompt
            // available even if undo returns to the old text, and don't report this
            // as someone else's change on retry.
            savedText = null
            if (after != null) baseline = after
        }
        state = state.copy(notice = notice, modified = state.field.text != savedText)
        return false
    }

    private suspend fun saved(document: DocumentRef, text: String, bytes: ByteArray) {
        ref = document
        savedRef = document
        baseline = bytes
        savedText = text
        history.seal()
        state = state.copy(
            name = document.displayName,
            modified = state.field.text != text,
            // A copy can be replaced only if its own provider allows it.
            canOverwrite = files.canWrite(document.uri),
            notice = EditorNotice.Saved,
        )
        if (closeAfterSave) finish()
    }

    private suspend fun readOrNull(uri: String): ByteArray? = try {
        files.read(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun refOrNull(uri: String): DocumentRef? = try {
        files.refOf(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun tooLarge(size: Long) = EditorPhase.Refused(
        "This file is too large to edit",
        "It is ${formatBytes(size)}, and Xfiles can edit text files up to ${formatBytes(MAX_EDITABLE_BYTES.toLong())} " +
            "so that typing stays responsive. You can still read it.",
    )

    private fun refusal(reason: TextRefusal) = when (reason) {
        TextRefusal.InvalidEncoding -> EditorPhase.Refused(
            "This file can't be edited safely",
            "It isn't valid UTF-8, or UTF-16 with a byte order mark, so saving it could damage it. It wasn't changed.",
        )
        TextRefusal.NotText -> EditorPhase.Refused(
            "This file can't be edited safely",
            "It contains binary data or an encoding Xfiles can't edit, so saving it could damage it. It wasn't changed.",
        )
        TextRefusal.MixedLineEndings -> EditorPhase.Refused(
            "This file can't be edited safely",
            "It mixes different kinds of line ending, so saving it would change lines you didn't edit. It wasn't changed.",
        )
        TextRefusal.NotReproducible -> EditorPhase.Refused(
            "This file can't be edited safely",
            "Xfiles couldn't reproduce it exactly, so saving it could damage it. It wasn't changed.",
        )
    }

    private fun TextFieldValue.snapshot() = TextSnapshot(text, selection.start, selection.end)

    private fun TextSnapshot.toField() = TextFieldValue(text, TextRange(selectionStart, selectionEnd))

    /** Pasted text can bring `\r`, which would double up when the file's line ending is applied. */
    private fun normalized(value: TextFieldValue): TextFieldValue {
        if ('\r' !in value.text) return value
        fun mapped(offset: Int) = TextFileCodec.normalizeLineEndings(value.text.substring(0, offset)).length
        return TextFieldValue(
            TextFileCodec.normalizeLineEndings(value.text),
            TextRange(mapped(value.selection.start), mapped(value.selection.end)),
        )
    }
}
