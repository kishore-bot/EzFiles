package com.ezfiles.reader.presentation.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.TextRecognizer
import com.ezfiles.reader.platform.CameraCapturer
import com.ezfiles.reader.platform.DocumentFiles
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
enum class TextSaveFormat(val suggestedName: String, val mimeType: String) {
    Text("Scanned text.txt", "text/plain"),
    Pdf("Scanned text.pdf", "application/pdf"),
}

enum class ImageToTextStep { Source, Review, Saved }

enum class ImageToTextBusy { Reading, Saving }

data class ImageToTextState(
    val step: ImageToTextStep = ImageToTextStep.Source,
    val busy: ImageToTextBusy? = null,
    /** The picture was read and held no text. */
    val noText: Boolean = false,
    val message: String? = null,
    val savedUri: String? = null,
)

sealed interface ImageToTextAction {
    data class TakePicture(val capturer: CameraCapturer) : ImageToTextAction

    data class Picked(val uri: String) : ImageToTextAction

    /** Back to the camera for another picture. */
    data object Again : ImageToTextAction

    /** Stops reading or saving. */
    data object CancelWork : ImageToTextAction

    /** Remembers the format while the system's save dialog is up, in case the process is killed behind it. */
    data class PrepareSave(val format: TextSaveFormat) : ImageToTextAction

    data class SaveTo(val target: String) : ImageToTextAction

    /** The app is going to the background: keep the edited text on disk. */
    data object Persist : ImageToTextAction

    data object DismissMessage : ImageToTextAction

    /** Ends the session: the photo and text go. */
    data object Discard : ImageToTextAction
}

@Serializable
private class SavedReview(val text: String, val noText: Boolean, val format: TextSaveFormat)

private const val PHOTO_ID = "photo"

/** Reads one picture at a time; the photo and text stay in the app-private workspace until saved. */
class ImageToTextViewModel(
    private val workspace: ScanWorkspace,
    private val recognizer: TextRecognizer,
    private val files: DocumentFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(ImageToTextState())
    val state: StateFlow<ImageToTextState> = _state.asStateFlow()

    /** Snapshot state rather than the flow, so typing doesn't lag behind and jump the cursor. */
    var text by mutableStateOf("")

    private val work = Mutex()
    private var job: Job? = null
    private var format = TextSaveFormat.Text

    init {
        viewModelScope.launch { work.withLock { restore() } }
    }

    fun onAction(action: ImageToTextAction) {
        when (action) {
            is ImageToTextAction.TakePicture -> read { capture(action.capturer) }
            is ImageToTextAction.Picked -> read { action.uri }
            ImageToTextAction.Again -> reset(ImageToTextState())
            ImageToTextAction.CancelWork -> job?.cancel()
            is ImageToTextAction.PrepareSave -> {
                format = action.format
                persist()
            }
            is ImageToTextAction.SaveTo -> save(action.target)
            ImageToTextAction.Persist -> persist()
            ImageToTextAction.DismissMessage -> _state.update { it.copy(message = null) }
            ImageToTextAction.Discard -> reset(ImageToTextState())
        }
    }

    private suspend fun restore() {
        val saved = workspace.readState() ?: return
        val review = try {
            Json.decodeFromString<SavedReview>(saved)
        } catch (_: SerializationException) {
            workspace.clear()
            return
        } catch (_: IllegalArgumentException) {
            workspace.clear()
            return
        }
        text = review.text
        format = review.format
        _state.update { it.copy(step = ImageToTextStep.Review, noText = review.noText) }
    }

    private fun persist() {
        if (_state.value.step != ImageToTextStep.Review) return
        val snapshot = SavedReview(text, _state.value.noText, format)
        viewModelScope.launch {
            work.withLock {
                try {
                    workspace.writeState(Json.encodeToString(snapshot))
                } catch (_: DocumentException) {
                    // The text is still on screen; this copy only matters if the process dies.
                }
            }
        }
    }

    private suspend fun capture(capturer: CameraCapturer): String {
        val target = workspace.captureTarget(PHOTO_ID)
        capturer.capture(target)
        return target
    }

    private fun read(source: suspend () -> String) {
        if (_state.value.busy != null) return
        job = viewModelScope.launch {
            work.withLock {
                _state.update { it.copy(busy = ImageToTextBusy.Reading, message = null) }
                try {
                    workspace.importSource(PHOTO_ID, source())
                    val result = recognizer.recognize(workspace.pictureUri(PHOTO_ID, ScanPictureKind.Source))
                    text = result.text
                    _state.update {
                        it.copy(step = ImageToTextStep.Review, noText = result.text.isBlank())
                    }
                    workspace.writeState(Json.encodeToString(SavedReview(result.text, result.text.isBlank(), format)))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { workspace.clear() }
                    throw e
                } catch (e: DocumentException) {
                    workspace.clear()
                    _state.update { it.copy(message = e.error.detail) }
                } catch (_: Throwable) {
                    workspace.clear()
                    _state.update { it.copy(message = "This picture couldn't be read. Try a smaller one.") }
                } finally {
                    _state.update { it.copy(busy = null) }
                }
            }
        }
    }

    private fun save(target: String) {
        if (_state.value.busy != null) return
        val content = text
        val chosen = format
        job = viewModelScope.launch {
            work.withLock {
                _state.update { it.copy(busy = ImageToTextBusy.Saving, message = null) }
                try {
                    when (chosen) {
                        TextSaveFormat.Text -> files.write(target, content.encodeToByteArray())
                        TextSaveFormat.Pdf -> workspace.writeTextPdf(content, target)
                    }
                    workspace.clear()
                    _state.update { it.copy(step = ImageToTextStep.Saved, savedUri = target) }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { workspace.deleteDocument(target) }
                    throw e
                } catch (e: DocumentException) {
                    workspace.deleteDocument(target)
                    _state.update { it.copy(message = e.error.detail) }
                } catch (_: Throwable) {
                    workspace.deleteDocument(target)
                    _state.update { it.copy(message = "The file couldn't be saved. Your text is still here.") }
                } finally {
                    _state.update { it.copy(busy = null) }
                }
            }
        }
    }

    private fun reset(next: ImageToTextState) {
        job?.cancel()
        text = ""
        format = TextSaveFormat.Text
        _state.value = next
        viewModelScope.launch { work.withLock { workspace.clear() } }
    }
}
