package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.TextSpan
import com.ezfiles.reader.presentation.BlockHighlights
import com.ezfiles.reader.presentation.SearchMatch
import com.ezfiles.reader.presentation.components.FileTypeTile
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.LocalPaperColors

private val CodeBackground = Color(0xFF221D27)
private val CodeText = Color(0xFFF3ECF5)
private val CodeGutter = Color(0xFF8E8496)
private val CELL_PADDING = 12.dp
private const val MAX_MEASURED_ROWS = 60
private val MAX_COLUMN_WIDTH = 280.dp

/** Applies engine spans and search [matches] to [text]; spans outside the text are ignored. */
fun richText(
    text: String,
    spans: List<TextSpan>,
    matches: List<SearchMatch> = emptyList(),
    current: SearchMatch? = null,
    inlineCodeBackground: Color = Color(0xFFF6E8EF),
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (start == end) continue
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { span.underline },
            TextDecoration.LineThrough.takeIf { span.strikethrough },
        )
        addStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                fontFamily = if (span.code) FontFamily.Monospace else null,
                color = span.color?.let(::Color) ?: Color.Unspecified,
                background = if (span.code) inlineCodeBackground else Color.Unspecified,
            ),
            start,
            end,
        )
        span.link?.let { url ->
            addLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = EzColors.PrimaryDark, textDecoration = TextDecoration.Underline)),
                ),
                start,
                end,
            )
        }
    }
    for (match in matches) {
        val start = match.start.coerceIn(0, text.length)
        val end = match.end.coerceIn(start, text.length)
        // Dark text, because code cards draw light text on a dark background.
        addStyle(
            SpanStyle(color = EzPaper.Text, background = if (match == current) EzColors.SearchCurrent else EzColors.SearchMatch),
            start,
            end,
        )
    }
}

/** A block's text, which is its only part, with its search matches. */
@Composable
private fun BlockText(
    text: String,
    spans: List<TextSpan>,
    highlights: BlockHighlights?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val paper = LocalPaperColors.current
    MatchText(richText(text, spans, highlights?.matches.orEmpty(), highlights?.current, inlineCodeBackground = paper.inlineCode), highlights?.current, style, modifier, softWrap)
}

/** Renders any single block except tables, which [TableCard] draws as a group. */
@Composable
fun BlockView(block: ContentBlock, monospace: Boolean = false, compact: Boolean = false, highlights: BlockHighlights? = null, onEntryTap: ((String) -> Unit)? = null) {
    val paper = LocalPaperColors.current
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = paper.text)
    when (block) {
        is ContentBlock.Heading -> BlockText(
            block.text,
            block.spans,
            highlights,
            style = headingStyle(block.level, compact),
            modifier = Modifier.padding(top = if (compact) 4.dp else 14.dp, bottom = 2.dp),
        )
        is ContentBlock.Paragraph -> BlockText(
            block.text,
            block.spans,
            highlights,
            style = if (monospace) body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp) else body,
        )
        is ContentBlock.ListItem -> ListItemView(block, body, highlights)
        is ContentBlock.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(10.dp))
                .background(paper.quoteBackground),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(EzColors.Primary))
            BlockText(
                block.text,
                block.spans,
                highlights,
                style = body.copy(fontStyle = FontStyle.Italic, color = paper.textSecondary),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        is ContentBlock.Code -> CodeCard(block, highlights = highlights)
        ContentBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 12.dp), color = paper.tableBorder, thickness = 1.dp)
        is ContentBlock.TableRow -> TableCard(listOf(block))
        is ContentBlock.Section -> BlockText(
            block.title,
            emptyList(),
            highlights,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = EzColors.PrimaryDark),
            modifier = Modifier
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(paper.tableHeader)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        is ContentBlock.Entry -> EntryRow(block, onEntryTap)
        is ContentBlock.Image -> DocumentImage(block)
        // In the outline a slide is its text; the canvas itself has none.
        is ContentBlock.Slide, is ContentBlock.CellStyle, is ContentBlock.Merge, is ContentBlock.CellRuns, is ContentBlock.Count -> {}
        is ContentBlock.Shape -> ShapeOutline(block.shape, body)
    }
}

@Composable
private fun ShapeOutline(shape: com.ezfiles.reader.domain.SlideShape, body: TextStyle) {
    if (shape.table.isNotEmpty()) {
        TableCard(shape.table.mapIndexed { i, cells -> ContentBlock.TableRow(i, cells, header = i == 0 && shape.tableHeader) })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in shape.paragraphs) {
            if (p.text.isBlank()) continue
            val style = if (p.size >= 28f) headingStyle(2, compact = true) else body
            val prefix = p.bullet?.let { "$it  " } ?: ""
            Text(
                richText(prefix + p.text, p.spans.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }),
                style = style,
                modifier = Modifier.padding(start = (p.level * 16).dp),
            )
        }
    }
}

@Composable
private fun headingStyle(level: Int, compact: Boolean): TextStyle {
    val paper = LocalPaperColors.current
    return when (level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 24.sp else 28.sp,
            lineHeight = if (compact) 30.sp else 34.sp,
            color = paper.text,
        )
        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = EzColors.Primary)
        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = paper.text)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = paper.textSecondary)
    }
}

@Composable
private fun ListItemView(item: ContentBlock.ListItem, body: androidx.compose.ui.text.TextStyle, highlights: BlockHighlights?) {
    val paper = LocalPaperColors.current
    Row(Modifier.padding(start = (18 * item.depth).dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(28.dp).padding(top = 1.dp), contentAlignment = Alignment.CenterStart) {
            when (val marker = item.marker) {
                null -> Box(
                    Modifier
                        .padding(start = 6.dp, top = 9.dp)
                        .size(if (item.depth == 0) 7.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (item.depth == 0) EzColors.Primary else paper.textSecondary),
                )
                "" -> {}
                "☑", "☐" -> Text(marker, style = body.copy(color = if (marker == "☑") EzColors.Positive else paper.textSecondary))
                else -> Text(marker, style = body.copy(fontWeight = FontWeight.SemiBold, color = EzColors.PrimaryDark))
            }
        }
        val done = item.marker == "☑"
        BlockText(
            item.text,
            item.spans,
            highlights,
            style = if (done) body.copy(color = paper.textSecondary, textDecoration = TextDecoration.LineThrough) else body,
        )
    }
}

/** Code on a dark card; source files get a line-number gutter. */
@Composable
fun CodeCard(code: ContentBlock.Code, joinTop: Boolean = false, joinBottom: Boolean = false, highlights: BlockHighlights? = null) {
    val shape = RoundedCornerShape(
        topStart = if (joinTop) 0.dp else 14.dp,
        topEnd = if (joinTop) 0.dp else 14.dp,
        bottomStart = if (joinBottom) 0.dp else 14.dp,
        bottomEnd = if (joinBottom) 0.dp else 14.dp,
    )
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = CodeText,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(CodeBackground)) {
        val language = code.language
        if (language != null && !joinTop) {
            Text(
                language.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFFFF8FA3),
                modifier = Modifier.padding(start = 14.dp, top = 10.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = if (joinTop) 0.dp else 10.dp)) {
            val first = code.firstLine
            if (first != null) {
                val count = code.text.count { it == '\n' } + 1
                Text(
                    (first until first + count).joinToString("\n"),
                    style = style.copy(color = CodeGutter),
                    modifier = Modifier.padding(start = 12.dp, end = 10.dp),
                )
            }
            val textModifier = Modifier.padding(start = if (first == null) 14.dp else 0.dp, end = 14.dp)
            // Syntax colours, unless search marks are showing in this block.
            val coloured = remember(code.text, code.language) { highlightCode(code.language, code.text) }
            if (coloured != null && highlights?.matches.isNullOrEmpty()) {
                MatchText(coloured, null, style, textModifier, false)
            } else {
                BlockText(code.text, emptyList(), highlights, style = style, softWrap = false, modifier = textModifier)
            }
        }
    }
}

/**
 * A document table drawn as one card: tinted header rows, striped body rows,
 * and column widths from measured text (see [fitColumns]). Tables that cannot
 * fit scroll sideways, with a fade at the edge to show there is more.
 * [firstBlock] is the page index of the first row, which [highlights] refer to.
 */
@Composable
fun TableCard(rows: List<ContentBlock.TableRow>, firstBlock: Int = 0, highlights: BlockHighlights? = null) {
    val columns = rows.maxOfOrNull { it.cells.size } ?: 0
    if (columns == 0) return
    val paper = LocalPaperColors.current
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(color = paper.text)
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measured = remember(rows, density) {
        val natural = FloatArray(columns)
        val minimum = FloatArray(columns)
        // Measuring every cell of a long table would stall the frame it appears in.
        for (row in rows.take(MAX_MEASURED_ROWS)) {
            val style = if (row.header) headerStyle else bodyStyle
            row.cells.forEachIndexed { column, cell ->
                if (column >= columns || cell.isEmpty()) return@forEachIndexed
                val lines = cell.lines()
                val full = lines.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toFloat()
                // Short values ("$112.4 M", "Net profit") never break; longer
                // text may wrap at its longest word.
                val words = cell.split(' ', '\n').filter { it.isNotEmpty() }
                val unbreakable = words.size <= 2 || cell.length <= 14
                val min = if (unbreakable) {
                    full
                } else {
                    words.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toFloat()
                }
                natural[column] = maxOf(natural[column], full)
                minimum[column] = maxOf(minimum[column], min)
            }
        }
        val padding = with(density) { (CELL_PADDING * 2).toPx() } + 2f
        val cap = with(density) { MAX_COLUMN_WIDTH.toPx() }
        Pair(
            natural.map { with(density) { (it + padding).coerceAtMost(cap).toDp() } },
            minimum.map { with(density) { (it + padding).coerceAtMost(cap).toDp() } },
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = fitColumns(measured.first, measured.second, maxWidth)
        val scrolls = widths.fold(0.dp) { acc, w -> acc + w } > maxWidth + 1.dp
        val shape = RoundedCornerShape(12.dp)
        val scroll = rememberScrollState()
        Box {
            Column(
                Modifier
                    .clip(shape)
                    .border(1.dp, paper.tableBorder, shape)
                    .then(if (scrolls) Modifier.horizontalScroll(scroll) else Modifier),
            ) {
                var body = 0
                rows.forEachIndexed { index, row ->
                    val background = when {
                        row.header -> paper.tableHeader
                        else -> if (body++ % 2 == 1) paper.tableStripe else paper.background
                    }
                    Row(Modifier.background(background).height(IntrinsicSize.Min)) {
                        for (column in 0 until columns) {
                            val cell = row.cells.getOrElse(column) { "" }
                            val hits = highlights?.inPart(firstBlock + index, column).orEmpty()
                            val current = highlights?.currentIn(firstBlock + index, column)
                            MatchText(
                                text = if (hits.isEmpty()) AnnotatedString(cell) else richText(cell, emptyList(), hits, current),
                                current = current,
                                style = if (row.header) headerStyle else bodyStyle,
                                modifier = Modifier
                                    .width(widths[column])
                                    .fillMaxHeight()
                                    .padding(horizontal = CELL_PADDING, vertical = 9.dp),
                            )
                        }
                    }
                    if (index < rows.lastIndex) HorizontalDivider(color = paper.tableBorder, thickness = 0.5.dp)
                }
            }
            if (scrolls && scroll.value < scroll.maxValue) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(28.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, paper.background.copy(alpha = 0.9f)))),
                    )
                }
            }
        }
    }
}

/**
 * Column widths for [available] space, from each column's one-line
 * [natural] width and its narrowest acceptable [minimum]. Narrow tables
 * stretch to fill; wider ones start from the minimums and share the spare
 * width by how much more each column wants. Only tables whose minimums do
 * not fit keep natural widths and scroll.
 */
private fun fitColumns(natural: List<Dp>, minimum: List<Dp>, available: Dp): List<Dp> {
    val total = natural.fold(0.dp) { acc, w -> acc + w }
    if (total <= available) return natural.map { it * (available / total) }
    val minTotal = minimum.fold(0.dp) { acc, w -> acc + w }
    if (minTotal > available) return natural
    val wants = natural.indices.map { natural[it] - minimum[it] }
    val wantTotal = wants.fold(0.dp) { acc, w -> acc + w }
    val spare = available - minTotal
    return natural.indices.map { i -> minimum[i] + if (wantTotal > 0.dp) spare * (wants[i] / wantTotal) else 0.dp }
}

@Composable
private fun EntryRow(entry: ContentBlock.Entry, onTap: ((String) -> Unit)?) {
    val clickable = onTap != null && !entry.isDirectory
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (clickable) it.clip(RoundedCornerShape(12.dp)).clickable { onTap!!(entry.path) } else it }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (entry.isDirectory) {
            Box(Modifier.size(width = 40.dp, height = 46.dp), contentAlignment = Alignment.Center) {
                Text("📁", fontSize = 28.sp)
            }
        } else {
            FileTypeTile(FileType.of(entry.path, null))
        }
        val paper = LocalPaperColors.current
        Column(Modifier.weight(1f)) {
            Text(
                entry.path.trimEnd('/').substringAfterLast('/'),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = paper.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folder = entry.path.trimEnd('/').substringBeforeLast('/', "")
            Text(
                listOfNotNull(folder.ifEmpty { null }, if (entry.isDirectory) "Folder" else formatBytes(entry.size)).joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A reading column that stays comfortable on tablets. */
val ReadingWidth: Dp = 720.dp

fun Modifier.readingWidth(): Modifier = widthIn(max = ReadingWidth)
