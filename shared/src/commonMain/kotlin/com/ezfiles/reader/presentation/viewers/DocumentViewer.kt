package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.presentation.PageFind
import com.ezfiles.reader.presentation.ReaderUiState

/**
 * Picks the viewer for a document kind. Each viewer owns its own layout
 * semantics; there is no lowest-common-denominator renderer (ADR-009).
 */
@Composable
fun DocumentViewer(
    state: ReaderUiState.Ready,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onPageFind: (PageFind) -> Unit = {},
    onEntryTap: ((String) -> Unit)? = null,
    onSaveCsv: ((String) -> Unit)? = null,
    onSaveXlsx: ((sheetIndex: Int, edits: Map<Pair<Int, Int>, String>) -> Unit)? = null,
    darkReading: Boolean = false,
) = EmbeddedMediaHost {
    when (state.info.kind) {
        DocumentKind.Text, DocumentKind.Archive -> when {
            state.canPreviewHtml -> HtmlViewer(state, onPageFind, darkReading = darkReading)
            state.info.format == DocumentFormat.Docx || state.info.format == DocumentFormat.Odt ->
                WordPagesViewer(state, onPageFind, darkReading = darkReading) { BlockListViewer(state, onLoadMore, onRetry) }
            state.info.format == DocumentFormat.Markdown && !state.source -> MarkdownPreviewViewer(state, onPageFind, darkReading = darkReading)
            state.info.format == DocumentFormat.Epub -> BookViewer(state, onPageFind) { BlockListViewer(state, onLoadMore, onRetry) }
            state.info.format == DocumentFormat.Html -> Column(Modifier.fillMaxSize()) {
                HtmlNote("This page is too large to preview (over 8 MB), so its code is shown.")
                Box(Modifier.weight(1f).fillMaxWidth()) { BlockListViewer(state, onLoadMore, onRetry) }
            }
            state.info.format == DocumentFormat.SourceCode && !state.source -> CodeBookViewer(state, onLoadMore, onRetry, darkReading = darkReading)
            state.info.format == DocumentFormat.Json && !state.source -> JsonTreeViewer(state, onLoadMore, onRetry, darkReading = darkReading)
            else -> BlockListViewer(state, onLoadMore, onRetry, onEntryTap)
        }
        DocumentKind.Presentation -> SlideshowViewer(state, onLoadMore, onRetry)
        DocumentKind.Spreadsheet -> SpreadsheetViewer(state, onLoadMore, onRetry, onSaveCsv, onSaveXlsx)
        // PDFs get the full page reader (PdfReader) from ReaderScreen.
        DocumentKind.Pdf -> PdfReader(state.ref)
        DocumentKind.Image -> ImageViewer(state)
    }
}
