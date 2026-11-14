package com.ezfiles.reader.presentation.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.assistant.LocalAssistantDraft
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.MAX_TEXT_PDF_CHARS
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.TextPdfAlignment
import com.ezfiles.reader.domain.TextPdfFont
import com.ezfiles.reader.domain.TextPdfStyle
import com.ezfiles.reader.domain.TextPdfTemplate
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.decodeImage
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.raised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long enough to read sharply on a phone's preview card, small enough to redraw while typing. */
private const val PREVIEW_WIDTH_PX = 900

/** Waits for a pause in typing, so each keystroke doesn't redo the layout. */
private const val PREVIEW_DELAY_MS = 250L

/**
 * Longer drafts aren't kept in saved instance state, whose size limit would
 * crash the app. They survive rotation (the activity isn't recreated) but not
 * the system ending the app in the background.
 */
private const val MAX_SAVED_DRAFT_CHARS = 50_000

private val DraftSaver = Saver<String, String>(
    save = { draft -> draft.takeIf { it.length <= MAX_SAVED_DRAFT_CHARS } },
    restore = { it },
)

private val ACCENT_CHOICES = listOf(
    "Red" to 0xFFE60023.toInt(),
    "Plum" to 0xFFB5478F.toInt(),
    "Blue" to 0xFF1F6FB2.toInt(),
    "Navy" to 0xFF1E3A5F.toInt(),
    "Teal" to 0xFF0F766E.toInt(),
    "Bronze" to 0xFF8A6D3B.toInt(),
    "Graphite" to 0xFF3A3A3A.toInt(),
)

private val BACKGROUND_CHOICES = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Cream" to 0xFFFBF6EA.toInt(),
    "Warm white" to 0xFFFFF7F2.toInt(),
    "Pink" to 0xFFFDE7F0.toInt(),
    "Mint" to 0xFFE6F4EE.toInt(),
    "Sky" to 0xFFE6F0FA.toInt(),
    "Near black" to 0xFF14121A.toInt(),
)

@Composable
fun TextToPdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val creator = LocalPdfCreator.current
    val files = LocalDocumentFiles.current
    val assistantDraft = LocalAssistantDraft.current
    val scope = rememberCoroutineScope()
    val runner = remember(scope) { ExportRunner(scope) }
    var text by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(assistantDraft?.text.orEmpty()) }
    var style by remember { mutableStateOf(TextPdfStyle.of(TextPdfTemplate.MinimalLetter)) }
    var tooLong by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(1) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewAspect by remember { mutableStateOf(595f / 842f) }
    var previewError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text, style, pageIndex) {
        delay(PREVIEW_DELAY_MS)
        try {
            val page = creator.textPreview(text, style, pageIndex, PREVIEW_WIDTH_PX)
            preview = withContext(Dispatchers.Default) { decodeImage(page.png, PREVIEW_WIDTH_PX * 2) }
            previewAspect = page.widthPt.toFloat() / page.heightPt
            pageCount = page.pageCount
            pageIndex = page.pageIndex
            previewError = null
        } catch (e: DocumentException) {
            previewError = e.error.detail
        }
    }

    val pdfSaver = rememberDocumentSaver { target ->
        if (target != null) {
            val draft = text
            val chosen = style
            runner.run(target) { onProgress ->
                val pages = creator.textToPdf(draft, chosen, target, onProgress)
                "Saved $pages ${if (pages == 1) "page" else "pages"}."
            }
        }
    }
    val pngSaver = rememberDocumentSaver { target ->
        if (target != null) {
            val draft = text
            val chosen = style
            runner.run(target) {
                val pages = creator.textToPng(draft, chosen, target)
                if (pages == 1) "Saved the page as a picture." else "Saved page 1 of $pages as a picture."
            }
        }
    }

    CreateToolFrame("Text to PDF", onClose) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PagePreview(preview, previewAspect, previewError)
                if (pageCount > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        BarIconButton(EzIcons.ChevronLeft, "Previous page", { pageIndex -= 1 }, enabled = pageIndex > 0)
                        Text(
                            "Page ${pageIndex + 1} of $pageCount",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EzColors.Text,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        BarIconButton(EzIcons.ChevronRight, "Next page", { pageIndex += 1 }, enabled = pageIndex < pageCount - 1)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { changed ->
                        tooLong = changed.length > MAX_TEXT_PDF_CHARS
                        if (!tooLong) {
                            text = changed
                            runner.clear()
                        }
                    },
                    label = { Text("Your text") },
                    isError = tooLong,
                    supportingText = if (tooLong) {
                        { Text("That would be over ${MAX_TEXT_PDF_CHARS / 1000},000 characters, so the change wasn't made.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().height(if (tooLong) 240.dp else 216.dp),
                )
                TextStyleControls(style) { changed ->
                    style = changed
                    runner.clear()
                }
            }
            ExportBar(
                status = runner.status,
                onCancel = runner::cancel,
                onOpen = { uri ->
                    scope.launch {
                        try {
                            onOpen(files.refOf(uri))
                        } catch (e: DocumentException) {
                            runner.report(e.error.detail)
                        }
                    }
                },
            ) {
                if (text.isBlank()) {
                    Text("Type or paste some text to save it.", style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextAction("First page as PNG") { pngSaver.launch("Text.png", "image/png") }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            PrimaryActionButton("Save PDF", EzIcons.Document, { pdfSaver.launch("Text.pdf", "application/pdf") })
                        }
                    }
                }
            }
        }
    }
}

/** The page as the export draws it. */
@Composable
private fun PagePreview(page: ImageBitmap?, aspect: Float, error: String?) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .height(340.dp)
                .aspectRatio(aspect)
                .raised(EzShapes.Page)
                .clip(EzShapes.Page)
                .background(EzPaper.Background),
            contentAlignment = Alignment.Center,
        ) {
            if (page != null) {
                Image(page, "Preview of the page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = EzColors.PrimaryDark, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun TextStyleControls(style: TextPdfStyle, onChange: (TextPdfStyle) -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(EzShapes.Medium, 0.4f).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingLabel("Template")
        ChipRow {
            TextPdfTemplate.entries.forEach { template ->
                EzChip(template.label, template == style.template, { onChange(TextPdfStyle.of(template, style.pageSize)) })
            }
        }
        SettingLabel("Alignment")
        ChipRow {
            TextPdfAlignment.entries.forEach { EzChip(it.label, it == style.alignment, { onChange(style.copy(alignment = it)) }) }
        }
        SettingLabel("Font")
        ChipRow {
            TextPdfFont.entries.forEach { EzChip(it.label, it == style.font, { onChange(style.copy(font = it)) }) }
        }
        SettingLabel("Text size: ${style.textSizePt.toInt()} pt")
        Slider(
            value = style.textSizePt,
            onValueChange = { onChange(style.copy(textSizePt = it.toInt().toFloat())) },
            valueRange = TextPdfStyle.MIN_TEXT_SIZE..TextPdfStyle.MAX_TEXT_SIZE,
        )
        SettingLabel("Page size")
        ChipRow {
            PdfPageSize.entries.filter { it != PdfPageSize.FitImage }.forEach {
                EzChip(it.label, it == style.pageSize, { onChange(style.copy(pageSize = it)) })
            }
        }
        SettingLabel("Margins: ${style.marginPt.toInt()} pt")
        Slider(
            value = style.marginPt,
            onValueChange = { onChange(style.copy(marginPt = it.toInt().toFloat())) },
            valueRange = 18f..108f,
        )
        SettingLabel("Accent colour")
        ChipRow {
            ACCENT_CHOICES.forEach { (name, argb) ->
                ColorSwatch(Color(argb), name, argb == style.accentArgb) { onChange(style.copy(accentArgb = argb)) }
            }
        }
        SettingLabel("Background")
        ChipRow {
            BACKGROUND_CHOICES.forEach { (name, argb) ->
                ColorSwatch(Color(argb), name, argb == style.backgroundArgb) { onChange(style.copy(backgroundArgb = argb)) }
            }
        }
    }
}
