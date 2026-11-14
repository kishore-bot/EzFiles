package com.ezfiles.reader.presentation.create

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberImagePicker
import com.ezfiles.reader.platform.rememberScanWorkspace
import com.ezfiles.reader.platform.rememberTextRecognizer
import com.ezfiles.reader.presentation.scan.CameraPanel
import com.ezfiles.reader.presentation.scan.ImageToTextAction
import com.ezfiles.reader.presentation.scan.ImageToTextBusy
import com.ezfiles.reader.presentation.scan.ImageToTextStep
import com.ezfiles.reader.presentation.scan.ImageToTextViewModel
import com.ezfiles.reader.presentation.scan.ScanButton
import com.ezfiles.reader.presentation.scan.ScanIcons
import com.ezfiles.reader.presentation.scan.ScanMessage
import com.ezfiles.reader.presentation.scan.ScanProgress
import com.ezfiles.reader.presentation.scan.TextSaveFormat
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.FeedbackOnAppear
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.launch

private const val SCRIPT_NOTICE =
    "Works on this device, with no internet. Reads Latin-script text (English, French, Spanish and so on). " +
        "Other scripts aren't recognized, and handwriting isn't supported."

@Composable
fun ImageToTextScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val workspace = rememberScanWorkspace("ocr")
    val recognizer = rememberTextRecognizer()
    val files = LocalDocumentFiles.current
    val viewModel = viewModel(key = "image-to-text") { ImageToTextViewModel(workspace, recognizer, files) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    val scope = rememberCoroutineScope()
    // What to do once the user agrees to lose the text: null while not asking.
    var discardThen by rememberSaveable { mutableStateOf<DiscardThen?>(null) }

    val close = {
        onAction(ImageToTextAction.Discard)
        onClose()
    }
    val back = {
        if (state.step == ImageToTextStep.Review && viewModel.text.isNotBlank()) discardThen = DiscardThen.Close else close()
    }
    PlatformBackHandler(enabled = true) {
        if (state.busy != null) onAction(ImageToTextAction.CancelWork) else back()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onAction(ImageToTextAction.Persist) }

    val saver = rememberDocumentSaver { uri -> uri?.let { onAction(ImageToTextAction.SaveTo(it)) } }
    val picker = rememberImagePicker(multiple = false) { uris ->
        uris.firstOrNull()?.let { onAction(ImageToTextAction.Picked(it)) }
    }
    val save = { format: TextSaveFormat ->
        onAction(ImageToTextAction.PrepareSave(format))
        saver.launch(format.suggestedName, format.mimeType)
    }

    Box(Modifier.fillMaxSize()) {
        CreateToolFrame("Image to text", back) {
            Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.message?.let { ScanMessage(it, { onAction(ImageToTextAction.DismissMessage) }) }
                when (state.step) {
                    ImageToTextStep.Source -> {
                        Text(SCRIPT_NOTICE, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        CameraPanel(
                            purpose = "The camera is used only to photograph the text, and the picture stays on this device. " +
                                "You can read a picture from your gallery without it.",
                            onCapture = { onAction(ImageToTextAction.TakePicture(it)) },
                            onImport = picker::launch,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    ImageToTextStep.Review -> if (state.noText) {
                        NoText({ onAction(ImageToTextAction.Again) }, Modifier.weight(1f))
                    } else {
                        @Suppress("DEPRECATION") // Its replacement needs a platform ClipEntry, which common code can't build.
                        val clipboard = LocalClipboardManager.current
                        OutlinedTextField(
                            viewModel.text,
                            { viewModel.text = it },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            label = { Text("Recognized text: check it for mistakes") },
                        )
                        Text(SCRIPT_NOTICE, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanButton(
                                "Copy",
                                { clipboard.setText(AnnotatedString(viewModel.text)) },
                                Modifier.weight(1f),
                                enabled = viewModel.text.isNotEmpty(),
                                icon = ScanIcons.Copy,
                            )
                            ScanButton("New picture", { discardThen = DiscardThen.NewPicture }, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanButton(
                                "Save .txt",
                                { save(TextSaveFormat.Text) },
                                Modifier.weight(1f),
                                primary = true,
                                enabled = viewModel.text.isNotBlank(),
                            )
                            // One main action per screen: the text file; the PDF is the alternative.
                            ScanButton(
                                "Save PDF",
                                { save(TextSaveFormat.Pdf) },
                                Modifier.weight(1f),
                                enabled = viewModel.text.isNotBlank(),
                            )
                        }
                    }
                    ImageToTextStep.Saved -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        FeedbackOnAppear(Unit) { confirm() }
                        Text("Saved", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
                        Text(
                            "The photo has been deleted from this app.",
                            color = EzColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                        ScanButton(
                            "Open file",
                            {
                                val uri = state.savedUri
                                if (uri != null) {
                                    scope.launch {
                                        try {
                                            val ref = files.refOf(uri)
                                            onAction(ImageToTextAction.Discard)
                                            onOpen(ref)
                                        } catch (_: DocumentException) {
                                            // The file is saved; it just can't be opened from here.
                                            close()
                                        }
                                    }
                                }
                            },
                            primary = true,
                        )
                        ScanButton("Read another picture", { onAction(ImageToTextAction.Again) })
                        ScanButton("Done", close)
                    }
                }
            }
        }
        state.busy?.let { busy ->
            ScanProgress(
                when (busy) {
                    ImageToTextBusy.Reading -> "Reading the text…"
                    ImageToTextBusy.Saving -> "Saving…"
                },
                onCancel = { onAction(ImageToTextAction.CancelWork) },
            )
        }
    }
    discardThen?.let { then ->
        EzDialog(
            onDismissRequest = { discardThen = null },
            title = { Text("Discard this text?") },
            text = { Text("It hasn't been saved. Copy or save it first if you want to keep it.") },
            confirmButton = {
                TextButton(onClick = {
                    discardThen = null
                    when (then) {
                        DiscardThen.Close -> close()
                        DiscardThen.NewPicture -> onAction(ImageToTextAction.Again)
                    }
                }) { Text("Discard", color = EzColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { discardThen = null }) { Text("Keep editing") }
            },
        )
    }
}

private enum class DiscardThen { Close, NewPicture }

@Composable
private fun NoText(onAgain: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(EzIcons.TextT, null, tint = EzColors.Primary)
        Text("No text found", style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        Text(
            "Nothing readable was found in this picture. Try a sharper, closer picture with good light. " +
                "Only Latin-script printed text can be read; handwriting isn't supported.",
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        ScanButton("Try another picture", onAgain, primary = true)
    }
}
