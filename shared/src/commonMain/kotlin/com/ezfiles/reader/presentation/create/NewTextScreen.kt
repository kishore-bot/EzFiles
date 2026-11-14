package com.ezfiles.reader.presentation.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.application.assistant.LocalAssistantDraft
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.editor.TextEditorScreen
import com.ezfiles.reader.presentation.editor.TextEditorViewModel
import com.ezfiles.reader.presentation.editor.EditorPhase

@Composable
fun NewTextScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val files = LocalDocumentFiles.current
    val draft = LocalAssistantDraft.current
    val editorKey = draft?.requestId?.takeIf { it != 0L }?.let { "new-text-editor-$it" } ?: "new-text-editor"
    val editor = viewModel(key = editorKey) { TextEditorViewModel(files) }
    LaunchedEffect(editor) {
        if (editor.state.phase == EditorPhase.Idle) {
            editor.startNew()
            draft?.let { editor.onFieldChange(TextFieldValue(it.text)) }
        }
    }
    val title = draft?.title.orEmpty().trim().trim('.').ifBlank { "Untitled" }
    val initialExtension = if (title.endsWith(".md", ignoreCase = true)) "md" else "txt"
    val baseName = if (title.endsWith(".txt", ignoreCase = true) || title.endsWith(".md", ignoreCase = true)) {
        title.substringBeforeLast('.').ifBlank { "Untitled" }
    } else title
    var extension by rememberSaveable { mutableStateOf(initialExtension) }
    TextEditorScreen(
        viewModel = editor,
        onFinished = { saved -> if (saved != null) onOpen(saved) else onClose() },
        title = "New text file",
        suggestedName = "$baseName.$extension",
        focusOnStart = true,
        header = {
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((value, label) in listOf("txt" to "Text (.txt)", "md" to "Markdown (.md)")) {
                    EzChip(label, selected = extension == value, onClick = { extension = value })
                }
            }
        },
    )
}
