package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.presentation.theme.rememberEzHaptics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.viewers.ConfirmDialog
import com.ezfiles.reader.presentation.viewers.MessageDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun PdfToTextScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit, onImageToText: () -> Unit) {
    var edited by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    ConvertPdfGate(
        title = "PDF to text",
        intro = "Read text from a PDF, then edit, copy or save it.",
        onClose = { if (edited) leaving = onClose else onClose() },
    ) { ref, pages, chooseAnother ->
        when {
            !pages.canSearch -> ConvertResult(
                "Text reading unavailable",
                "This device cannot read text from this PDF.",
                onDone = onClose,
                doneLabel = "Back",
            )
            else -> TextContent(
                ref,
                pages,
                chooseAnother = { if (edited) leaving = chooseAnother else chooseAnother() },
                onEdited = { edited = it },
                onOpen,
                onImageToText,
            )
        }
    }
    PlatformBackHandler(enabled = edited) { leaving = onClose }
    leaving?.let { action ->
        ConfirmDialog(
            title = "Discard your edits?",
            message = "Your changes to this text haven't been saved.",
            confirm = "Discard",
            dismiss = "Keep editing",
            onConfirm = {
                leaving = null
                edited = false
                action()
            },
            onDismiss = { leaving = null },
        )
    }
}

private sealed interface TextStep {
    data class Working(val done: Int, val total: Int) : TextStep
    data object Stopped : TextStep
    data class Failed(val message: String) : TextStep
    data object NoText : TextStep
    data object Review : TextStep
}

@Composable
private fun TextContent(
    ref: DocumentRef,
    pages: PdfPages,
    chooseAnother: () -> Unit,
    onEdited: (Boolean) -> Unit,
    onOpen: (DocumentRef) -> Unit,
    onImageToText: () -> Unit,
) {
    val files = LocalDocumentFiles.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") // Its replacement needs a platform ClipEntry, which common code can't build.
    val clipboard = LocalClipboardManager.current
    var step by remember(pages) { mutableStateOf<TextStep>(TextStep.Working(0, pages.pageCount)) }
    var collector by remember(pages) { mutableStateOf<PdfTextCollector?>(null) }
    var text by remember(pages) { mutableStateOf("") }
    // What the field held when it last matched the PDF or a saved file.
    var baseline by remember(pages) { mutableStateOf("") }
    var separators by remember(pages) { mutableStateOf(false) }
    var job by remember(pages) { mutableStateOf<Job?>(null) }
    var askToggle by remember { mutableStateOf(false) }
    val haptics = rememberEzHaptics()
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var savedUri by remember(pages) { mutableStateOf<String?>(null) }
    val edited = step == TextStep.Review && text != baseline
    LaunchedEffect(edited) { onEdited(edited) }
    val rebuild = { withSeparators: Boolean ->
        separators = withSeparators
        text = collector?.assemble(withSeparators).orEmpty()
        baseline = text
    }

    LaunchedEffect(pages) {
        job = scope.launch {
            val total = pages.pageCount
            val found = PdfTextCollector(total)
            step = TextStep.Working(0, total)
            try {
                for (index in 0 until total) {
                    if (!found.add(index, pages.pageText(index).orEmpty())) break
                    step = TextStep.Working(index + 1, total)
                }
                collector = found
                rebuild(separators)
                step = if (found.hasText) TextStep.Review else TextStep.NoText
            } catch (e: CancellationException) {
                step = TextStep.Stopped
                throw e
            } catch (e: DocumentException) {
                step = TextStep.Failed(e.error.detail)
            } catch (_: RuntimeException) {
                step = TextStep.Failed("A page of this PDF couldn't be read.")
            }
        }
    }

    val saver = rememberDocumentSaver { uri ->
        if (uri != null) {
            val saving = text
            scope.launch {
                try {
                    files.write(uri, saving.encodeToByteArray())
                    baseline = saving
                    savedUri = uri
                } catch (e: DocumentException) {
                    message = "Couldn't save" to e.error.detail
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pages.textRecognitionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary) }
        when (val current = step) {
            is TextStep.Working -> {
                Column(Modifier.weight(1f)) {}
                ConvertProgress("Reading page ${current.done} of ${current.total}…", current.done.toLong(), current.total.toLong()) { job?.cancel() }
            }
            TextStep.Stopped -> ConvertResult("Cancelled", "No text was kept.", onDone = chooseAnother, doneLabel = "Choose another PDF")
            is TextStep.Failed -> ConvertResult("Couldn't read this PDF", current.message, onDone = chooseAnother, doneLabel = "Choose another PDF")
            TextStep.NoText -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConvertResult(
                    "No text found",
                    if (pages.usesTextRecognition) "No Latin-script text could be recognized. Page clarity and language affect recognition." else
                    "This PDF has no text layer, so it's probably a scan. Nothing was read from the pictures of its pages. " +
                        "Image to text can read a picture on this device: save a page with PDF to images first, then open it there.",
                    onDone = chooseAnother,
                    doneLabel = "Choose another PDF",
                )
                PrimaryActionButton("Go to Image to text", EzIcons.TextT, onImageToText, Modifier.fillMaxWidth())
            }
            TextStep.Review -> {
                collectorNote(collector, pages.pageCount)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text from ${ref.displayName}") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) {
                            if (edited) { haptics.activate(); askToggle = true }
                            else { haptics.toggle(!separators); rebuild(!separators) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Mark where each page starts", style = MaterialTheme.typography.bodyMedium, color = EzColors.Text, modifier = Modifier.weight(1f))
                    Switch(
                        checked = separators,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = EzColors.Primary, checkedThumbColor = EzColors.OnPrimary),
                    )
                }
                val saved = savedUri
                if (saved != null) {
                    ConvertResult(
                        "Saved",
                        null,
                        onDone = { savedUri = null },
                        doneLabel = "Keep editing",
                        outputRef = DocumentRef(saved, "${convertFileStem(ref.displayName)}.txt"),
                        onOpen = {
                            scope.launch {
                                try {
                                    onOpen(files.refOf(saved))
                                } catch (e: DocumentException) {
                                    message = "Couldn't open it" to e.error.detail
                                }
                            }
                        },
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EzTextButton("Copy", { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty())
                        EzTextButton("Another PDF", chooseAnother, color = EzColors.TextSecondary)
                        PrimaryActionButton("Save .txt", EzIcons.Document, { saver.launch("${convertFileStem(ref.displayName)}.txt", "text/plain") }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (askToggle) {
        ConfirmDialog(
            title = "Discard your edits?",
            message = "Changing this rebuilds the text from the PDF, which replaces what you've edited.",
            confirm = "Discard edits",
            dismiss = "Keep editing",
            onConfirm = {
                askToggle = false
                haptics.toggle(!separators)
                rebuild(!separators)
            },
            onDismiss = { askToggle = false },
        )
    }
    message?.let { (title, body) -> MessageDialog(title, body) { message = null } }
}

/** Says what the review doesn't hold: a limit reached, or pages with nothing to read. */
private fun collectorNote(collector: PdfTextCollector?, pageCount: Int): String? {
    collector ?: return null
    val cut = collector.cutAtPage
    return when {
        cut != null -> "This is the first ${MAX_EXTRACTED_CHARS / 1000},000 characters, the most EzFiles holds at once. It stops on page ${cut + 1} of $pageCount."
        collector.blankPages > 0 -> "${collector.blankPages} of $pageCount pages had no text, so they may be scans. Nothing was read from their pictures."
        else -> null
    }
}
