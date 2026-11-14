package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.CellLook
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.TextSpan

/** A spreadsheet row and where it sits in `Ready.pages`, for search and highlights. */
class SheetRow(val page: Int, val block: Int, val row: ContentBlock.TableRow)

/**
 * One sheet of a workbook. Rows only append, as batches arrive. Column widths
 * come from the first [MEASURED_ROWS] rows, in characters, so they settle
 * early and don't jump while the rest of a long sheet loads.
 */
class Sheet(val title: String?, val page: Int, val block: Int) {
    val rows = ArrayList<SheetRow>()
    var columns = 0
        private set

    /** Widest content per column, in characters, capped at [MAX_COLUMN_CHARS]. */
    val chars = ArrayList<Int>()

    internal fun add(row: SheetRow) {
        val cells = row.row.cells
        if (cells.size > columns) columns = cells.size
        if (rows.size < MEASURED_ROWS) {
            while (chars.size < cells.size) chars += 0
            for ((column, cell) in cells.withIndex()) {
                // Text with an empty cell to its right spills into it, as titles do,
                // so it doesn't widen its column; very long text is cut instead.
                val spills = cells.getOrNull(column + 1).isNullOrEmpty() && !looksNumeric(cell)
                val length = when {
                    spills -> cell.length.coerceAtMost(SPILLING_CHARS)
                    cell.length > MAX_COLUMN_CHARS && !looksNumeric(cell) -> MAX_COLUMN_CHARS / 2
                    else -> cell.length
                }
                chars[column] = maxOf(chars[column], length.coerceAtMost(MAX_COLUMN_CHARS))
            }
        }
        rows += row
    }

    /** Merged ranges by the rows they cover. */
    private val merges = HashMap<Int, MutableList<ContentBlock.Merge>>()

    internal fun addMerge(merge: ContentBlock.Merge) {
        // A range down very many rows only shows on its first ones.
        val last = minOf(merge.lastRow, merge.firstRow + MAX_MERGED_ROWS - 1)
        for (row in merge.firstRow..last) merges.getOrPut(row) { ArrayList(1) } += merge
    }

    /** Styled runs of cells, by spreadsheet row and then column. */
    private val runs = HashMap<Int, HashMap<Int, List<TextSpan>>>()

    internal fun addRuns(cell: ContentBlock.CellRuns) {
        runs.getOrPut(cell.row) { HashMap(2) }[cell.column] = cell.spans
    }

    /** The styled runs of the cell at spreadsheet row [row], [column]; empty if it has none. */
    fun runsAt(row: Int?, column: Int): List<TextSpan> = row?.let { runs[it]?.get(column) }.orEmpty()

    /** The merged ranges covering spreadsheet row [row], as a new list. */
    fun mergesAt(row: Int?): List<ContentBlock.Merge> = row?.let { merges[it]?.toList() }.orEmpty()

    /** Index of the row for a block of `Ready.pages`, or of the nearest one before it. */
    fun indexOf(page: Int, block: Int): Int {
        var low = 0
        var high = rows.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val r = rows[mid]
            val cmp = compareValues(r.page, page).takeIf { it != 0 } ?: compareValues(r.block, block)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return (low - 1).coerceAtLeast(0)
    }

    companion object {
        const val MEASURED_ROWS = 300
        const val MAX_COLUMN_CHARS = 36

        /** What text that can spill counts toward its column's width. */
        const val SPILLING_CHARS = 12

        const val MAX_MERGED_ROWS = 2000
    }
}

/**
 * The sheets of a workbook as its pages arrive. Each `Section` starts a sheet;
 * rows before any section (CSV) form one untitled sheet. Call [update] with
 * the current pages: only pages not seen yet are read.
 */
class Workbook {
    val sheets = ArrayList<Sheet>()

    /** Cell looks by palette index, as the workbook defines them; empty if it has none. */
    val looks = HashMap<Int, CellLook>()
    private var pagesRead = 0

    /** How a cell of [row] looks, or `null` if plain. */
    fun lookOf(row: ContentBlock.TableRow, column: Int): CellLook? =
        row.styles.getOrNull(column)?.takeIf { it != 0 }?.let(looks::get)

    fun update(pages: List<List<ContentBlock>>) {
        for (page in pagesRead until pages.size) {
            for ((block, content) in pages[page].withIndex()) {
                when (content) {
                    is ContentBlock.Section -> sheets += Sheet(content.title, page, block)
                    is ContentBlock.TableRow -> {
                        if (sheets.isEmpty()) sheets += Sheet(null, page, block)
                        sheets.last().add(SheetRow(page, block, content))
                    }
                    is ContentBlock.CellStyle -> looks[content.index] = content.look
                    is ContentBlock.Merge -> sheets.lastOrNull()?.addMerge(content)
                    is ContentBlock.CellRuns -> sheets.lastOrNull()?.addRuns(content)
                    else -> {}
                }
            }
        }
        pagesRead = pages.size
    }

    /** The sheet holding a block of `Ready.pages`: the last one starting at or before it. */
    fun sheetOf(page: Int, block: Int): Int =
        sheets.indexOfLast { it.page < page || (it.page == page && it.block <= block) }.coerceAtLeast(0)
}

private val NUMERIC = Regex("""^[-+(]?[$€£¥₹]?\s?[-+]?(\d{1,3}(,\d{3})+|\d+)?(\.\d+)?([eE][-+]?\d+)?\s?%?\)?$""")

/** Whether a formatted cell reads as a number, so it aligns right as in a spreadsheet. */
fun looksNumeric(cell: String): Boolean = cell.isNotEmpty() && cell.any(Char::isDigit) && NUMERIC.matches(cell.trim())

/** "A", "B", … "Z", "AA", … for a zero-based column. */
fun columnName(index: Int): String {
    var n = index + 1
    val out = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        out.insert(0, 'A' + rem)
        n = (n - 1) / 26
    }
    return out.toString()
}

/**
 * How many columns text in [column] covers: itself plus the empty cells to its
 * right that it needs, as spreadsheets let text overflow. Numbers never spill.
 * [needed] and [widthOf] are in the same unit.
 */
fun overflowSpan(cells: List<String>, column: Int, needed: Float, widthOf: (Int) -> Float, maxSpan: Int = 8): Int {
    val text = cells.getOrNull(column) ?: return 1
    if (text.isEmpty() || looksNumeric(text)) return 1
    var span = 1
    var width = widthOf(column)
    while (width < needed && span < maxSpan) {
        val next = column + span
        if (cells.getOrNull(next)?.isNotEmpty() == true) break
        width += widthOf(next)
        span++
    }
    return span
}
