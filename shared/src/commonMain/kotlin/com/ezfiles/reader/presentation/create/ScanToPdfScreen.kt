package com.ezfiles.reader.presentation.create

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberImagePicker
import com.ezfiles.reader.platform.rememberScanWorkspace
import com.ezfiles.reader.platform.rememberTextRecognizer
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.scan.CameraPanel
import com.ezfiles.reader.presentation.scan.CornerEditor
import com.ezfiles.reader.presentation.scan.MAX_SCAN_PAGES
import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanAction
import com.ezfiles.reader.presentation.scan.ScanBusy
import com.ezfiles.reader.presentation.scan.ScanButton
import com.ezfiles.reader.presentation.scan.ScanFilter
import com.ezfiles.reader.presentation.scan.ScanIcons
import com.ezfiles.reader.presentation.scan.ScanMessage
import com.ezfiles.reader.presentation.scan.ScanPage
import com.ezfiles.reader.presentation.scan.ScanPicture
import com.ezfiles.reader.presentation.scan.ScanProgress
import com.ezfiles.reader.presentation.scan.ScanStep
import com.ezfiles.reader.presentation.scan.ScanUiState
import com.ezfiles.reader.presentation.scan.ScanViewModel
import com.ezfiles.reader.presentation.scan.loadPicture
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.launch

private const val THUMBNAIL_SIDE = 384
private const val EDITOR_SIDE = 1280

private const val CAMERA_PURPOSE =
    "The camera is used only to photograph your pages, and the pictures stay on this device. " +
        "You can scan pictures from your gallery without it."

@Composable
fun ScanToPdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val workspace = rememberScanWorkspace("pages")
    val recognizer = rememberTextRecognizer()
    val pdf = LocalPdfCreator.current
    val files = LocalDocumentFiles.current
    // One scanner at a time, so a fixed key: the view model outlives the activity
    // being recreated, and after process death it finds the session in the workspace.
    val viewModel = viewModel(key = "scan-to-pdf") { ScanViewModel(workspace, recognizer, pdf) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val close = {
        onAction(ScanAction.Discard)
        onClose()
    }
    val back = {
        when (state.step) {
            is ScanStep.Capture, is ScanStep.Adjust -> if (state.pages.isEmpty()) close() else onAction(ScanAction.ShowPages)
            ScanStep.Pages -> confirmingDiscard = true
            ScanStep.Saved -> close()
        }
    }
    PlatformBackHandler(enabled = true) {
        if (state.busy != null) onAction(ScanAction.CancelWork) else back()
    }

    val saver = rememberDocumentSaver { uri -> uri?.let { onAction(ScanAction.SaveTo(it)) } }
    val picker = rememberImagePicker(multiple = true) { uris -> onAction(ScanAction.ImportPictures(uris)) }

    Box(Modifier.fillMaxSize()) {
        CreateToolFrame("Scan to PDF", back) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.message?.let { ScanMessage(it, { onAction(ScanAction.DismissMessage) }) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val step = state.step) {
                        is ScanStep.Capture -> CameraPanel(
                            purpose = CAMERA_PURPOSE,
                            onCapture = { onAction(ScanAction.TakePicture(it)) },
                            onImport = picker::launch,
                            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                            autoCapture = true,
                            footer = {
                                if (state.pages.isNotEmpty()) {
                                    ScanButton("Pages (${state.pages.size})", { onAction(ScanAction.ShowPages) })
                                }
                            },
                        )
                        is ScanStep.Adjust -> state.pages.firstOrNull { it.id == step.pageId }?.let { page ->
                            AdjustPage(workspace, page, onAction)
                        }
                        ScanStep.Pages -> PageList(
                            workspace,
                            state,
                            onAction,
                            onSave = { saver.launch("Scan.pdf", "application/pdf") },
                        )
                        ScanStep.Saved -> SavedPanel(
                            note = state.saved?.note,
                            onOpen = {
                                val uri = state.saved?.uri ?: return@SavedPanel
                                scope.launch {
                                    try {
                                        val ref = files.refOf(uri)
                                        onAction(ScanAction.Discard)
                                        onOpen(ref)
                                    } catch (_: DocumentException) {
                                        // The file is saved; it just can't be opened from here.
                                        close()
                                    }
                                }
                            },
                            onAnother = { onAction(ScanAction.Discard) },
                            onDone = close,
                        )
                    }
                }
            }
        }
        state.busy?.let { busy ->
            ScanProgress(
                when (busy) {
                    ScanBusy.Preparing -> "Getting the page ready…"
                    is ScanBusy.Reading -> "Reading text on page ${busy.page} of ${busy.total}…"
                    ScanBusy.Writing -> "Saving the PDF…"
                },
                onCancel = { onAction(ScanAction.CancelWork) },
            )
        }
    }
    if (confirmingDiscard) {
        EzDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("Your ${state.pages.size} scanned page${if (state.pages.size == 1) "" else "s"} will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    close()
                }) { Text("Discard", color = EzColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep scanning") }
            },
        )
    }
}

@Composable
private fun AdjustPage(workspace: ScanWorkspace, page: ScanPage, onAction: (ScanAction) -> Unit) {
    // Local until confirmed: redrawing the page on every drag step would be wasted work.
    var quad by remember(page.id, page.quad) { mutableStateOf(page.quad) }
    val picture by loadPicture(workspace, page.id, ScanPictureKind.Source, 0, EDITOR_SIDE)
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (page.detected) {
                "Check the corners and drag them onto the page's edges if needed."
            } else {
                "The page's edges weren't found. Drag the corners onto them."
            },
            color = EzColors.Text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val loaded = picture
            if (loaded == null) {
                CircularProgressIndicator(color = EzColors.Primary)
            } else {
                CornerEditor(loaded, quad, { quad = it }, Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanButton("Whole picture", { quad = PageQuad.FullFrame }, Modifier.weight(1f))
            ScanButton("Retake", { onAction(ScanAction.Retake(page.id)) }, Modifier.weight(1f))
            ScanButton(
                "Done",
                { onAction(ScanAction.SetOutline(page.id, quad)) },
                Modifier.weight(1f),
                primary = true,
            )
        }
    }
}

@Composable
private fun PageList(
    workspace: ScanWorkspace,
    state: ScanUiState,
    onAction: (ScanAction) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                PageCard(workspace, page, index, state.pages.size, page.id in state.rendering, onAction)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .glass(EzShapes.Medium, 0.6f)
                .toggleable(value = state.searchable, role = Role.Switch) { onAction(ScanAction.SetSearchable(it)) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Make searchable", color = EzColors.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Reads the text on this device. Latin script only, and printed text works best; handwriting isn't supported.",
                    color = EzColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                state.searchable,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 12.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = EzColors.Primary),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanButton(
                "Add page",
                { onAction(ScanAction.AddPage) },
                Modifier.weight(1f),
                enabled = state.pages.size < MAX_SCAN_PAGES,
                icon = EzIcons.Plus,
            )
            ScanButton("Save PDF", onSave, Modifier.weight(1f), primary = true, enabled = state.pages.isNotEmpty())
        }
    }
}

@Composable
private fun PageCard(
    workspace: ScanWorkspace,
    page: ScanPage,
    index: Int,
    count: Int,
    rendering: Boolean,
    onAction: (ScanAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(width = 96.dp, height = 128.dp).clip(EzShapes.Page).background(EzPaper.Background),
                contentAlignment = Alignment.Center,
            ) {
                ScanPicture(
                    workspace,
                    page.id,
                    ScanPictureKind.Output,
                    page.version,
                    THUMBNAIL_SIDE,
                    "Page ${index + 1}",
                    Modifier.fillMaxSize(),
                )
                if (rendering) CircularProgressIndicator(color = EzColors.Primary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Page ${index + 1}", color = EzColors.Text, fontWeight = FontWeight.SemiBold)
                if (!page.detected) {
                    Text(
                        "Edges not found: check the corners.",
                        color = EzColors.Accent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.padding(top = 4.dp)) {
                    BarIconButton(EzIcons.ChevronUp, "Move page ${index + 1} up", { onAction(ScanAction.Move(page.id, -1)) }, enabled = index > 0)
                    BarIconButton(EzIcons.ChevronDown, "Move page ${index + 1} down", { onAction(ScanAction.Move(page.id, 1)) }, enabled = index < count - 1)
                    BarIconButton(EzIcons.Rotate, "Rotate page ${index + 1}", { onAction(ScanAction.Rotate(page.id)) })
                }
                Row {
                    BarIconButton(ScanIcons.Crop, "Adjust corners of page ${index + 1}", { onAction(ScanAction.Adjust(page.id)) })
                    BarIconButton(ScanIcons.Camera, "Retake page ${index + 1}", { onAction(ScanAction.Retake(page.id)) })
                    BarIconButton(ScanIcons.Delete, "Delete page ${index + 1}", { onAction(ScanAction.Delete(page.id)) })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (filter in ScanFilter.entries) {
                EzChip(filter.label, page.filter == filter, { onAction(ScanAction.SetFilter(page.id, filter)) })
            }
        }
        if (count > 1) {
            TextButton(onClick = { onAction(ScanAction.SetFilterOnAll(page.filter)) }) {
                Text("Use ${page.filter.label} on all pages", color = EzColors.Accent)
            }
        }
    }
}

@Composable
private fun SavedPanel(note: String?, onOpen: () -> Unit, onAnother: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text("PDF saved", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
        Text(
            "The working copies of your scan have been deleted from this app.",
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (note != null) Text(note, color = EzColors.Text, textAlign = TextAlign.Center)
        ScanButton("Open PDF", onOpen, primary = true)
        ScanButton("Scan another", onAnother)
        ScanButton("Done", onDone)
    }
}
