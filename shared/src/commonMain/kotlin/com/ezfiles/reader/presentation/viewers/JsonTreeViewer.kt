package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.presentation.ReaderUiState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.LocalPaperColors

private object DarkJson {
    val key = Color(0xFF9CDCFE)
    val string = Color(0xFFCE9178)
    val number = Color(0xFFB5CEA8)
    val bool = Color(0xFF569CD6)
    val nullColor = Color(0xFF808080)
    val bracket = Color(0xFFD4D4D4)
}

private object LightJson {
    val key = Color(0xFF0451A5)
    val string = Color(0xFFA31515)
    val number = Color(0xFF098658)
    val bool = Color(0xFF0000FF)
    val nullColor = Color(0xFF808080)
    val bracket = Color(0xFF1E1E1E)
}

private data class JsonColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val bool: Color,
    val nullColor: Color,
    val bracket: Color,
)

private val LocalJsonColors = compositionLocalOf {
    JsonColors(DarkJson.key, DarkJson.string, DarkJson.number, DarkJson.bool, DarkJson.nullColor, DarkJson.bracket)
}

private sealed interface JsonNode {
    data class Obj(val entries: List<Pair<String, JsonNode>>) : JsonNode
    data class Arr(val items: List<JsonNode>) : JsonNode
    data class Str(val value: String) : JsonNode
    data class Num(val value: String) : JsonNode
    data class Bool(val value: Boolean) : JsonNode
    data object Null : JsonNode
}

private class JsonParser(private val text: String) {
    private var pos = 0
    private fun skipWhitespace() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'
    private fun advance(): Char = text[pos++]

    fun parse(): JsonNode? = try { skipWhitespace(); parseValue() } catch (_: Exception) { null }

    private fun parseValue(): JsonNode {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseString())
            't', 'f' -> parseBool()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonNode.Obj {
        advance() // {
        val entries = mutableListOf<Pair<String, JsonNode>>()
        skipWhitespace()
        if (peek() != '}') {
            entries += parseEntry()
            while (peek() == ',') { advance(); entries += parseEntry() }
        }
        if (peek() == '}') advance()
        return JsonNode.Obj(entries)
    }

    private fun parseEntry(): Pair<String, JsonNode> {
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        if (peek() == ':') advance()
        val value = parseValue()
        skipWhitespace()
        return key to value
    }

    private fun parseArray(): JsonNode.Arr {
        advance() // [
        val items = mutableListOf<JsonNode>()
        skipWhitespace()
        if (peek() != ']') {
            items += parseValue()
            skipWhitespace()
            while (peek() == ',') { advance(); items += parseValue(); skipWhitespace() }
        }
        if (peek() == ']') advance()
        return JsonNode.Arr(items)
    }

    private fun parseString(): String {
        advance() // opening "
        val sb = StringBuilder()
        while (pos < text.length && text[pos] != '"') {
            if (text[pos] == '\\' && pos + 1 < text.length) {
                pos++
                when (text[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (pos + 4 < text.length) {
                            val hex = text.substring(pos + 1, pos + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            pos += 4
                        }
                    }
                    else -> { sb.append('\\'); sb.append(text[pos]) }
                }
            } else sb.append(text[pos])
            pos++
        }
        if (pos < text.length) pos++ // closing "
        return sb.toString()
    }

    private fun parseNumber(): JsonNode.Num {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        return JsonNode.Num(text.substring(start, pos))
    }

    private fun parseBool(): JsonNode.Bool {
        val value = peek() == 't'
        pos += if (value) 4 else 5
        return JsonNode.Bool(value)
    }

    private fun parseNull(): JsonNode.Null {
        pos += 4
        return JsonNode.Null
    }
}

@Composable
fun JsonTreeViewer(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit, darkReading: Boolean = true) {
    val colors = if (darkReading) JsonColors(DarkJson.key, DarkJson.string, DarkJson.number, DarkJson.bool, DarkJson.nullColor, DarkJson.bracket)
        else JsonColors(LightJson.key, LightJson.string, LightJson.number, LightJson.bool, LightJson.nullColor, LightJson.bracket)
    CompositionLocalProvider(LocalJsonColors provides colors) {
        JsonTreeContent(state, onLoadMore, onRetry)
    }
}

@Composable
private fun JsonTreeContent(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val listState = rememberLazyListState()
    ListScrollControl(listState)
    LoadMoreWhenNearEnd(listState, state, onLoadMore)

    val jsonText = remember(state.pages) {
        val sb = StringBuilder()
        for (page in state.pages) {
            for (block in page) {
                when (block) {
                    is ContentBlock.Code -> sb.append(block.text)
                    is ContentBlock.Paragraph -> sb.append(block.text)
                    else -> {}
                }
            }
        }
        sb.toString()
    }

    val root = remember(jsonText) { JsonParser(jsonText).parse() }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )

    if (root == null) {
        Box(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Could not parse JSON", color = LocalPaperColors.current.textSecondary, style = style)
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 76.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val lines = mutableListOf<@Composable () -> Unit>()
        collectLines(root, 0, "", collapsed, lines)

        items(lines.size, key = { it }) { index ->
            lines[index]()
        }
        item(key = "footer") { DocumentFooter(state, onRetry) }
    }
}

private fun collectLines(
    node: JsonNode,
    depth: Int,
    path: String,
    collapsed: MutableMap<String, Boolean>,
    lines: MutableList<@Composable () -> Unit>,
    trailing: String = "",
) {
    val indent = "  ".repeat(depth)
    when (node) {
        is JsonNode.Obj -> {
            val isCollapsed = collapsed[path] == true
            lines += @Composable {
                JsonLineRow(indent, isCollapsed, node.entries.size, "{", "}$trailing", path, collapsed)
            }
            if (!isCollapsed) {
                node.entries.forEachIndexed { i, (key, value) ->
                    val childPath = "$path/$key"
                    val childIndent = "  ".repeat(depth + 1)
                    val comma = if (i < node.entries.size - 1) "," else ""
                    when (value) {
                        is JsonNode.Obj, is JsonNode.Arr -> {
                            lines += @Composable { KeyLabel(childIndent, key) }
                            collectLines(value, depth + 1, childPath, collapsed, lines, comma)
                        }
                        else -> lines += @Composable { KeyValueLine(childIndent, key, value, comma) }
                    }
                }
                lines += @Composable { CloseBracket(indent, "}$trailing") }
            }
        }
        is JsonNode.Arr -> {
            val isCollapsed = collapsed[path] == true
            lines += @Composable {
                JsonLineRow(indent, isCollapsed, node.items.size, "[", "]$trailing", path, collapsed)
            }
            if (!isCollapsed) {
                node.items.forEachIndexed { i, item ->
                    val childPath = "$path[$i]"
                    val childIndent = "  ".repeat(depth + 1)
                    val comma = if (i < node.items.size - 1) "," else ""
                    when (item) {
                        is JsonNode.Obj, is JsonNode.Arr -> {
                            collectLines(item, depth + 1, childPath, collapsed, lines, comma)
                        }
                        else -> lines += @Composable { ValueLine(childIndent, item, comma) }
                    }
                }
                lines += @Composable { CloseBracket(indent, "]$trailing") }
            }
        }
        else -> lines += @Composable { ValueLine(indent, node, "") }
    }
}

@Composable
private fun JsonLineRow(
    indent: String,
    isCollapsed: Boolean,
    count: Int,
    open: String,
    close: String,
    path: String,
    collapsed: MutableMap<String, Boolean>,
) {
    val c = LocalJsonColors.current
    val text = buildAnnotatedString {
        append(indent)
        withStyle(SpanStyle(color = c.bracket)) {
            if (isCollapsed) append("$open … $count items … $close")
            else append(open)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { collapsed[path] = !isCollapsed }
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun KeyLabel(indent: String, key: String) {
    val c = LocalJsonColors.current
    Text(
        buildAnnotatedString {
            append(indent)
            withStyle(SpanStyle(color = c.key)) { append("\"$key\"") }
            withStyle(SpanStyle(color = c.bracket)) { append(": ") }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun KeyValueLine(indent: String, key: String, value: JsonNode, comma: String) {
    val c = LocalJsonColors.current
    Text(
        buildAnnotatedString {
            append(indent)
            withStyle(SpanStyle(color = c.key)) { append("\"$key\"") }
            withStyle(SpanStyle(color = c.bracket)) { append(": ") }
            appendValue(value, c)
            if (comma.isNotEmpty()) withStyle(SpanStyle(color = c.bracket)) { append(comma) }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun ValueLine(indent: String, value: JsonNode, comma: String) {
    val c = LocalJsonColors.current
    Text(
        buildAnnotatedString {
            append(indent)
            appendValue(value, c)
            if (comma.isNotEmpty()) withStyle(SpanStyle(color = c.bracket)) { append(comma) }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun CloseBracket(indent: String, bracket: String) {
    val c = LocalJsonColors.current
    Text(
        buildAnnotatedString {
            append(indent)
            withStyle(SpanStyle(color = c.bracket)) { append(bracket) }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendValue(node: JsonNode, c: JsonColors) {
    when (node) {
        is JsonNode.Str -> withStyle(SpanStyle(color = c.string)) { append("\"${node.value.replace("\"", "\\\"")}\"") }
        is JsonNode.Num -> withStyle(SpanStyle(color = c.number)) { append(node.value) }
        is JsonNode.Bool -> withStyle(SpanStyle(color = c.bool, fontWeight = FontWeight.SemiBold)) { append(node.value.toString()) }
        is JsonNode.Null -> withStyle(SpanStyle(color = c.nullColor, fontStyle = FontStyle.Italic)) { append("null") }
        is JsonNode.Obj -> withStyle(SpanStyle(color = c.bracket)) { append("{…}") }
        is JsonNode.Arr -> withStyle(SpanStyle(color = c.bracket)) { append("[…]") }
    }
}
