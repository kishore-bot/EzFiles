package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfPageEdit
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.create.ChipRow
import com.ezfiles.reader.presentation.create.SettingLabel

private enum class SplitMode(val label: String) { Extract("Extract pages"), Separate("Separate files") }

/** The files a split makes: one per page reads better named by its page. */
internal fun splitOutputNames(original: String, pageCount: Int, everyPages: Int): List<String> {
    val chunks = pageChunks(pageCount, everyPages)
    return chunks.mapIndexed { index, chunk ->
        val suffix = if (everyPages == 1) {
            "page ${paddedNumber(chunk.first + 1, pageCount)}"
        } else {
            "part ${paddedNumber(index + 1, chunks.size)}"
        }
        suggestedPdfName(original, suffix)
    }
}

@Composable
fun SplitPdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("split") { tools, outputs -> PdfToolViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    val pageCount = pdf?.info?.pageCount ?: 0
    var mode by rememberSaveable { mutableStateOf(SplitMode.Extract) }
    var ranges by rememberSaveable { mutableStateOf("") }
    var perPage by rememberSaveable { mutableStateOf(true) }
    var everyText by rememberSaveable { mutableStateOf("") }

    val parsed = parsePageRanges(ranges, pageCount)
    val every = if (perPage) 1 else everyText.toIntOrNull()?.takeIf { it >= 1 }

    PdfToolHost(
        title = PdfTool.Split.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDF to split. The original stays as it is.",
        actions = {
            // Without a folder picker on this device, Extract is all there is.
            val separate = mode == SplitMode.Separate && canWriteToFolders
            PdfSaveRow(
                hint = when {
                    separate && every == null -> "Enter how many pages per file."
                    !separate && parsed is PageRangeResult.Invalid -> "Enter the pages to extract."
                    else -> null
                },
                saveLabel = if (separate) "Choose folder" else "Extract",
                onSave = {
                    val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                    if (separate) {
                        val step = every ?: return@PdfSaveRow
                        flow.requestRun(separateFilesJob(flow, name, pageCount, step))
                    } else {
                        val pages = (parsed as? PageRangeResult.Pages)?.pages ?: return@PdfSaveRow
                        flow.requestRun(
                            PdfToolJob(
                                outputs = listOf(
                                    PdfToolOutput(suggestedPdfName(name, "extract")) { pdfs, target, onProgress ->
                                        flow.tools.rebuild(pdfs.first(), pages.map { PdfPageEdit(it) }, target, onProgress)
                                    },
                                ),
                                savedMessage = { "Saved ${pageCountLabel(pages.size)} as a new PDF." },
                            ),
                        )
                    }
                },
            )
        },
    ) {
        if (pdf != null) {
            PdfToolForm(pdf, pickPdf) {
                if (canWriteToFolders) {
                    ChipRow {
                        SplitMode.entries.forEach { EzChip(it.label, it == mode, { mode = it }) }
                    }
                }
                if (mode == SplitMode.Separate && canWriteToFolders) {
                    SettingLabel("How to split")
                    ChipRow {
                        EzChip("One per page", perPage, { perPage = true })
                        EzChip("Every N pages", !perPage, { perPage = false })
                    }
                    if (!perPage) {
                        OutlinedTextField(
                            value = everyText,
                            onValueChange = { everyText = it.filter(Char::isDigit).take(MAX_PAGE_DIGITS) },
                            singleLine = true,
                            isError = everyText.isNotEmpty() && every == null,
                            label = { Text("Pages per file") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when {
                        every != null -> PdfToolNote(
                            // Costs a string per page on a long document, so not on every recomposition.
                            remember(pdf.id, every) {
                                val names = splitOutputNames(pdf.ref.displayName, pageCount, every)
                                if (names.size == 1) {
                                    "Makes 1 file, with every page in it: ${names.first()}"
                                } else {
                                    "Makes ${names.size} new files in the folder you choose, starting with ${names.first()}. Files already there aren't replaced."
                                }
                            },
                        )
                        everyText.isNotEmpty() -> PdfToolNote("Enter 1 or more.", warning = true)
                    }
                } else {
                    SettingLabel("Pages to keep")
                    OutlinedTextField(
                        value = ranges,
                        onValueChange = { ranges = it.take(MAX_RANGE_INPUT) },
                        singleLine = true,
                        isError = ranges.isNotBlank() && parsed is PageRangeResult.Invalid,
                        label = { Text("For example 1-3, 7") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (val result = parsed) {
                        is PageRangeResult.Pages -> PdfToolNote("${pageCountLabel(result.pages.size)} go into one new PDF, in this order.")
                        is PageRangeResult.Invalid -> if (ranges.isNotBlank()) PdfToolNote(result.message, warning = true)
                    }
                }
            }
        }
    }
}

private fun separateFilesJob(flow: PdfToolFlow, name: String, pageCount: Int, every: Int): PdfToolJob {
    val chunks = pageChunks(pageCount, every)
    val names = splitOutputNames(name, pageCount, every)
    return PdfToolJob(
        outputs = chunks.mapIndexed { index, chunk ->
            PdfToolOutput(names[index]) { pdfs, target, onProgress ->
                flow.tools.rebuild(pdfs.first(), chunk.map { PdfPageEdit(it) }, target, onProgress)
            }
        },
        toFolder = true,
        savedMessage = { count -> if (count == 1) "Saved 1 file to the folder." else "Saved $count files to the folder." },
    )
}

private const val MAX_RANGE_INPUT = 2000
