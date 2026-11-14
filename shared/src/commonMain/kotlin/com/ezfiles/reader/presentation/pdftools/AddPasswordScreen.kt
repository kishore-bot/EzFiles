package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs

/** The form is here and not in saved state: it survives the activity, and goes when the tool is left or the file is saved. */
private class AddPasswordViewModel(tools: PdfTools, outputs: DocumentOutputs) : PdfToolViewModel(tools, outputs) {
    var form by mutableStateOf(PasswordForm())

    fun clearForm() {
        form = PasswordForm()
    }

    override fun onLeave() {
        clearForm()
        super.onLeave()
    }
}

@Composable
fun AddPasswordScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("add-password") { tools, outputs -> AddPasswordViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    val form = tool.form

    PdfToolHost(
        title = PdfTool.AddPassword.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDF to protect. The original stays as it is, without the password.",
        actions = {
            PdfSaveRow(
                hint = if (form.canSubmit) null else "Enter the same password twice.",
                saveLabel = "Protect",
                onSave = {
                    val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                    val password = form.password
                    if (!form.canSubmit) return@PdfSaveRow
                    flow.requestRun(
                        PdfToolJob(
                            outputs = listOf(
                                PdfToolOutput(suggestedPdfName(name, "protected")) { pdfs, target, onProgress ->
                                    flow.tools.addPassword(pdfs.first(), password, target, onProgress)
                                },
                            ),
                            savedMessage = { "Saved a copy that needs the password to open." },
                            onSaved = tool::clearForm,
                        ),
                    )
                },
            )
        },
    ) {
        if (pdf != null) {
            PdfToolForm(pdf, pickPdf) {
                PdfToolNote(
                    "A forgotten password can't be recovered. Nobody can open the protected copy without it, and that includes EzFiles. Keep it somewhere safe.",
                    warning = true,
                )
                if (pdf.info.encrypted) PdfToolNote("This PDF is already protected. The new password replaces its current one in the copy.")
                PdfPasswordField(
                    value = form.password,
                    onValueChange = { tool.form = PasswordForm(it, form.confirmation) },
                    label = "New password",
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                PdfPasswordField(
                    value = form.confirmation,
                    onValueChange = { tool.form = PasswordForm(form.password, it) },
                    label = "Confirm password",
                    isError = form.mismatch,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.mismatch) PdfToolNote("The passwords don't match.", warning = true)
                if (form.isShort) PdfToolNote("This is a very short password, and easy to guess. You can still use it.")
            }
        }
    }
}
