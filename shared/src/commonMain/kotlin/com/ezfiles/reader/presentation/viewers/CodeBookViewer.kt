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
import com.ezfiles.reader.domain.ContentBlock
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

private data class FoldRegion(val startLine: Int, val endLine: Int)

@Composable
fun CodeBookViewer(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit, darkReading: Boolean = true) {
    val theme = if (darkReading) DarkBookColors else LightBookColors
    val listState = rememberLazyListState()
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

    val foldRegions = remember(lines) { detectFoldRegions(lines) }
    val foldStarts = remember(foldRegions) { foldRegions.associateBy { it.startLine } }
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }

    val visibleLines = remember(lines.size, collapsed.toMap()) {
        val hidden = mutableSetOf<Int>()
        for ((startLine, isCollapsed) in collapsed) {
            if (isCollapsed) {
                val region = foldStarts[startLine] ?: continue
                for (line in (region.startLine + 1)..region.endLine) hidden.add(line)
            }
        }
        lines.indices.filter { it !in hidden }
    }

    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = theme.text,
    )
    val gutterStyle = style.copy(color = theme.gutter)
    val gutterWidth = "${lines.size}".length
    val gutterChars = gutterWidth + 2

    Box(Modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(visibleLines.size, key = { visibleLines[it] }) { idx ->
                val lineIndex = visibleLines[idx]
                val fold = foldStarts[lineIndex]
                val isCollapsed = collapsed[lineIndex] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp),
                ) {
                    // Gutter: line number + fold indicator
                    Box(
                        Modifier
                            .width((gutterChars * 9).dp)
                            .then(if (fold != null) Modifier.clickable {
                                collapsed[lineIndex] = !isCollapsed
                            } else Modifier),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        val gutterText = buildAnnotatedString {
                            if (fold != null) {
                                withStyle(SpanStyle(color = theme.foldIndicator)) {
                                    append(if (isCollapsed) "▶" else "▼")
                                }
                                append(" ")
                            }
                            withStyle(SpanStyle(color = theme.gutter)) {
                                append("${lineIndex + 1}".padStart(gutterWidth))
                            }
                        }
                        Text(gutterText, style = gutterStyle, modifier = Modifier.padding(end = 12.dp))
                    }
                    // Code content
                    if (isCollapsed && fold != null) {
                        val lineText = highlightedLines?.getOrNull(lineIndex)
                        val collapsedText = buildAnnotatedString {
                            if (lineText != null) append(lineText) else append(lines[lineIndex])
                            withStyle(SpanStyle(color = theme.foldedSuffix)) {
                                append("  ...  }")
                            }
                        }
                        Text(collapsedText, style = style)
                    } else {
                        val lineText = highlightedLines?.getOrNull(lineIndex)
                        if (lineText != null) {
                            Text(lineText, style = style)
                        } else {
                            Text(lines[lineIndex], style = style)
                        }
                    }
                }
            }
            item(key = "footer") { DocumentFooter(state, onRetry) }
        }
    }
}

private fun detectFoldRegions(lines: List<String>): List<FoldRegion> {
    val regions = mutableListOf<FoldRegion>()
    val braceStack = mutableListOf<Int>()
    for (i in lines.indices) {
        val line = lines[i]
        for (ch in line) {
            when (ch) {
                '{' -> braceStack.add(i)
                '}' -> if (braceStack.isNotEmpty()) {
                    val start = braceStack.removeLast()
                    if (i - start >= 2) {
                        regions.add(FoldRegion(start, i))
                    }
                }
            }
        }
    }
    return regions
}
