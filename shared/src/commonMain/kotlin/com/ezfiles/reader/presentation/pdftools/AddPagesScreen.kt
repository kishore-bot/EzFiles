package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs
import com.ezfiles.reader.platform.rememberImagePicker
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.create.ChipRow
import com.ezfiles.reader.presentation.create.SettingLabel
import com.ezfiles.reader.presentation.create.TextAction

private enum class InsertChoice(val label: String) { Start("At the start"), End("At the end"), After("After a page") }

/** Keeps the chosen pictures: a long list of URIs doesn't belong in saved state. */
private class AddPagesViewModel(tools: PdfTools, outputs: DocumentOutputs) : PdfToolViewModel(tools, outputs) {
    var images by mutableStateOf(emptyList<String>())

    override fun onLeave() {
        images = emptyList()
        super.onLeave()
    }
}

@Composable
fun AddPagesScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("add-pages") { tools, outputs -> AddPagesViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    var choice by rememberSaveable { mutableStateOf(InsertChoice.End) }
    var afterPage by rememberSaveable { mutableStateOf("") }
    val picker = rememberImagePicker(multiple = true) { uris -> if (uris.isNotEmpty()) tool.images = uris }

    val pageCount = pdf?.info?.pageCount ?: 0
    val position = when (choice) {
        InsertChoice.Start -> InsertPosition.Start
        InsertChoice.End -> InsertPosition.End
        InsertChoice.After -> InsertPosition.After(afterPage.toIntOrNull() ?: 0)
    }
    val atPage = insertIndex(position, pageCount)

    PdfToolHost(
        title = PdfTool.AddPages.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDF to add pictures to. The original stays as it is.",
        actions = {
            val images = tool.images
            PdfSaveRow(
                hint = when {
                    images.isEmpty() -> "Add pictures to continue."
                    atPage == null -> "Choose where they go."
                    else -> null
                },
                saveLabel = "Add pages",
                leading = { TextAction(if (images.isEmpty()) "Add pictures" else "Choose again") { picker.launch() } },
                onSave = {
                    val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                    val index = atPage ?: return@PdfSaveRow
                    flow.requestRun(
                        PdfToolJob(
                            outputs = listOf(
                                PdfToolOutput(suggestedPdfName(name, "with pictures")) { pdfs, target, onProgress ->
                                    flow.tools.insertImages(pdfs.first(), images, index, target, onProgress)
                                },
                            ),
                            savedMessage = { "Added ${pageCountLabel(images.size)}." },
                            onSaved = { tool.images = emptyList() },
                        ),
                    )
                },
            )
        },
    ) {
        if (pdf != null) {
            PdfToolForm(pdf, pickPdf) {
                SettingLabel("Pictures")
                PdfToolNote(
                    when (val count = tool.images.size) {
                        0 -> "None yet. Each picture becomes a new page, in the order you chose them."
                        1 -> "1 picture chosen. It becomes a new page."
                        else -> "$count pictures chosen. Each becomes a new page, in the order you chose them."
                    },
                )
                SettingLabel("Where they go")
                ChipRow {
                    InsertChoice.entries.forEach { EzChip(it.label, it == choice, { choice = it }) }
                }
                if (choice == InsertChoice.After) {
                    OutlinedTextField(
                        value = afterPage,
                        onValueChange = { afterPage = it.filter(Char::isDigit).take(MAX_PAGE_DIGITS) },
                        singleLine = true,
                        isError = afterPage.isNotEmpty() && atPage == null,
                        label = { Text("After page (1–$pageCount)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (afterPage.isNotEmpty() && atPage == null) {
                        PdfToolNote("Enter a page from 1 to $pageCount.", warning = true)
                    }
                }
            }
        }
    }
}

internal const val MAX_PAGE_DIGITS = 6
