package com.ezfiles.reader.presentation.create

import androidx.compose.foundation.clickable
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
import com.ezfiles.reader.presentation.components.isWideLandscape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.components.EzTopBar
import com.ezfiles.reader.presentation.pdftools.PdfToolsScreen
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.readerBackground
import com.ezfiles.reader.presentation.theme.appSafeArea
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ezfiles.reader.presentation.tidy.TidyIcons
import com.ezfiles.reader.presentation.tidy.TidyUpScreen

val LocalPdfCreator = staticCompositionLocalOf<PdfCreator> { error("PdfCreator not provided") }

/** The tools that make a new document, reached from the library's Create button. */
enum class CreateTool(val title: String, val subtitle: String) {
    PdfTools("PDF tools", "Merge, split, rotate, sign, add a password and more"),
    ScanToPdf("Scan to PDF", "Use the camera, straighten and clean up pages"),
    ImageToText("Image to text", "Read text from a photo or the camera, on this device"),
    ImagesToPdf("Images to PDF", "Turn pictures into one PDF"),
    TextToPdf("Text to PDF", "Type or paste text and style it"),
    NewText("New text file", "Write notes or Markdown"),
    TidyUp("Tidy up", "Find copies and look-alikes, gather receipts and tickets"),
}

private fun CreateTool.icon(): ImageVector = when (this) {
    CreateTool.PdfTools -> EzIcons.PdfFile
    CreateTool.ScanToPdf -> EzIcons.Camera
    CreateTool.ImageToText -> EzIcons.TextT
    CreateTool.ImagesToPdf -> EzIcons.Image
    CreateTool.TextToPdf -> EzIcons.Lines
    CreateTool.NewText -> EzIcons.NoteAdd
    CreateTool.TidyUp -> TidyIcons.Sparkles
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CreateSheet(onSelect: (CreateTool) -> Unit, onDismiss: () -> Unit) {
    val columns = if (isWideLandscape()) 2 else 1
    EzSheet(onDismissRequest = onDismiss, title = "Tools") {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            for (tools in CreateTool.entries.chunked(columns)) {
                Row(Modifier.fillMaxWidth()) {
                    for (tool in tools) {
                        CreateToolChoice(tool, { onSelect(tool) }, Modifier.weight(1f))
                    }
                    if (tools.size < columns) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CreateToolChoice(tool: CreateTool, onSelect: () -> Unit, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = rememberHapticActivation(onSelect))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).glass(EzShapes.Small, 0.9f), contentAlignment = Alignment.Center) {
            Icon(tool.icon(), null, tint = EzColors.Primary, modifier = Modifier.size(EzSpacing.BarIcon))
        }
        Column(Modifier.weight(1f)) {
            Text(tool.title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
            Text(tool.subtitle, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
        }
        Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * A full-screen tool. Each tool owns its file. [onOpen] shows a document it
 * just saved in the reader.
 */
@Composable
fun CreateToolScreen(tool: CreateTool, onClose: () -> Unit, onSwitchTool: (CreateTool) -> Unit, onOpen: (DocumentRef) -> Unit) {
    when (tool) {
        CreateTool.PdfTools -> PdfToolsScreen(onClose, onOpen, onSwitchTool)
        CreateTool.ScanToPdf -> ScanToPdfScreen(onClose, onOpen)
        CreateTool.ImageToText -> ImageToTextScreen(onClose, onOpen)
        CreateTool.ImagesToPdf -> ImagesToPdfScreen(onClose, onOpen)
        CreateTool.TextToPdf -> TextToPdfScreen(onClose, onOpen)
        CreateTool.NewText -> NewTextScreen(onClose, onOpen)
        CreateTool.TidyUp -> TidyUpScreen(onClose, onOpen)
    }
}

/** The shared frame of a tool: background, glass top bar with a back button, and content inside the screen gutter. */
@Composable
fun CreateToolFrame(title: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().readerBackground().appSafeArea()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            EzTopBar(title = title, onBack = onClose, actions = actions)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = EzSpacing.Gutter)) { content() }
        }
    }
}
