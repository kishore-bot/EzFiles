package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.CodeFold
import com.ezfiles.reader.domain.CodeFolding
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.FoldKind
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ezfiles.reader.presentation.ReaderUiState

private data class CodeBookColors(
    val background: Color,
    val text: Color,
    val gutter: Color,
    val foldIndicator: Color,
    val foldedSuffix: Color,
)

private val DarkBookColors = CodeBookColors(
    background = Color(0xFF1B1B1F),
    text = Color(0xFFD4D4D4),
    gutter = Color(0xFF858585),
    foldIndicator = Color(0xFF569CD6),
    foldedSuffix = Color(0xFF6A6A6A),
)

private val LightBookColors = CodeBookColors(
    background = Color(0xFFFAFAFA),
    text = Color(0xFF1E1E1E),
    gutter = Color(0xFF999999),
    foldIndicator = Color(0xFF0065A9),
    foldedSuffix = Color(0xFFAAAAAA),
)

@Composable
fun CodeBookViewer(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit, darkReading: Boolean = true) {
    val theme = if (darkReading) DarkBookColors else LightBookColors
    val listState = rememberLazyListState()
    ListScrollControl(listState)
    LoadMoreWhenNearEnd(listState, state, onLoadMore)

    val allCode = remember(state.pages) {
        val sb = StringBuilder()
        var lang: String? = null
        for (page in state.pages) {
            for (block in page) {
                when (block) {
                    is ContentBlock.Code -> {
                        if (lang == null) lang = block.language
                        sb.append(block.text)
                        if (!block.text.endsWith('\n')) sb.append('\n')
                    }
                    is ContentBlock.Paragraph -> {
                        sb.append(block.text)
                        if (!block.text.endsWith('\n')) sb.append('\n')
                    }
                    else -> {}
                }
            }
        }
        Pair(lang, sb.toString())
    }

    val (language, text) = allCode
    val highlighted = remember(text, language, darkReading) { highlightCode(language, text, dark = darkReading) }
    val lines = remember(text) { text.lines() }
    val highlightedLines = remember(highlighted, lines) {
        if (highlighted == null) return@remember null
        var offset = 0
        lines.map { line ->
            val start = offset
            val end = offset + line.length
            offset = end + 1
            highlighted.subSequence(start, end.coerceAtMost(highlighted.length))
        }
    }

    // IntelliJ-style folding: imports start collapsed, everything else open.
    val extension = remember(state.ref) { state.ref.displayName.substringAfterLast('.', "").lowercase() }
    val folds = remember(lines, extension) { CodeFolding.regions(lines, extension) }
    val foldStarts = remember(folds) { folds.associateBy { it.start } }
    val toggled = remember(state.ref) { mutableStateMapOf<Int, Boolean>() }
    fun isCollapsed(fold: CodeFold) = toggled[fold.start] ?: (fold.kind == FoldKind.Imports)

    val visibleLines = remember(lines.size, folds, toggled.toMap()) {
        val hidden = BooleanArray(lines.size)
        for (fold in folds) {
            if (!isCollapsed(fold) || hidden[fold.start]) continue
            for (line in (fold.start + 1)..fold.lastHidden.coerceAtMost(lines.size - 1)) hidden[line] = true
        }
        lines.indices.filter { !hidden[it] }
    }
    val anyOpen = folds.any { !isCollapsed(it) }

    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = theme.text,
    )
    val gutterStyle = style.copy(color = theme.gutter)
    val gutterWidth = "${lines.size}".length

    Box(Modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = if (folds.isEmpty()) 24.dp else 56.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(visibleLines.size, key = { visibleLines[it] }) { idx ->
                val lineIndex = visibleLines[idx]
                val fold = foldStarts[lineIndex]
                val collapsed = fold != null && isCollapsed(fold)
                val toggle = { if (fold != null) toggled[fold.start] = !collapsed }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${lineIndex + 1}".padStart(gutterWidth),
                        style = gutterStyle,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    // The fold arrow: a full-height touch target, like the IDE gutter.
                    Box(
                        Modifier
                            .width(32.dp)
                            .then(if (fold != null) Modifier.clickable(onClick = toggle) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (fold != null) Text(if (collapsed) "▸" else "▾", style = gutterStyle.copy(color = theme.foldIndicator))
                    }
                    val lineText: AnnotatedString = highlightedLines?.getOrNull(lineIndex) ?: AnnotatedString(lines[lineIndex])
                    if (collapsed && fold != null) {
                        // Tapping the placeholder opens the fold, as clicking it does in the IDE.
                        Text(
                            buildAnnotatedString {
                                append(lineText)
                                withStyle(SpanStyle(color = theme.foldIndicator, background = theme.foldIndicator.copy(alpha = 0.12f))) {
                                    append(CodeFolding.placeholder(fold, lines))
                                }
                            },
                            style = style,
                            modifier = Modifier.clickable(onClick = toggle),
                        )
                    } else {
                        Text(lineText, style = style)
                    }
                }
            }
            item(key = "footer") { DocumentFooter(state, onRetry) }
        }
        if (folds.isNotEmpty()) {
            Text(
                if (anyOpen) "Collapse all" else "Expand all",
                style = MaterialTheme.typography.labelLarge,
                color = theme.foldIndicator,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
                    .background(theme.background.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .clickable { folds.forEach { toggled[it.start] = anyOpen } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
