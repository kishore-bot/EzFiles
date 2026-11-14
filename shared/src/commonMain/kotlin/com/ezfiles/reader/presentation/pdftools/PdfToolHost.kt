package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.rememberDocumentOutputs
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberPdfPicker
import com.ezfiles.reader.platform.rememberWritableFolderPicker
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzActionPanel
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzProgressRow
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.create.TextAction
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.FeedbackOnAppear
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.launch

/** Keeps a tool's flow across activity recreation. Subclass it for a tool's own state that shouldn't go in saved state. */
open class PdfToolViewModel(tools: PdfTools, outputs: DocumentOutputs, singleSource: Boolean = true) : ViewModel() {
    val flow = PdfToolFlow(viewModelScope, tools, outputs, singleSource)

    /** The user left the tool. It's still in the activity's store, so everything has to be forgotten here. */
    open fun onLeave() = flow.leave()
}

/**
 * The tool's ViewModel. The store outlives the screen, so leaving is told apart
 * from the activity being recreated: then the lifecycle has already ended.
 */
@Composable
internal inline fun <reified VM : PdfToolViewModel> rememberPdfTool(
    key: String,
    noinline create: (PdfTools, DocumentOutputs) -> VM,
): VM {
    val tools = LocalPdfTools.current
    val outputs = rememberDocumentOutputs()
    val requestId = LocalPdfToolRequestId.current
    val viewModelKey = if (requestId == 0L) "pdf-tool-$key" else "pdf-tool-$key-request-$requestId"
    val tool = viewModel(key = viewModelKey) { create(tools, outputs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(tool, lifecycle) {
        onDispose { if (lifecycle.currentState != Lifecycle.State.DESTROYED) tool.onLeave() }
    }
    return tool
}

/** What a tool's content can ask of the host. */
class PdfToolHostScope internal constructor(
    /** Shows the PDF picker. */
    val pickPdf: () -> Unit,
    /** Whether a [PdfToolJob] with `toFolder` can run on this device. */
    val canWriteToFolders: Boolean,
)

/**
 * Draws the steps of a [PdfToolFlow] around a tool: a prompt to pick a file,
 * [content] once there is one, [actions] in the bar below it, then the result.
 * Handles the pickers, the password sheet and the warning.
 */
@Composable
internal fun PdfToolHost(
    title: String,
    flow: PdfToolFlow,
    onClose: () -> Unit,
    onOpen: (DocumentRef) -> Unit,
    pickHint: String,
    multiple: Boolean = false,
    actions: @Composable PdfToolHostScope.() -> Unit,
    content: @Composable PdfToolHostScope.() -> Unit,
) {
    val state by flow.state.collectAsStateWithLifecycle()
    val saver = rememberDocumentSaver(flow::onTargetPicked)
    val folderPicker = rememberWritableFolderPicker(flow::onFolderPicked) { flow.notice(NO_SYSTEM_DIALOG) }
    val picker = rememberPdfPicker(multiple, flow::addPicked)
    val hostScope = remember(picker, folderPicker) {
        PdfToolHostScope(
            pickPdf = { launchingDialog(flow) { picker.launch() } },
            canWriteToFolders = folderPicker != null,
        )
    }
    val preselect = LocalPdfToolsPreselect.current
    val preselectList = LocalPdfToolsPreselectList.current.ifEmpty { listOfNotNull(preselect) }
    LaunchedEffect(flow) { if (preselectList.isNotEmpty()) flow.preselect(preselectList) }
    LaunchedEffect(state.saveDialog) {
        val dialog = state.saveDialog ?: return@LaunchedEffect
        // Marked first, so a recreated activity doesn't show it twice.
        flow.saveDialogShown()
        launchingDialog(flow) {
            when (dialog) {
                is PdfSaveDialog.File -> saver.launch(dialog.suggestedName, PDF_MIME)
                PdfSaveDialog.Folder -> folderPicker?.invoke() ?: flow.notice(NO_SYSTEM_DIALOG)
            }
        }
    }
    state.prompt?.let { PdfPasswordSheet(it, flow::submitPassword, flow::dismissPassword) }
    state.warning?.let { warning ->
        EzDialog(
            onDismissRequest = flow::dismissWarning,
            title = { Text("Change this file?") },
            text = { Text(warning) },
            confirmButton = { EzTextButton("Continue", flow::confirmWarning) },
            dismissButton = { EzTextButton("Cancel", flow::dismissWarning, color = EzColors.TextSecondary) },
        )
    }

    CreateToolFrame(title, onClose) {
        Column(Modifier.fillMaxSize().imePadding()) {
            val run = state.run
            when {
                run is PdfRunStatus.Done -> PdfResult(run, flow, onOpen, onClose)
                state.sources.items.isEmpty() -> PickPrompt(pickHint, state, hostScope.pickPdf, flow::cancel)
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) { hostScope.content() }
                    PdfRunBar(state, flow::cancel) { hostScope.actions() }
                }
            }
        }
    }
}

/** The system dialogs throw on a device without a documents app; that shouldn't crash a tool. */
private inline fun launchingDialog(flow: PdfToolFlow, launch: () -> Unit) {
    try {
        launch()
    } catch (_: RuntimeException) {
        flow.notice(NO_SYSTEM_DIALOG)
    }
}

private const val NO_SYSTEM_DIALOG = "This device has no app for choosing files."

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    EzStatusCard { content() }
}

@Composable
private fun PickPrompt(hint: String, state: PdfFlowState, onPick: () -> Unit, onCancel: () -> Unit) {
    StatusCard {
        val reading = state.inspecting
        if (reading != null) {
            CircularProgressIndicator(color = EzColors.Primary)
            Text("Reading $reading…", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, textAlign = TextAlign.Center)
            EzTextButton("Cancel", onCancel)
        } else {
            Icon(EzIcons.PdfFile, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, textAlign = TextAlign.Center)
            state.notice?.let { Notice(it) }
            PrimaryActionButton("Choose a PDF", EzIcons.Folder, onPick)
        }
    }
}

@Composable
private fun Notice(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = EzColors.Accent,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PdfResult(run: PdfRunStatus.Done, flow: PdfToolFlow, onOpen: (DocumentRef) -> Unit, onClose: () -> Unit) {
    val files = LocalDocumentFiles.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsStateWithLifecycle()
    FeedbackOnAppear(run) { confirm() }
    StatusCard {
        Icon(PdfToolIcons.Check, null, tint = EzColors.Positive, modifier = Modifier.size(36.dp))
        Text(run.message, style = MaterialTheme.typography.titleLarge, color = EzColors.Text, textAlign = TextAlign.Center)
        state.notice?.let { Notice(it) }
        if (run.uri != null) {
            PrimaryActionButton("Open", EzIcons.Document, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    try {
                        onOpen(files.refOf(run.uri))
                    } catch (e: DocumentException) {
                        flow.notice(e.error.detail)
                    }
                }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextAction("Start over", flow::startOver)
            TextAction("Back to tools", onClose)
        }
    }
}

/** Progress and Cancel while running, what went wrong after a failure, and [actions] the rest of the time. */
@Composable
private fun PdfRunBar(state: PdfFlowState, onCancel: () -> Unit, actions: @Composable () -> Unit) {
    EzActionPanel {
        val run = state.run
        val reading = state.inspecting
        when {
            run is PdfRunStatus.Running -> RunProgress(run, onCancel)
            reading != null -> EzProgressRow("Reading $reading…", null, onCancel)
            else -> {
                state.notice?.let { Notice(it) }
                (run as? PdfRunStatus.Stopped)?.let { Notice(it.message) }
                actions()
            }
        }
    }
}

@Composable
private fun RunProgress(run: PdfRunStatus.Running, onCancel: () -> Unit) {
    val label = when {
        run.cancelling -> "Cancelling…"
        run.total <= 0 -> "Working…"
        run.files -> "File ${minOf(run.done + 1, run.total)} of ${run.total}"
        else -> "Saving… ${run.done} of ${run.total}"
    }
    EzProgressRow(label, if (run.total > 0) run.done.toFloat() / run.total else null, onCancel.takeIf { !run.cancelling })
}

/** The look of the reader's password sheet. The text lives only in this composable, and goes to the flow for one try at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPasswordSheet(prompt: PdfPasswordPrompt, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(prompt.name) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = { if (text.isNotEmpty() && !prompt.busy) onSubmit(text) }
    EzSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(EzIcons.Lock, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
            Text(
                if (prompt.ownerPassword) "Owner password needed" else "This file is protected",
                style = MaterialTheme.typography.titleLarge,
                color = EzColors.Text,
            )
            Text(
                prompt.name,
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    prompt.incorrect -> "That password didn't work. Try again."
                    prompt.ownerPassword -> "Anyone can open this PDF, but its owner restricted changes. Enter the owner password to continue."
                    else -> "Enter the password to continue."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (prompt.incorrect) EzColors.Accent else EzColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            PdfPasswordField(
                value = text,
                onValueChange = { text = it },
                label = if (prompt.ownerPassword) "Owner password" else "Password",
                enabled = !prompt.busy,
                onDone = { submit() },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            // Here, not outside the sheet: its content is composed separately, and
            // asking before the field exists would throw.
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (prompt.busy) {
                CircularProgressIndicator(color = EzColors.Primary)
            } else {
                PrimaryActionButton("Unlock", EzIcons.Lock, onClick = { submit() }, modifier = Modifier.fillMaxWidth())
            }
            EzTextButton("Cancel", onDismiss)
        }
    }
}

/** A password field with show and hide. Its text is the caller's to keep out of saved state. */
@Composable
internal fun PdfPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        isError = isError,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            BarIconButton(
                if (visible) EzIcons.EyeOff else EzIcons.Eye,
                if (visible) "Hide password" else "Show password",
                { visible = !visible },
            )
        },
        modifier = modifier,
    )
}

/** A chosen file: name, page count and what's special about it, with [trailing] controls. */
@Composable
internal fun PickedPdfRow(pdf: PickedPdf, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).glass(EzShapes.Card, 0.5f).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pdf.ref.displayName, style = MaterialTheme.typography.titleSmall, color = EzColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                add(pageCountLabel(pdf.info.pageCount))
                if (pdf.info.encrypted) add("protected")
                if (pdf.info.signed) add("signed")
                if (pdf.info.archival) add("PDF/A")
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
        trailing()
    }
}

/** A scrolling page of settings under the chosen file, with a Change action. */
@Composable
internal fun PdfToolForm(pdf: PickedPdf, onChange: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PickedPdfRow(pdf) { TextAction("Change", onChange) }
        content()
    }
}

/** Text in the tool's settings: [warning] when the user should stop and read it. */
@Composable
internal fun PdfToolNote(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warning) EzColors.Accent else EzColors.TextSecondary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The bar's content: what's missing, or the button that saves. */
@Composable
internal fun PdfSaveRow(hint: String?, saveLabel: String, onSave: () -> Unit, leading: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.End)
            } else {
                PrimaryActionButton(saveLabel, EzIcons.Document, onSave)
            }
        }
    }
}
