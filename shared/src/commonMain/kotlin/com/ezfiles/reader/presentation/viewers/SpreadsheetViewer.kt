package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.application.assistant.ReaderPosition
import androidx.compose.foundation.gestures.animateScrollBy
import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import com.ezfiles.reader.presentation.theme.rememberHapticChoice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ezfiles.reader.domain.CellAlign
import com.ezfiles.reader.domain.CellLook
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.presentation.BlockHighlights
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.LocalPaperColors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.EzShapes

private val GutterWidth = 48.dp
private val RowHeight = 34.dp
private const val CHAR_DP = 7.4f
private const val CELL_PADDING_DP = 20f
private const val MIN_CELL_DP = 64f
private const val MAX_CELL_DP = 300f
private const val EMPTY_TRAILING_COLUMNS = 3

/** Pages read without scrolling, so a workbook's later sheets show up as tabs. */
private const val DISCOVERY_PAGES = 12

private val SHEET_VOICE_OPERATIONS = setOf(
    "select_cell", "next_sheet", "previous_sheet", "scroll_down", "scroll_up", "scroll_top", "scroll_bottom",
)

private val GridLine = Color(0xFFE3E6EC)
private val HeaderBackground = Color(0xFFF3F4F7)
private val HeaderText = Color(0xFF5B6170)
private val TitleRowBackground = Color(0xFFEEF2FF)
private val SelectedOutline = Color(0xFF2563EB)
private val SelectedFill = Color(0x142563EB)

private val EditedFill = Color(0x1AFF9800)

/** Opaque, for the frozen row numbers that cells scroll under. */
private val SelectedGutter = Color(0xFFE4EBFB)

private class SheetEditState {
    val edits = mutableStateMapOf<Pair<Int, Int>, String>()
    val hasChanges: Boolean get() = edits.isNotEmpty()

    fun editedValue(row: Int, column: Int, original: String): String =
        edits[row to column] ?: original

    fun set(row: Int, column: Int, value: String, original: String) {
        if (value == original) edits.remove(row to column)
        else edits[row to column] = value
    }
}

/**
 * A spreadsheet the way spreadsheet apps show one: a sheet at a time with
 * tabs, lettered columns and numbered rows that stay put while the grid
 * scrolls, columns as wide as their content, numbers on the right, and long
 * text spilling into empty cells. The workbook's own fills, fonts, colours,
 * alignment and merged cells show as it defines them. Tapping a cell shows its whole value above
 * the grid. Rows are virtualized, and so are columns: only those in view are
 * composed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpreadsheetViewer(
    state: ReaderUiState.Ready,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSaveCsv: ((String) -> Unit)? = null,
    onSaveXlsx: ((sheetIndex: Int, edits: Map<Pair<Int, Int>, String>) -> Unit)? = null,
) {
    val workbook = remember(state.ref, state.source, state.openId) { Workbook() }
    workbook.update(state.pages)
    var selectedSheet by rememberSaveable(state.openId) { mutableIntStateOf(0) }
    val sheetIndex = selectedSheet.coerceIn(0, (workbook.sheets.size - 1).coerceAtLeast(0))
    val sheet = workbook.sheets.getOrNull(sheetIndex)
    val csvEditable = state.info.format == DocumentFormat.Csv
    val xlsxEditable = onSaveXlsx != null
    val editable = csvEditable || xlsxEditable
    val sheetEdits = remember(state.ref, state.openId) { mutableMapOf<Int, SheetEditState>() }
    val editState = if (editable) sheetEdits.getOrPut(sheetIndex) { SheetEditState() } else null
    var editingCell by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }

    // A few pages load on their own, so the tabs of later sheets appear.
    val canLoadMore = !state.endReached && !state.loadingMore && state.loadMoreError == null
    LaunchedEffect(canLoadMore, state.pages.size) {
        if (canLoadMore && state.pages.size < DISCOVERY_PAGES) onLoadMore()
    }

    Column(Modifier.fillMaxSize()) {
        var selected by remember(sheetIndex, state.openId) { mutableStateOf<Pair<Int, Int>?>(null) }
        CellBar(sheet, selected, editState)
        val editing = editingCell
        if (editing != null) {
            CellEditOverlay(
                initial = editing.third,
                onDone = { newValue ->
                    editState?.set(editing.first, editing.second, newValue, editing.third)
                    editingCell = null
                },
                onCancel = { editingCell = null },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sheet == null) {
                DocumentFooter(state, onRetry, Modifier.align(Alignment.Center))
            } else {
                key(sheetIndex) {
                    SheetGrid(
                        state = state,
                        sheet = sheet,
                        isLastSheet = sheetIndex == workbook.sheets.lastIndex,
                        workbook = workbook,
                        editState = editState,
                        selected = selected,
                        onSelect = { selected = it },
                        onShowSheet = { selectedSheet = it },
                        onEditCell = if (editable) { row, col, original ->
                            editingCell = Triple(row, col, editState!!.editedValue(row, col, original))
                        } else null,
                        onLoadMore = onLoadMore,
                        onRetry = onRetry,
                    )
                }
            }
            if (editState != null && editState.hasChanges && sheet != null) {
                val canSave = onSaveCsv != null || onSaveXlsx != null
                if (canSave) Box(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomEnd), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        if (xlsxEditable) "Save As" else "Save",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(EzShapes.Pill)
                            .background(EzColors.Accent)
                            .clickable {
                                if (onSaveCsv != null) onSaveCsv(serializeCsv(sheet, editState))
                                else if (onSaveXlsx != null) onSaveXlsx(sheetIndex, editState.edits.toMap())
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (workbook.sheets.size > 1 || (workbook.sheets.firstOrNull()?.title != null)) {
            SheetTabs(workbook, sheetIndex, loading = !state.endReached) { selectedSheet = it }
        }
    }
}

/** The selected cell's address and full value, like a formula bar. */
@Composable
private fun CellBar(sheet: Sheet?, selected: Pair<Int, Int>?, editState: SheetEditState? = null) {
    val row = selected?.let { sheet?.rows?.getOrNull(it.first) }
    val column = selected?.second ?: 0
    Row(
        Modifier
            .fillMaxWidth()
            .background(EzColors.BackgroundSecondary)
            .border(0.5.dp, EzColors.Hairline)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (row != null) "${columnName(column)}${row.row.row ?: ""}" else "",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = EzColors.TextSecondary,
            modifier = Modifier.width(64.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            val original = row?.row?.cells?.getOrNull(column).orEmpty()
            val display = if (row != null && editState != null && selected != null)
                editState.editedValue(selected.first, column, original)
            else original
            Text(
                display.ifEmpty { null } ?: if (row != null) "(empty)" else "Tap a cell to see all of it",
                fontSize = 14.sp,
                color = if (row != null) EzColors.Text else EzColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetGrid(
    state: ReaderUiState.Ready,
    sheet: Sheet,
    isLastSheet: Boolean,
    workbook: Workbook,
    editState: SheetEditState?,
    selected: Pair<Int, Int>?,
    onSelect: (Pair<Int, Int>) -> Unit,
    onShowSheet: (Int) -> Unit,
    onEditCell: ((Int, Int, String) -> Unit)?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    if (isLastSheet) LoadMoreWhenNearEnd(listState, state, onLoadMore)
    val horizontal = rememberScrollState()
    val columns = maxOf(sheet.columns, 1)
    // Column edges in dp; a few empty columns follow the data, as in spreadsheets, for text to spill into.
    val widths = remember(sheet, sheet.chars.size, sheet.rows.size.coerceAtMost(Sheet.MEASURED_ROWS)) {
        FloatArray(columns + EMPTY_TRAILING_COLUMNS) { c ->
            val chars = maxOf(sheet.chars.getOrElse(c) { 0 }, columnName(c).length + 1)
            (chars * CHAR_DP + CELL_PADDING_DP).coerceIn(MIN_CELL_DP, MAX_CELL_DP)
        }
    }
    val starts = remember(widths) { FloatArray(widths.size + 1).also { for (i in widths.indices) it[i + 1] = it[i] + widths[i] } }
    val gridWidth = GutterWidth + starts.last().dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewport = maxWidth
        // Only columns in view are composed, starting a little early for text that spills right.
        val window by remember(starts, viewport) {
            derivedStateOf {
                val left = with(density) { horizontal.value.toDp() }.value - GutterWidth.value
                val right = left + viewport.value
                val first = starts.indexOfLast { it <= left }.coerceAtLeast(0)
                val last = starts.indexOfFirst { it >= right }.let { if (it < 0) widths.lastIndex else it }
                // In steps of COLUMN_STEP, so sideways scrolling rebuilds the rows once every
                // few columns instead of at each one; a few extra columns cost little.
                val from = ((first - 2).coerceAtLeast(0) / COLUMN_STEP) * COLUMN_STEP
                val to = ((last / COLUMN_STEP) + 1) * COLUMN_STEP
                from..to.coerceAtMost(widths.lastIndex)
            }
        }

        // Search: switch to the sheet with the current match, then bring the cell into view.
        val search = state.search
        val target = search?.currentMatch
        LaunchedEffect(target, search?.revealTick) {
            val match = target ?: return@LaunchedEffect
            val owner = workbook.sheetOf(match.page, match.block)
            if (workbook.sheets[owner] !== sheet) {
                onShowSheet(owner)
                return@LaunchedEffect
            }
            val index = sheet.indexOf(match.page, match.block)
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index + 1 }) listState.scrollToItem(index)
            val left = with(density) { (GutterWidth + starts.getOrElse(match.part) { 0f }.dp).toPx() }
            val width = with(density) { widths.getOrElse(match.part) { MIN_CELL_DP }.dp.toPx() }
            val viewportPx = with(density) { viewport.toPx() }
            if (left < horizontal.value || left + width > horizontal.value + viewportPx) {
                horizontal.animateScrollTo((left - (viewportPx - width) / 2).toInt().coerceAtLeast(0))
            }
        }

        val sheetIndex = workbook.sheets.indexOf(sheet)
        val address = selected?.let { (row, column) -> sheet.rows.getOrNull(row)?.row?.row?.let { "${columnName(column)}$it" } }
        ReaderControlTarget(SHEET_VOICE_OPERATIONS, ReaderPosition(sheet = sheet.title ?: "Sheet ${sheetIndex + 1}", cell = address)) { operation, value ->
            when (operation) {
                "select_cell" -> {
                    val (column, rowNumber) = parseCellAddress(value) ?: return@ReaderControlTarget "missing_cell"
                    val index = sheet.rows.indexOfFirst { it.row.row == rowNumber }
                    when {
                        column >= columns -> "out_of_range"
                        index < 0 -> if (state.endReached) "out_of_range" else "still_loading"
                        else -> {
                            onSelect(index to column)
                            listState.animateScrollToItem(index)
                            val left = with(density) { (GutterWidth + starts.getOrElse(column) { 0f }.dp).toPx() }
                            val width = with(density) { widths.getOrElse(column) { MIN_CELL_DP }.dp.toPx() }
                            val viewportPx = with(density) { viewport.toPx() }
                            if (left < horizontal.value || left + width > horizontal.value + viewportPx) {
                                horizontal.animateScrollTo((left - (viewportPx - width) / 2).toInt().coerceAtLeast(0))
                            }
                            null
                        }
                    }
                }
                "next_sheet" -> if (sheetIndex + 1 > workbook.sheets.lastIndex) "at_last_sheet" else null.also { onShowSheet(sheetIndex + 1) }
                "previous_sheet" -> if (sheetIndex <= 0) "at_first_sheet" else null.also { onShowSheet(sheetIndex - 1) }
                "scroll_top" -> null.also { listState.animateScrollToItem(0) }
                "scroll_bottom" -> null.also { listState.animateScrollToItem(sheet.rows.size) }
                "scroll_down", "scroll_up" -> {
                    val screen = listState.layoutInfo.viewportSize.height
                    listState.animateScrollBy((if (operation == "scroll_down") 0.8f else -0.8f) * screen)
                    null
                }
                else -> "unsupported_here"
            }
        }

        Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.width(gridWidth),
            ) {
                // The scroll position is passed as a reader, not a value: reading it here would
                // recompose every row on every pixel of sideways scrolling.
                stickyHeader(key = "columns") { ColumnHeader(window, widths, starts, horizontal::value, selected?.second) }
                items(count = sheet.rows.size, key = { i -> sheet.rows[i].page.toLong() shl 32 or sheet.rows[i].block.toLong() }) { i ->
                    val row = sheet.rows[i]
                    val highlights = search?.results?.highlights(row.page, row.block..row.block, target)
                    GridRow(
                        row = row,
                        index = i,
                        workbook = workbook,
                        sheet = sheet,
                        editState = editState,
                        merges = sheet.mergesAt(row.row.row),
                        window = window,
                        widths = widths,
                        starts = starts,
                        scroll = horizontal::value,
                        highlights = highlights,
                        selectedColumn = selected?.takeIf { it.first == i }?.second,
                        onSelect = { column -> onSelect(i to column) },
                        onEditCell = onEditCell,
                    )
                }
                if (isLastSheet) item(key = "footer") { DocumentFooter(state, onRetry) }
            }
        }
    }
}

/** Columns join and leave the composed window this many at a time. */
private const val COLUMN_STEP = 8

/** Keeps a composable at the left edge of the viewport while the grid scrolls sideways; [scroll] is read while placing it. */
private fun Modifier.frozenLeft(scroll: () -> Int): Modifier = offset { IntOffset(scroll(), 0) }.zIndex(1f)

/**
 * A cell's grid lines: its right and bottom edges only, as neighbours draw the
 * others, and one hairline each instead of a four-sided border. A screenful of
 * cells otherwise sends hundreds of extra drawing commands every frame.
 */
private fun Modifier.cellLines(): Modifier = drawBehind {
    val w = 1.dp.toPx().coerceAtMost(2f)
    drawRect(GridLine, Offset(size.width - w, 0f), Size(w, size.height))
    drawRect(GridLine, Offset(0f, size.height - w), Size(size.width, w))
}

/** Paints [color] unless it's the white the grid already has. */
private fun Modifier.fill(color: Color): Modifier = if (color == Color.White) this else background(color)

@Composable
private fun ColumnHeader(window: IntRange, widths: FloatArray, starts: FloatArray, scroll: () -> Int, selectedColumn: Int?) {
    Row(Modifier.height(28.dp).background(HeaderBackground)) {
        Box(Modifier.frozenLeft(scroll).width(GutterWidth).height(28.dp).background(HeaderBackground).cellLines())
        Spacer(Modifier.width(starts[window.first].dp))
        for (column in window) {
            val active = column == selectedColumn
            Box(
                Modifier
                    .width(widths[column].dp)
                    .height(28.dp)
                    .background(if (active) SelectedFill else HeaderBackground)
                    .cellLines(),
                contentAlignment = Alignment.Center,
            ) {
                Text(columnName(column), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (active) SelectedOutline else HeaderText)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridRow(
    row: SheetRow,
    index: Int,
    workbook: Workbook,
    sheet: Sheet,
    editState: SheetEditState?,
    merges: List<ContentBlock.Merge>,
    window: IntRange,
    widths: FloatArray,
    starts: FloatArray,
    scroll: () -> Int,
    highlights: BlockHighlights?,
    selectedColumn: Int?,
    onSelect: (Int) -> Unit,
    onEditCell: ((Int, Int, String) -> Unit)?,
) {
    val cells = row.row.cells
    val rowNumber = row.row.row
    // Without looks of its own, a sheet gets a guessed header and title row.
    val styled = workbook.looks.isNotEmpty()
    // A first row of labels over the data reads as its header, as in most sheets.
    val header = row.row.header ||
        (!styled && index == 0 && cells.size >= 2 && cells.all { it.isNotEmpty() && !looksNumeric(it) })
    // A lone value in the first cell of a row reads as a title, as in report sheets.
    val title = !styled && !header && index < 3 && cells.size >= 1 && cells.drop(1).all { it.isEmpty() } && cells[0].length > 12
    val paper = LocalPaperColors.current
    val base = TextStyle(fontSize = CELL_FONT_SP.sp, color = paper.text, fontWeight = if (header || title) FontWeight.SemiBold else FontWeight.Normal)
    Row(Modifier.height(RowHeight)) {
        Box(
            Modifier
                .frozenLeft(scroll)
                .width(GutterWidth)
                .height(RowHeight)
                .background(if (selectedColumn != null) SelectedGutter else HeaderBackground)
                .cellLines()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(rowNumber?.toString().orEmpty(), fontSize = 12.sp, color = if (selectedColumn != null) SelectedOutline else HeaderText, maxLines = 1)
        }
        // The window starts two columns early, so text spilling in from the left is drawn whole,
        // and earlier still if a merged range reaches into it.
        val first = merges.filter { window.first in it.firstColumn..it.lastColumn }.minOfOrNull { it.firstColumn } ?: window.first
        Spacer(Modifier.width(starts[first.coerceIn(0, widths.lastIndex)].dp))
        var column = first
        while (column <= window.last && column < widths.size) {
            // Keyed by column: when columns scroll in or out, the cells that stay keep their
            // place in the composition, so their text isn't laid out again.
            column += key(column) {
            val merge = merges.firstOrNull { column in it.firstColumn..it.lastColumn }
            val look = workbook.lookOf(row.row, column)
            if (merge != null && merge.firstRow != rowNumber) {
                // Covered by a range merged from a row above: one blank cell.
                val span = (merge.lastColumn - column + 1).coerceIn(1, widths.size - column)
                Box(
                    Modifier
                        .width((column until column + span).sumOf { widths[it].toDouble() }.toFloat().dp)
                        .height(RowHeight)
                        .fill(look?.fill?.let(::Color) ?: Color.White)
                        .cellLines(),
                )
                span
            } else {
            val originalText = cells.getOrNull(column).orEmpty()
            val text = editState?.editedValue(index, column, originalText) ?: originalText
            val edited = editState?.edits?.containsKey(index to column) == true
            val sheetRuns = sheet.runsAt(rowNumber, column)
            val numeric = looksNumeric(text)
            val align = look?.align ?: CellAlign.Auto
            val span = when {
                merge != null -> merge.lastColumn - column + 1
                // Text that wraps or centres stays in its cell; the rest spills into empty neighbours.
                look?.wrap == true || align == CellAlign.Center || align == CellAlign.Right -> 1
                else -> overflowSpan(cells, column, text.length * CHAR_DP + CELL_PADDING_DP, { widths.getOrElse(it) { MIN_CELL_DP } })
            }.coerceIn(1, widths.size - column)
            val width = (column until column + span).sumOf { widths[it].toDouble() }.toFloat()
            val hits = highlights?.inPart(row.block, column).orEmpty()
            val current = highlights?.currentIn(row.block, column)
            val chosen = selectedColumn == column
            val clickedColumn = column
            val (boxAlign, textAlign) = when (align) {
                CellAlign.Left -> Alignment.CenterStart to TextAlign.Start
                CellAlign.Center -> Alignment.Center to TextAlign.Center
                CellAlign.Right -> Alignment.CenterEnd to TextAlign.End
                CellAlign.Auto -> if (numeric) Alignment.CenterEnd to TextAlign.End else Alignment.CenterStart to TextAlign.Start
            }
            Box(
                Modifier
                    .width(width.dp)
                    .height(RowHeight)
                    .fill(
                        when {
                            current != null -> EzColors.SearchCurrent.copy(alpha = 0.45f)
                            hits.isNotEmpty() -> EzColors.SearchMatch.copy(alpha = 0.45f)
                            edited -> EditedFill
                            look?.fill != null -> Color(look.fill)
                            header -> Color(0xFFFBE7EE)
                            title -> TitleRowBackground
                            else -> Color.White
                        },
                    )
                    .then(if (chosen) Modifier.border(2.dp, SelectedOutline) else Modifier.cellLines())
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = "Show cell",
                        onClick = rememberHapticActivation { onSelect(clickedColumn) },
                        onDoubleClick = onEditCell?.let { edit -> { edit(index, clickedColumn, originalText) } },
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = boxAlign,
            ) {
                if (text.isNotEmpty()) {
                    Text(
                        text = sheetRuns.let { runs ->
                            if (hits.isEmpty() && runs.isEmpty()) AnnotatedString(text) else richText(text, runs, hits, current)
                        },
                        style = base.withLook(look).copy(
                            textAlign = textAlign,
                            color = when {
                                look?.color != null -> Color(look.color)
                                !styled && text.startsWith("-") && numeric -> Color(0xFFB91C1C)
                                else -> base.color
                            },
                        ),
                        maxLines = if (look?.wrap == true) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            span
            }
            }
        }
    }
}

/** Spreadsheets size text in points, 11 being usual; the grid keeps rows one height, so big text is capped. */
private const val CELL_FONT_SP = 13f
private const val USUAL_POINTS = 11f

private fun TextStyle.withLook(look: CellLook?): TextStyle {
    if (look == null) return this
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { look.underline },
        TextDecoration.LineThrough.takeIf { look.strikethrough },
    )
    return copy(
        fontWeight = if (look.bold) FontWeight.Bold else fontWeight,
        fontStyle = if (look.italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        fontSize = look.size?.let { (CELL_FONT_SP * it / USUAL_POINTS).coerceIn(9f, 20f).sp } ?: fontSize,
        lineHeight = if (look.wrap) 14.sp else lineHeight,
    )
}

@Composable
private fun SheetTabs(workbook: Workbook, selected: Int, loading: Boolean, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(selected) { list.animateScrollToItem(selected) }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().background(EzColors.BackgroundSecondary).border(0.5.dp, EzColors.Hairline),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(workbook.sheets) { index, sheet ->
            val active = index == selected
            Text(
                sheet.title ?: "Sheet ${index + 1}",
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) EzColors.Accent else EzColors.Text,
                maxLines = 1,
                modifier = Modifier
                    .then(if (active) Modifier.clip(EzShapes.Pill).background(EzColors.Selected) else Modifier.glass(EzShapes.Pill, 0.6f))
                    .semantics { this.selected = active }
                    .clickable(role = Role.Tab, onClick = rememberHapticChoice(active) { onSelect(index) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        if (loading) {
            item { Text("Loading…", fontSize = 12.sp, color = EzColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp)) }
        }
    }
}

@Composable
private fun CellEditOverlay(
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(EzColors.BackgroundSecondary)
            .border(0.5.dp, EzColors.Accent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 14.sp, color = EzColors.Text, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(EzColors.Accent),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        )
        Spacer(Modifier.width(8.dp))
        Text("Done", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EzColors.Accent,
            modifier = Modifier.clickable { onDone(text) }.padding(8.dp))
        Text("Cancel", fontSize = 13.sp, color = EzColors.TextSecondary,
            modifier = Modifier.clickable { onCancel() }.padding(8.dp))
    }
}

private fun serializeCsv(sheet: Sheet, editState: SheetEditState): String {
    val sb = StringBuilder()
    sheet.rows.forEachIndexed { rowIndex, sheetRow ->
        sheetRow.row.cells.forEachIndexed { colIndex, cell ->
            if (colIndex > 0) sb.append(',')
            val value = editState.editedValue(rowIndex, colIndex, cell)
            if (value.contains(',') || value.contains('"') || value.contains('\n')) {
                sb.append('"').append(value.replace("\"", "\"\"")).append('"')
            } else {
                sb.append(value)
            }
        }
        sb.append("\r\n")
    }
    return sb.toString()
}
