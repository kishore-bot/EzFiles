package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.LocalPdfConverter
import com.ezfiles.reader.platform.PageImageFormat
import com.ezfiles.reader.platform.PdfConverter
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberWritableFolderPicker
import com.ezfiles.reader.platform.rememberWritableFolders
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ImagesStep {
    data object Choose : ImagesStep
    data class Working(val done: Int, val total: Int) : ImagesStep
    data class Finished(val title: String, val message: String?, val singleFile: String? = null) : ImagesStep
}

@Composable
fun PdfToImagesScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    ConvertPdfGate(
        title = "PDF to images",
        intro = "Save the pages of a PDF as JPEG or PNG pictures. The PDF isn't changed.",
        onClose = onClose,
    ) { ref, pages, chooseAnother ->
        ImagesContent(ref, pages, chooseAnother, onOpen)
    }
}

/** Renders one page at the chosen resolution and encodes it. Nothing is cached, so the pixels are freed right after. */
internal suspend fun encodePageImage(pages: PdfPages, converter: PdfConverter, index: Int, dpi: Int, format: PageImageFormat): ByteArray {
    val width = pages.widthPoints(index) ?: 612f
    val size = exportImageSize(width, width / pages.aspectRatio(index), dpi)
    val image = pages.renderForExport(index, size.width)
        ?: throw DocumentException(DocumentError.Internal("The PDF was closed."))
    return converter.encodeAndRelease(image, format)
}

@Composable
private fun ImagesContent(ref: DocumentRef, pages: PdfPages, chooseAnother: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val files = LocalDocumentFiles.current
    val converter = LocalPdfConverter.current
    val folders = rememberWritableFolders()
    val scope = rememberCoroutineScope()
    var allPages by remember { mutableStateOf(true) }
    var rangeText by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PageImageFormat.Jpeg) }
    var dpi by remember { mutableIntStateOf(150) }
    var step by remember { mutableStateOf<ImagesStep>(ImagesStep.Choose) }
    var job by remember { mutableStateOf<Job?>(null) }

    val range = if (allPages) ConvertPageRange.Pages(List(pages.pageCount) { it }) else parseConvertPageRange(rangeText, pages.pageCount)
    val chosen = (range as? ConvertPageRange.Pages)?.indices.orEmpty()
    val stem = convertFileStem(ref.displayName)
    val encode: suspend (Int) -> ByteArray = { encodePageImage(pages, converter, it, dpi, format) }

    val saveOne: (String, Int) -> Unit = { target, page ->
        step = ImagesStep.Working(0, 1)
        job = scope.launch {
            val result = try {
                exportPagesOneByOne(
                    listOf(page),
                    encode,
                    { _, bytes -> files.write(target, bytes) },
                    { done, total -> step = ImagesStep.Working(done, total) },
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { folders.delete(target) }
                step = ImagesStep.Finished("Cancelled", "Nothing was saved.")
                throw e
            }
            step = if (result.failure == null) {
                ImagesStep.Finished("Saved 1 picture", null, target)
            } else {
                folders.delete(target)
                ImagesStep.Finished("Couldn't save the picture", result.failure)
            }
        }
    }
    val saveToFolder: (String, List<Int>) -> Unit = { folder, chosenPages ->
        step = ImagesStep.Working(0, chosenPages.size)
        job = scope.launch {
            var written = 0
            val place = folders.displayName(folder)?.let { " in $it" }.orEmpty()
            try {
                val result = exportPagesOneByOne(
                    chosenPages,
                    encode,
                    { page, bytes ->
                        val name = pageImageFileName(stem, page, pages.pageCount, format)
                        val file = folders.createFile(folder, name, format.mimeType)
                        try {
                            files.write(file, bytes)
                        } catch (e: Exception) {
                            // Half a picture shouldn't look like a finished one.
                            withContext(NonCancellable) { folders.delete(file) }
                            throw e
                        }
                    },
                    { done, total ->
                        written = done
                        step = ImagesStep.Working(done, total)
                    },
                )
                step = if (result.failure == null) {
                    ImagesStep.Finished("Saved ${result.written} files", "They're$place, named like ${pageImageFileName(stem, chosenPages.first(), pages.pageCount, format)}.")
                } else {
                    ImagesStep.Finished(
                        "Stopped after ${result.written} of ${result.total} files",
                        result.failure + if (result.written > 0) " The files already written are$place." else " No files were written.",
                    )
                }
            } catch (e: CancellationException) {
                step = ImagesStep.Finished("Cancelled after $written of ${chosenPages.size} files", if (written > 0) "The files already written are$place." else "No files were written.")
                throw e
            }
        }
    }

    var waitingPage by remember { mutableStateOf<Int?>(null) }
    val saver = rememberDocumentSaver { uri ->
        val page = waitingPage
        waitingPage = null
        if (uri != null && page != null) saveOne(uri, page)
    }
    var waitingPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    val folderPicker = rememberWritableFolderPicker(
        onPicked = { folder -> saveToFolder(folder, waitingPages) },
        onUnavailable = { step = ImagesStep.Finished("No folder picker", "This device has no way to choose a folder. Choose a single page to save instead.") },
    )

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val current = step) {
            ImagesStep.Choose -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(ref.displayName, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Text("${pages.pageCount} ${if (pages.pageCount == 1) "page" else "pages"}", style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                    Text("Pages", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EzChip("All pages", allPages, { allPages = true })
                        EzChip("Some pages", !allPages, { allPages = false })
                    }
                    if (!allPages) {
                        OutlinedTextField(
                            value = rangeText,
                            onValueChange = { rangeText = it },
                            singleLine = true,
                            label = { Text("Pages, like 1-3, 5") },
                            isError = range is ConvertPageRange.Invalid && rangeText.isNotBlank(),
                            supportingText = (range as? ConvertPageRange.Invalid)?.takeIf { rangeText.isNotBlank() }?.let { { Text(it.message) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PageImageOptions(format, { format = it }, dpi, { dpi = it })
                }
                if (chosen.size == 1) {
                    PrimaryActionButton("Save the picture", EzIcons.Image, {
                        waitingPage = chosen.first()
                        saver.launch(pageImageFileName(stem, chosen.first(), pages.pageCount, format), format.mimeType)
                    }, Modifier.fillMaxWidth())
                } else if (chosen.isNotEmpty() && folderPicker != null) {
                    PrimaryActionButton("Choose a folder for ${chosen.size} pictures", EzIcons.Folder, {
                        waitingPages = chosen
                        folderPicker()
                    }, Modifier.fillMaxWidth())
                }
            }
            is ImagesStep.Working -> {
                Column(Modifier.weight(1f)) {}
                ConvertProgress("Saving page ${current.done} of ${current.total}…", current.done.toLong(), current.total.toLong()) { job?.cancel() }
            }
            is ImagesStep.Finished -> ConvertResult(
                current.title,
                current.message,
                onDone = {
                    step = ImagesStep.Choose
                    chooseAnother()
                },
                onOpen = current.singleFile?.let { file ->
                    {
                        scope.launch {
                            try {
                                onOpen(files.refOf(file))
                            } catch (e: DocumentException) {
                                step = ImagesStep.Finished("The picture was saved, but couldn't be opened", e.error.detail)
                            }
                        }
                    }
                },
            )
        }
    }
}
