package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.create.TextAction
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons

@Composable
fun MergePdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("merge") { tools, outputs -> PdfToolViewModel(tools, outputs, singleSource = false) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val sources = state.sources

    PdfToolHost(
        title = PdfTool.Merge.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDFs to merge. You can add more and put them in order next.",
        multiple = true,
        actions = {
            PdfSaveRow(
                hint = if (sources.canMerge) null else "Add at least 2 PDFs.",
                saveLabel = "Merge",
                leading = { TextAction("Add PDFs", pickPdf) },
                onSave = {
                    val first = sources.items.first().ref.displayName
                    val count = sources.items.size
                    val pages = sources.totalPages
                    flow.requestRun(
                        PdfToolJob(
                            outputs = listOf(
                                PdfToolOutput(suggestedPdfName(first, "merged")) { pdfs, target, onProgress ->
                                    flow.tools.merge(pdfs, target, onProgress)
                                },
                            ),
                            savedMessage = { "Merged $count PDFs into ${pageCountLabel(pages)}." },
                        ),
                    )
                },
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Pages run in this order. ${pageCountLabel(sources.totalPages)} in all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                )
            }
            itemsIndexed(sources.items, key = { _, pdf -> pdf.id }) { index, pdf ->
                val name = pdf.ref.displayName
                PickedPdfRow(pdf) {
                    BarIconButton(EzIcons.ChevronUp, "Move $name up", { flow.moveSource(pdf.id, -1) }, enabled = index > 0)
                    BarIconButton(EzIcons.ChevronDown, "Move $name down", { flow.moveSource(pdf.id, 1) }, enabled = index < sources.items.lastIndex)
                    BarIconButton(EzIcons.Close, "Remove $name", { flow.removeSource(pdf.id) })
                }
            }
        }
    }
}
