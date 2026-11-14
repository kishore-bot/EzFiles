package com.ezfiles.reader.presentation.editor

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.background
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.rememberDocumentDeliveryActions
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.EzNotice
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzTopBar
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.raised
import com.ezfiles.reader.presentation.theme.readerBackground
import com.ezfiles.reader.presentation.theme.appSafeArea
import com.ezfiles.reader.presentation.tidy.TidyIcons

private val UndoIcon = editorIcon(
    "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5" +
        "l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
)
private val RedoIcon = editorIcon(
    "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88" +
        "L13,16h9V7l-3.6,3.6z",
)

private fun editorIcon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

/**
 * The full-screen text editor. [onFinished] gets the latest saved document, or
 * `null` if nothing was saved. [title] and [suggestedName] default to the file's
 * name; [header] sits above the text.
 */
@Composable
fun TextEditorScreen(
    viewModel: TextEditorViewModel,
    onFinished: (DocumentRef?) -> Unit,
    title: String? = null,
    suggestedName: String? = null,
    /** A file another app handed over, which opens when the editor closes. */
    waitingFile: String? = null,
    focusOnStart: Boolean = false,
    header: @Composable () -> Unit = {},
) {
    val state = viewModel.state
    val delivery = rememberDocumentDeliveryActions()
    var shareError by remember { mutableStateOf<String?>(null) }
    fun shareText(selected: Boolean) {
        val field = viewModel.state.field
        val count = if (selected) field.selection.length else field.text.length
        if (count > MAX_SHARED_TEXT_CHARS) {
            shareError = "Share at most 100,000 characters at once. Select a smaller part of the text."
            return
        }
        val text = if (selected) field.text.substring(field.selection.min, field.selection.max) else field.text
        delivery.shareText(text, if (selected) "Selection from ${title ?: state.name}" else "${title ?: state.name} (draft)")
    }
    val saveName = suggestedName ?: state.name
    val saver = rememberDocumentSaver(viewModel::onSaveAsResult)
    val saveAs = { closeAfter: Boolean ->
        if (viewModel.prepareSaveAs(saveName, closeAfter)) saver.launch(saveName, textMimeTypeFor(saveName))
    }
    val saveAndClose = {
        if (state.canOverwrite) viewModel.save(closeAfter = true) else saveAs(true)
    }

    PlatformBackHandler(enabled = true, viewModel::requestClose)
    LaunchedEffect(state.finished) {
        if (state.finished) onFinished(viewModel.takeResult())
    }

    Box(Modifier.fillMaxSize().readerBackground().appSafeArea()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            EditorTopBar(
                state = state,
                title = title ?: state.name,
                onBack = viewModel::requestClose,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = { viewModel.save() },
                onSaveAs = { saveAs(false) },
                onShareDraft = { shareText(selected = false) },
                onShareSelection = { shareText(selected = true) },
                sharing = delivery.busy,
            )
            when (val phase = state.phase) {
                EditorPhase.Idle -> {}
                EditorPhase.Loading -> Centered {
                    CircularProgressIndicator(color = EzColors.Primary)
                    Text("Opening ${state.name}…", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, textAlign = TextAlign.Center)
                }
                is EditorPhase.Refused -> Centered {
                    Text(phase.title, style = MaterialTheme.typography.titleLarge, color = EzColors.Text, textAlign = TextAlign.Center)
                    Text(phase.detail, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
                    EzTextButton("Back", viewModel::requestClose)
                }
                EditorPhase.Editing -> {
                    header()
                    if (waitingFile != null) {
                        EditorNote("$waitingFile will open when you close the editor.")
                    }
                    state.notice?.let { notice ->
                        EditorNote(notice.message, if (notice.isError) viewModel::dismissNotice else null)
                    }
                    EditorField(viewModel, state, saveName, focusOnStart, Modifier.weight(1f))
                }
            }
        }
    }

    shareError?.let { message ->
        EzDialog(
            onDismissRequest = { shareError = null },
            title = { Text("Share text") },
            text = { Text(message) },
            confirmButton = { EzTextButton("OK", { shareError = null }) },
        )
    }

    when (state.prompt) {
        EditorPrompt.UnsavedChanges -> EzDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("Save your changes?") },
            text = { Text("Your changes to ${title ?: state.name} haven't been saved.") },
            confirmButton = {
                TextButton(onClick = saveAndClose) {
                    Text(if (state.canOverwrite) "Save" else "Save As", color = EzColors.Accent)
                }
            },
            dismissButton = {
                Row {
                    EzTextButton("Discard", viewModel::discard)
                    EzTextButton("Keep editing", viewModel::dismissPrompt)
                }
            },
        )
        EditorPrompt.ChangedOnDisk -> EzDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("The file has changed") },
            text = { Text("Something else changed ${state.name} since you opened it. Overwriting replaces that version with yours.") },
            confirmButton = {
                TextButton(onClick = { viewModel.save(force = true, closeAfter = viewModel.closesAfterSave) }) {
                    Text("Overwrite", color = EzColors.Accent)
                }
            },
            dismissButton = {
                Row {
                    EzTextButton("Save As", { saveAs(viewModel.closesAfterSave) })
                    EzTextButton("Keep editing", viewModel::dismissPrompt)
                }
            },
        )
        null -> {}
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    title: String,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShareDraft: () -> Unit,
    onShareSelection: () -> Unit,
    sharing: Boolean,
) {
    val editing = state.phase == EditorPhase.Editing
    var menuOpen by remember { mutableStateOf(false) }
    EzTopBar(
        title = title,
        onBack = onBack,
        gutter = EzSpacing.ReaderGutter,
        subtitle = if (!editing) null else ({
                Row(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.modified) {
                        Text("Modified", style = MaterialTheme.typography.labelSmall, color = EzColors.Accent, maxLines = 1)
                    }
                    Text(
                        state.format.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = EzColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }),
    ) {
        if (editing) {
            BarIconButton(UndoIcon, "Undo", onUndo, enabled = state.canUndo && !state.busy)
            BarIconButton(RedoIcon, "Redo", onRedo, enabled = state.canRedo && !state.busy)
            if (state.canOverwrite) {
                EzTextButton("Save", onSave, enabled = state.modified && !state.busy)
            } else {
                EzTextButton("Save As", onSaveAs, enabled = !state.busy)
            }
            Box {
                BarIconButton(EzIcons.More, "More options", { menuOpen = true })
                EzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (state.canOverwrite) {
                        EzMenuItem("Save As", icon = EzIcons.Document, enabled = !state.busy, onClick = {
                            menuOpen = false
                            onSaveAs()
                        })
                    }
                    if (!state.field.selection.collapsed) {
                        EzMenuItem("Share selected text", icon = TidyIcons.Share, enabled = !state.busy && !sharing, onClick = {
                            menuOpen = false
                            onShareSelection()
                        })
                    }
                    EzMenuItem("Share draft text", icon = TidyIcons.Share, enabled = !state.busy && !sharing && state.field.text.isNotBlank(), onClick = {
                        menuOpen = false
                        onShareDraft()
                    })
                }
            }
        }
    }
}

private const val MAX_SHARED_TEXT_CHARS = 100_000

@Composable
private fun EditorField(
    viewModel: TextEditorViewModel,
    state: EditorUiState,
    fileName: String,
    focusOnStart: Boolean,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    if (focusOnStart) LaunchedEffect(Unit) { focus.requestFocus() }
    // Autocorrect helps prose, but would silently change code and data.
    val prose = fileName.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "markdown")
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            .raised(EzShapes.Page)
            .clip(EzShapes.Page)
            .background(EzPaper.Background),
    ) {
        if (state.field.text.isEmpty()) {
            Text("Start typing", color = EzPaper.TextSecondary, style = EditorTextStyle, modifier = Modifier.padding(14.dp))
        }
        BasicTextField(
            value = state.field,
            onValueChange = viewModel::onFieldChange,
            readOnly = state.busy,
            textStyle = EditorTextStyle,
            cursorBrush = SolidColor(EzColors.Primary),
            keyboardOptions = KeyboardOptions(
                capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = prose,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "File text" },
        )
    }
}

private val EditorTextStyle = TextStyle(color = EzPaper.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun EditorNote(message: String, onDismiss: (() -> Unit)? = null) {
    EzNotice(message, onDismiss, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    EzStatusCard(Modifier.padding(horizontal = 24.dp)) { content() }
}
