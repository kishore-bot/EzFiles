package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.presentation.create.CreateTool
import com.ezfiles.reader.presentation.components.EzSectionTitle
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glassCard
import com.ezfiles.reader.presentation.theme.pressScale

val LocalPdfTools = staticCompositionLocalOf<PdfTools> { error("PdfTools not provided") }

/** The file open in the reader when the hub was reached from it; tools using [PdfToolHost] start with it. */
val LocalPdfToolsPreselect = compositionLocalOf<DocumentRef?> { null }

/** An authorized handoff's sources, in the order the user requested (for example, a merge). */
val LocalPdfToolsPreselectList = compositionLocalOf<List<DocumentRef>> { emptyList() }

/** A validated enum name selects the tool once; backing out still returns to the hub. */
val LocalPdfToolRequest = compositionLocalOf<String?> { null }

/** Nonzero for assistant handoffs, so repeating an operation receives a fresh flow. */
val LocalPdfToolRequestId = compositionLocalOf<Long> { 0L }

/** The tools listed in the hub. Each has its own screen file in this folder. */
enum class PdfTool(val title: String, val section: PdfToolSection, val color: Color) {
    Merge("Merge PDFs", PdfToolSection.Edit, Color(0xFFE60023)),
    AddPages("Add pages from gallery", PdfToolSection.Edit, Color(0xFF8E5BD9)),
    Split("Split", PdfToolSection.Edit, Color(0xFF0B7F86)),
    Organize("Reorder, rotate, delete", PdfToolSection.Edit, Color(0xFF0862D9)),
    PageSize("Page size", PdfToolSection.Edit, Color(0xFF087F42)),
    AddPassword("Add password", PdfToolSection.Edit, Color(0xFFD9408A)),
    RemovePassword("Remove password", PdfToolSection.Edit, Color(0xFF6B3A63)),
    Sign("Signature", PdfToolSection.Edit, Color(0xFFE0570B)),
    ToImages("PDF to images", PdfToolSection.Convert, Color(0xFF2E9E6B)),
    ToText("PDF to text", PdfToolSection.Convert, Color(0xFF2F3BB5)),
    ToZip("PDF to ZIP", PdfToolSection.Convert, Color(0xFFB0124F)),
}

enum class PdfToolSection(val title: String) { Edit("Edit"), Convert("Convert a PDF") }

private fun PdfTool.icon(): ImageVector = when (this) {
    PdfTool.Merge -> EzIcons.Plus
    PdfTool.AddPages -> EzIcons.Image
    PdfTool.Split -> EzIcons.Lines
    PdfTool.Organize -> EzIcons.Rotate
    PdfTool.PageSize -> EzIcons.SinglePage
    PdfTool.AddPassword, PdfTool.RemovePassword -> EzIcons.Lock
    PdfTool.Sign -> EzIcons.Document
    PdfTool.ToImages -> EzIcons.Image
    PdfTool.ToText -> EzIcons.TextT
    PdfTool.ToZip -> EzIcons.Folder
}

/**
 * Lists the PDF tools and shows the chosen one. [onOpen] shows a saved file in
 * the reader; [onCreateTool] jumps to Images to PDF or Text to PDF.
 */
@Composable
fun PdfToolsScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit, onCreateTool: (CreateTool) -> Unit) {
    val requested = LocalPdfToolRequest.current
    var selected by rememberSaveable(requested) { mutableStateOf(PdfTool.entries.firstOrNull { it.name == requested }) }
    PlatformBackHandler(enabled = selected != null) { selected = null }
    val close = { selected = null }
    when (selected) {
        null -> CreateToolFrame("PDF tools", onClose) { Hub(onSelect = { selected = it }, onCreateTool = onCreateTool) }
        PdfTool.Merge -> MergePdfScreen(close, onOpen)
        PdfTool.AddPages -> AddPagesScreen(close, onOpen)
        PdfTool.Split -> SplitPdfScreen(close, onOpen)
        PdfTool.Organize -> OrganizePdfScreen(close, onOpen)
        PdfTool.PageSize -> PageSizeScreen(close, onOpen)
        PdfTool.AddPassword -> AddPasswordScreen(close, onOpen)
        PdfTool.RemovePassword -> RemovePasswordScreen(close, onOpen)
        PdfTool.Sign -> SignPdfScreen(close, onOpen)
        PdfTool.ToImages -> PdfToImagesScreen(close, onOpen)
        PdfTool.ToText -> PdfToTextScreen(close, onOpen) { onCreateTool(CreateTool.ImageToText) }
        PdfTool.ToZip -> PdfToZipScreen(close, onOpen)
    }
}

@Composable
private fun Hub(onSelect: (PdfTool) -> Unit, onCreateTool: (CreateTool) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (section in PdfToolSection.entries) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(section.title) }
            items(PdfTool.entries.filter { it.section == section }, key = { it.name }) { tool ->
                ToolCell(tool.title, tool.icon(), tool.color) { onSelect(tool) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Make a PDF") }
        item(key = "images") { ToolCell(CreateTool.ImagesToPdf.title, EzIcons.Image, Color(0xFF7A4A2E)) { onCreateTool(CreateTool.ImagesToPdf) } }
        item(key = "text") { ToolCell(CreateTool.TextToPdf.title, EzIcons.Lines, Color(0xFF6832C1)) { onCreateTool(CreateTool.TextToPdf) } }
        item(key = "scan") { ToolCell(CreateTool.ScanToPdf.title, EzIcons.Document, Color(0xFF373C47)) { onCreateTool(CreateTool.ScanToPdf) } }
    }
}

@Composable
private fun SectionTitle(title: String) {
    EzSectionTitle(title, Modifier.padding(start = 4.dp, top = 12.dp))
}

@Composable
private fun ToolCell(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        // Same width and at least three lines of label, so a row of tools lines up on a phone.
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.97f)
            .glassCard(EzShapes.Card, 0.5f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = rememberHapticActivation(onClick))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EzColors.OnPrimary, modifier = Modifier.size(24.dp))
        }
        Text(title, style = MaterialTheme.typography.labelMedium, color = EzColors.Text, textAlign = TextAlign.Center, minLines = 3)
    }
}
