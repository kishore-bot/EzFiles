package com.ezfiles.reader.presentation.pdftools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.presentation.create.TextAction

/** What Remove password can do with a file it has read. */
internal enum class RemovePasswordStep {
    /** Not encrypted: there's nothing to remove. */
    NotProtected,

    /** Opened without a password yet encrypted: only the owner password can lift its restrictions. */
    NeedsOwnerPassword,

    Ready,
}

internal fun removePasswordStep(pdf: PickedPdf): RemovePasswordStep = when {
    !pdf.info.encrypted -> RemovePasswordStep.NotProtected
    pdf.password == null -> RemovePasswordStep.NeedsOwnerPassword
    else -> RemovePasswordStep.Ready
}

@Composable
fun RemovePasswordScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("remove-password") { tools, outputs -> PdfToolViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    val step = pdf?.let(::removePasswordStep)

    PdfToolHost(
        title = PdfTool.RemovePassword.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the protected PDF. You'll need its current password; nothing is bypassed or guessed.",
        actions = {
            when (step) {
                RemovePasswordStep.NeedsOwnerPassword -> PdfSaveRow(
                    hint = null,
                    saveLabel = "Enter owner password",
                    onSave = { pdf?.let(flow::askOwnerPassword) },
                )
                RemovePasswordStep.Ready -> PdfSaveRow(
                    hint = null,
                    saveLabel = "Remove password",
                    onSave = {
                        val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                        flow.requestRun(
                            PdfToolJob(
                                outputs = listOf(
                                    PdfToolOutput(suggestedPdfName(name, "unlocked")) { pdfs, target, onProgress ->
                                        flow.tools.removePassword(pdfs.first(), target, onProgress)
                                    },
                                ),
                                savedMessage = { "Saved a copy that opens without a password." },
                            ),
                        )
                    },
                )
                RemovePasswordStep.NotProtected, null -> TextAction("Choose another PDF", pickPdf)
            }
        },
    ) {
        if (pdf != null) {
            PdfToolForm(pdf, pickPdf) {
                when (step) {
                    RemovePasswordStep.NotProtected -> PdfToolNote("This PDF isn't protected, so there's no password to remove.")
                    RemovePasswordStep.NeedsOwnerPassword -> PdfToolNote(
                        "Anyone can open this PDF, but its owner restricted what can be done with it. Removing that needs the owner password.",
                    )
                    RemovePasswordStep.Ready -> PdfToolNote(
                        "The password is correct. Removing it saves a copy anyone can open; the protected original stays as it is.",
                    )
                    null -> Unit
                }
            }
        }
    }
}
