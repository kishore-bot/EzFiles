package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.PdfEditInfo
import com.ezfiles.reader.platform.PdfEditor
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfSaveBlock
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentPicker
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzBlockingProgress
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.raised
import com.ezfiles.reader.presentation.viewers.ConfirmDialog
import com.ezfiles.reader.presentation.viewers.MessageDialog
import com.ezfiles.reader.presentation.viewers.PdfAnnotationHistory
import com.ezfiles.reader.presentation.viewers.PdfPageActions
import com.ezfiles.reader.presentation.viewers.PdfPageOverlay
import com.ezfiles.reader.presentation.viewers.RedoIcon
import com.ezfiles.reader.presentation.viewers.SaveIcon
import com.ezfiles.reader.presentation.viewers.SignatureChooser
import com.ezfiles.reader.presentation.viewers.SignatureIcon
import com.ezfiles.reader.presentation.viewers.SignaturePlacer
import com.ezfiles.reader.presentation.viewers.UndoIcon
import com.ezfiles.reader.presentation.viewers.saveWarning
import com.ezfiles.reader.presentation.viewers.signedFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.ezfiles.reader.presentation.viewers.PdfTool as AnnotationTool

private const val A4_ASPECT = 1f / 1.414f

/**
 * Picks a PDF and hands it to the same signature pieces as the reader's
 * annotate toolbar. A password-protected PDF can't take annotations, so this
 * says so without asking for its password.
 */
@Composable
fun SignPdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val initialRef = LocalPdfToolsPreselect.current ?: LocalPdfToolsPreselectList.current.firstOrNull()
    var ref by remember { mutableStateOf(initialRef) }
    val picker = rememberDocumentPicker { picked -> if (picked != null) ref = picked }
    val load = rememberConvertPdf(ref, null)
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val current = ref

    CreateToolFrame("Signature", { if (dirty) leaving = onClose else onClose() }) {
        when (load) {
            ConvertPdfLoad.Idle -> ConvertPickPrompt("Choose a PDF to add your drawn signature to. The PDF itself isn't changed: you save a copy.", picker::launch)
            ConvertPdfLoad.Loading -> ConvertBusy()
            is ConvertPdfLoad.NeedsPassword -> ConvertPickPrompt(
                "${PdfSaveBlock.Encrypted.message} You can make an unlocked copy with Remove password, then sign that.",
                picker::launch,
            )
            is ConvertPdfLoad.Failed -> ConvertPickPrompt(load.message, picker::launch)
            is ConvertPdfLoad.Open -> if (current != null) {
                SignGate(current, load.pages, { dirty = it }, { ref = null }, picker::launch, onOpen)
            }
        }
    }
    PlatformBackHandler(enabled = dirty) { leaving = onClose }
    leaving?.let { action ->
        ConfirmDialog(
            title = "Discard your signatures?",
            message = "The signatures you placed haven't been saved.",
            confirm = "Discard",
            dismiss = "Keep editing",
            onConfirm = {
                leaving = null
                dirty = false
                action()
            },
            onDismiss = { leaving = null },
        )
    }
}

@Composable
private fun SignGate(
    ref: DocumentRef,
    pages: PdfPages,
    onDirty: (Boolean) -> Unit,
    onDone: () -> Unit,
    onPick: () -> Unit,
    onOpen: (DocumentRef) -> Unit,
) {
    val editor = pages.editor
    val info by produceState<PdfEditInfo?>(null, pages) { value = editor?.inspect() }
    val loaded = info
    when {
        editor == null -> ConvertPickPrompt("Signatures can't be saved on this device yet.", onPick)
        loaded == null -> ConvertBusy()
        // The same refusal, for the same reasons, as the reader's annotate button.
        loaded.saveBlock != null -> ConvertPickPrompt(loaded.saveBlock.message, onPick)
        else -> SignEditor(ref, pages, editor, loaded, onDirty, onDone, onOpen)
    }
}

@Composable
private fun SignEditor(
    ref: DocumentRef,
    pages: PdfPages,
    editor: PdfEditor,
    info: PdfEditInfo,
    onDirty: (Boolean) -> Unit,
    onDone: () -> Unit,
    onOpen: (DocumentRef) -> Unit,
) {
    val files = LocalDocumentFiles.current
    val scope = rememberCoroutineScope()
    val signing = remember { SignaturePlacer() }
    var history by remember { mutableStateOf(PdfAnnotationHistory.Empty) }
    var page by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    val existingByPage = remember(info) { info.existing.groupBy { it.page } }
    val hasWork = history.isDirty && history.items.isNotEmpty()
    LaunchedEffect(hasWork) { onDirty(hasWork) }
    val goTo = { target: Int ->
        signing.finish()
        page = target.coerceIn(0, pages.pageCount - 1)
    }

    val saver = rememberDocumentSaver { uri ->
        if (uri != null) {
            val items = history.items
            saving = true
            scope.launch {
                try {
                    editor.saveCopy(items, uri)
                    history = history.markSaved()
                    savedUri = uri
                } catch (e: DocumentException) {
                    failure = e.error.detail
                } finally {
                    saving = false
                }
            }
        }
    }
    val startSave = {
        signing.finish()
        saver.launch(signedFileName(ref.displayName), "application/pdf")
    }

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val aspect by produceState(A4_ASPECT, pages, page) {
                value = try {
                    pages.aspectRatio(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    A4_ASPECT
                }
            }
            val pageWidth = minOf(maxWidth, maxHeight * aspect)
            val pageHeight = pageWidth / aspect
            val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
            val bitmap by produceState<ImageBitmap?>(null, pages, page, widthPx) {
                value = null
                value = try {
                    pages.render(page, widthPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    null // A damaged page shows as blank; the others can still be signed.
                }
            }
            Box(Modifier.size(pageWidth, pageHeight).raised(EzShapes.Page, 10.dp).clip(EzShapes.Page).background(EzPaper.Background)) {
                bitmap?.let { Image(it, contentDescription = "Page ${page + 1}", modifier = Modifier.fillMaxSize()) }
                PdfPageOverlay(
                    page = page,
                    annotations = existingByPage[page].orEmpty() + history.onPage(page),
                    selection = null,
                    actions = PdfPageActions(
                        tool = AnnotationTool.Signature,
                        inkColor = 0,
                        inkWidth = 0f,
                        canSelectText = false,
                        onTap = { tapped, point, pageAspect ->
                            if (signing.active != null) signing.finish() else history = signing.placeAt(history, tapped, point, pageAspect)
                        },
                        onLongPress = { _, _ -> },
                        onExtendSelection = { _, _ -> },
                        onHandleDrag = { _, _ -> },
                        onStroke = { _, _ -> },
                        placement = signing.active,
                        onPlacementChange = { history = signing.adjust(history, it) },
                        onPlacementDone = signing::finish,
                    ),
                )
            }
        }
        PageChooser(page, pages.pageCount, goTo)
        Text(
            signing.hint ?: if (history.items.isEmpty()) "Add a signature, then tap where it goes." else "Add another, or save a copy.",
            style = MaterialTheme.typography.bodySmall,
            color = EzColors.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        val saved = savedUri
        if (saved != null && !hasWork) {
            ConvertResult(
                "Saved a copy with your signature",
                "The original PDF wasn't changed.",
                onDone = onDone,
                doneLabel = "Sign another PDF",
                outputRef = DocumentRef(saved, signedFileName(ref.displayName)),
                onOpen = {
                    scope.launch {
                        try {
                            onOpen(files.refOf(saved))
                        } catch (e: DocumentException) {
                            failure = e.error.detail
                        }
                    }
                },
            )
        } else {
            // Labelled actions in a row, and the save across the bottom once there's something to save.
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).glassBar().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryActionButton("Sign", SignatureIcon, signing::startPicking)
                    if (signing.lastUsed != null) {
                        EzTextButton("Again", signing::placeAgain)
                    }
                    Box(Modifier.weight(1f))
                    BarIconButton(UndoIcon, "Undo", { history = signing.undo(history) }, enabled = history.canUndo)
                    BarIconButton(
                        RedoIcon,
                        "Redo",
                        {
                            signing.finish()
                            history = history.redo()
                        },
                        enabled = history.canRedo,
                    )
                }
                if (hasWork) {
                    PrimaryActionButton("Save a copy", SaveIcon, {
                        val note = saveWarning(info.signed, info.archival)
                        if (note != null) warning = note else startSave()
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }
    SignatureChooser(signing)
    warning?.let { note ->
        ConfirmDialog(
            title = "Before you save",
            message = note,
            confirm = "Save a copy",
            dismiss = "Cancel",
            onConfirm = {
                warning = null
                startSave()
            },
            onDismiss = { warning = null },
        )
    }
    failure?.let { message -> MessageDialog("Couldn't save", message) { failure = null } }
    if (saving) EzBlockingProgress("Saving…")
}

@Composable
private fun PageChooser(page: Int, count: Int, onPage: (Int) -> Unit) {
    if (count <= 1) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(
        Modifier.fillMaxWidth().glass(EzShapes.Pill, 0.5f).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(EzIcons.ChevronLeft, "Previous page", { onPage(page - 1) }, enabled = page > 0)
        Text("${(dragging?.toInt() ?: page) + 1} / $count", style = MaterialTheme.typography.labelLarge, color = EzColors.Text)
        Slider(
            value = dragging ?: page.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onPage(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..(count - 1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = EzColors.Primary, activeTrackColor = EzColors.Primary, inactiveTrackColor = EzColors.SliderTrack),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        BarIconButton(EzIcons.ChevronRight, "Next page", { onPage(page + 1) }, enabled = page < count - 1)
    }
}
