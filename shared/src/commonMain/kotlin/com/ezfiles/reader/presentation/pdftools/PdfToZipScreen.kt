package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalPdfConverter
import com.ezfiles.reader.platform.PageImageFormat
import com.ezfiles.reader.platform.PdfZipWriter
import com.ezfiles.reader.platform.rememberDocumentPicker
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberWritableFolders
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ZipKind { Document, PageImages }

private sealed interface ZipStep {
    data object Choose : ZipStep

    /** [total] of `null` means the length isn't known. */
    data class Working(val label: String, val done: Long, val total: Long?) : ZipStep

    /** Built in private storage, waiting for the user to choose where it goes. */
    data class Ready(val before: Long?, val after: Long, val kind: ZipKind) : ZipStep
    data class Saved(val after: Long, val uri: String) : ZipStep
    data class Failed(val message: String) : ZipStep
}

/**
 * Two honest ways to a ZIP. The archive is built privately first, so the sizes
 * can be shown before the user decides to keep it.
 */
@Composable
fun PdfToZipScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val converter = LocalPdfConverter.current
    val folders = rememberWritableFolders()
    val scope = rememberCoroutineScope()
    val initialRef = LocalPdfToolsPreselect.current ?: LocalPdfToolsPreselectList.current.firstOrNull()
    var ref by remember { mutableStateOf(initialRef) }
    var password by remember(ref) { mutableStateOf<String?>(null) }
    var kind by remember(ref) { mutableStateOf(ZipKind.Document) }
    var format by remember { mutableStateOf(PageImageFormat.Jpeg) }
    var dpi by remember { mutableIntStateOf(150) }
    var step by remember(ref) { mutableStateOf<ZipStep>(ZipStep.Choose) }
    var job by remember { mutableStateOf<Job?>(null) }
    var writer by remember { mutableStateOf<PdfZipWriter?>(null) }
    var askPassword by remember(ref) { mutableStateOf(false) }

    val picker = rememberDocumentPicker { picked -> if (picked != null) ref = picked }
    val load = rememberConvertPdf(ref, password)
    val current = ref
    val startOver = {
        writer?.discard()
        writer = null
        step = ZipStep.Choose
    }
    DisposableEffect(Unit) { onDispose { writer?.discard() } }

    val saver = rememberDocumentSaver { uri ->
        val ready = step as? ZipStep.Ready
        val archive = writer
        if (uri != null && ready != null && archive != null) {
            step = ZipStep.Working("Saving the ZIP…", 0, null)
            job = scope.launch {
                step = try {
                    archive.copyTo(uri)
                    ZipStep.Saved(ready.after, uri)
                } catch (e: CancellationException) {
                    // The archive is still here to save again, and no half-copied file should stay behind.
                    withContext(NonCancellable) { folders.delete(uri) }
                    step = ready
                    throw e
                } catch (e: DocumentException) {
                    ZipStep.Failed(e.error.detail)
                }
            }
        }
    }

    val build: (DocumentRef, ZipKind) -> Unit = { source, chosen ->
        step = ZipStep.Working("Starting…", 0, null)
        job = scope.launch {
            try {
                val archive = converter.newZip()
                writer = archive
                val stem = convertFileStem(source.displayName)
                var before = source.sizeBytes
                if (chosen == ZipKind.Document) {
                    before = archive.addDocument(source, "$stem.pdf") { bytes ->
                        step = ZipStep.Working("Compressing…", bytes, source.sizeBytes)
                    }
                } else {
                    val pages = (load as ConvertPdfLoad.Open).pages
                    val result = exportPagesOneByOne(
                        List(pages.pageCount) { it },
                        { encodePageImage(pages, converter, it, dpi, format) },
                        { page, bytes -> archive.addStored(pageImageFileName(stem, page, pages.pageCount, format), bytes) },
                        { done, total -> step = ZipStep.Working("Adding page $done of $total…", done.toLong(), total.toLong()) },
                    )
                    if (result.failure != null) {
                        throw DocumentException(DocumentError.Internal("${result.failure} Nothing was saved."))
                    }
                }
                step = ZipStep.Ready(before, archive.finish(), chosen)
            } catch (e: CancellationException) {
                writer?.discard()
                writer = null
                step = ZipStep.Choose
                throw e
            } catch (e: DocumentException) {
                writer?.discard()
                writer = null
                step = ZipStep.Failed(e.error.detail)
            }
        }
    }

    CreateToolFrame("PDF to ZIP", onClose) {
        Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                current == null -> ConvertPickPrompt("Put a PDF in a ZIP, or make a ZIP of pictures of its pages. The PDF isn't changed.", picker::launch)
                load == ConvertPdfLoad.Idle || load == ConvertPdfLoad.Loading -> ConvertBusy()
                load is ConvertPdfLoad.Failed && !load.isPdf -> ConvertPickPrompt(load.message, picker::launch)
                else -> when (val state = step) {
                    ZipStep.Choose -> {
                        // A protected file can be archived whole without opening it; its pictures need the password.
                        val pictures = load is ConvertPdfLoad.Open || load is ConvertPdfLoad.NeedsPassword
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(current.displayName, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                            current.sizeBytes?.let {
                                Text(formatFileSize(it), style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                            }
                            ConvertOption(
                                "Compress the PDF into a .zip",
                                "The same PDF, inside a ZIP. PDFs barely shrink, because they're compressed inside already. A password stays in place.",
                                kind == ZipKind.Document,
                            ) { kind = ZipKind.Document }
                            ConvertOption(
                                "Page images in a .zip",
                                if (pictures) "One picture for each page. The text stops being searchable." else (load as? ConvertPdfLoad.Failed)?.message.orEmpty(),
                                kind == ZipKind.PageImages,
                                enabled = pictures,
                            ) { kind = ZipKind.PageImages }
                            if (kind == ZipKind.PageImages) PageImageOptions(format, { format = it }, dpi, { dpi = it })
                        }
                        PrimaryActionButton("Make the ZIP", EzIcons.Folder, {
                            when {
                                kind == ZipKind.Document -> build(current, ZipKind.Document)
                                load is ConvertPdfLoad.Open -> build(current, ZipKind.PageImages)
                                else -> askPassword = true
                            }
                        }, Modifier.fillMaxWidth())
                    }
                    is ZipStep.Working -> {
                        Column(Modifier.weight(1f)) {}
                        ConvertProgress(state.label, state.done, state.total) { job?.cancel() }
                    }
                    is ZipStep.Ready -> {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ready to save", style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
                            state.before?.let {
                                Text("PDF: ${formatFileSize(it)}", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                            }
                            Text("ZIP: ${formatFileSize(state.after)}", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                            Text(
                                zipSizeNote(state.before, state.after, state.kind == ZipKind.PageImages),
                                style = MaterialTheme.typography.bodyMedium,
                                color = EzColors.TextSecondary,
                            )
                        }
                        PrimaryActionButton("Save the ZIP", EzIcons.Folder, { saver.launch("${convertFileStem(current.displayName)}.zip", "application/zip") }, Modifier.fillMaxWidth())
                    }
                    is ZipStep.Saved -> ConvertResult(
                        "Saved the ZIP",
                        "${formatFileSize(state.after)}, where you chose.",
                        outputRef = DocumentRef(state.uri, "${convertFileStem(current.displayName)}.zip", state.after),
                        onDone = {
                            startOver()
                            ref = null
                        },
                    )
                    is ZipStep.Failed -> ConvertResult(
                        "Couldn't make the ZIP",
                        state.message,
                        onDone = startOver,
                        doneLabel = "Try again",
                    )
                }
            }
        }
    }
    (load as? ConvertPdfLoad.NeedsPassword)?.let { locked ->
        if (askPassword || locked.incorrect) {
            ConvertPasswordDialog(
                locked.incorrect,
                onUnlock = {
                    password = it
                    askPassword = false
                },
                onDismiss = {
                    askPassword = false
                    password = null
                },
            )
        }
    }
}
