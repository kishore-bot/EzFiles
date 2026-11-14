package com.ezfiles.reader.presentation.pdftools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.create.ChipRow
import com.ezfiles.reader.presentation.create.SettingLabel
import kotlinx.coroutines.CancellationException

private val RESIZE_SIZES = listOf(PdfPageSize.A4, PdfPageSize.Letter)

@Composable
fun PageSizeScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("page-size") { tools, outputs -> PdfToolViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    var size by rememberSaveable { mutableStateOf(PdfPageSize.A4) }

    PdfToolHost(
        title = PdfTool.PageSize.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDF to resize. The original stays as it is.",
        actions = {
            PdfSaveRow(
                hint = null,
                saveLabel = "Resize",
                onSave = {
                    val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                    val chosen = size
                    flow.requestRun(
                        PdfToolJob(
                            outputs = listOf(
                                PdfToolOutput(suggestedPdfName(name, chosen.label)) { pdfs, target, onProgress ->
                                    flow.tools.resizePages(pdfs.first(), chosen, target, onProgress)
                                },
                            ),
                            savedMessage = { "Saved with ${chosen.label} pages." },
                        ),
                    )
                },
            )
        },
    ) {
        if (pdf != null) {
            PdfToolForm(pdf, pickPdf) {
                currentShape(pdf)?.let { PdfToolNote("First page now: $it.") }
                SettingLabel("New page size")
                ChipRow {
                    RESIZE_SIZES.forEach { EzChip(it.label, it == size, { size = it }) }
                }
                PdfToolNote("Every page becomes ${size.label}, with its content scaled to fit and centred.")
            }
        }
    }
}

/** What the renderer can say about the first page: its shape, as it gives no absolute size. `null` if it can't open the file. */
@Composable
private fun currentShape(pdf: PickedPdf): String? {
    val preview by rememberPdfPreview(pdf)
    val pages = (preview as? PdfPreview.Ready)?.thumbnails?.pages ?: return null
    val shape by produceState<String?>(null, pages) {
        value = try {
            describePageShape(pages.aspectRatio(0))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    return shape
}
