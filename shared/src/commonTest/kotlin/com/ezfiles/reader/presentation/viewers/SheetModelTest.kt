package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.CellLook
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.TextSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SheetModelTest {
    private fun row(n: Int, vararg cells: String) = ContentBlock.TableRow(n, cells.toList())

    @Test
    fun sections_split_a_workbook_into_sheets_as_pages_arrive() {
        val workbook = Workbook()
        workbook.update(listOf(listOf(ContentBlock.Section("Sales"), row(1, "a"), row(2, "b"))))
        workbook.update(listOf(listOf(ContentBlock.Section("Sales"), row(1, "a"), row(2, "b")), listOf(row(3, "c"), ContentBlock.Section("Notes"), row(1, "x"))))
        assertEquals(listOf("Sales", "Notes"), workbook.sheets.map { it.title })
        assertEquals(listOf(1, 2, 3), workbook.sheets[0].rows.map { it.row.row })
        assertEquals(1, workbook.sheetOf(page = 1, block = 2))
        assertEquals(0, workbook.sheetOf(page = 1, block = 0))
    }

    @Test
    fun rows_without_a_section_form_one_untitled_sheet() {
        val workbook = Workbook()
        workbook.update(listOf(listOf(row(1, "id", "name"), row(2, "1", "Ann"))))
        assertEquals(1, workbook.sheets.size)
        assertNull(workbook.sheets[0].title)
        assertEquals(2, workbook.sheets[0].columns)
    }

    @Test
    fun a_row_is_found_by_its_place_in_the_pages() {
        val workbook = Workbook()
        workbook.update(listOf(listOf(ContentBlock.Section("S"), row(1, "a"), row(2, "b")), listOf(row(3, "c"))))
        val sheet = workbook.sheets[0]
        assertEquals(2, sheet.indexOf(page = 1, block = 0))
        assertEquals(0, sheet.indexOf(page = 0, block = 1))
    }

    @Test
    fun spilling_titles_do_not_widen_their_column_but_values_do() {
        val workbook = Workbook()
        workbook.update(listOf(listOf(row(1, "FY2026 Regional Sales Summary"), row(2, "North America", "616,800"))))
        val chars = workbook.sheets[0].chars
        assertEquals("North America".length, chars[0])
        assertEquals("616,800".length, chars[1])
    }

    @Test
    fun looks_and_merges_reach_their_cells() {
        val workbook = Workbook()
        val bold = CellLook(bold = true, fill = 0xFFFFCC00.toInt())
        val title = ContentBlock.TableRow(1, listOf("Title"), styles = listOf(1, 1, 1))
        workbook.update(listOf(listOf(ContentBlock.CellStyle(1, bold), ContentBlock.Section("S"), title, row(2, "a", "b"))))
        // Merges follow the sheet's rows, in a later page.
        workbook.update(
            listOf(
                listOf(ContentBlock.CellStyle(1, bold), ContentBlock.Section("S"), title, row(2, "a", "b")),
                listOf(ContentBlock.Merge(1, 0, 1, 2), ContentBlock.Merge(1, 3, 5000, 3)),
            ),
        )
        val sheet = workbook.sheets.single()
        assertEquals(bold, workbook.lookOf(title, 2))
        assertNull(workbook.lookOf(title, 3))
        assertNull(workbook.lookOf(sheet.rows[1].row, 0))
        assertEquals(2, sheet.mergesAt(1).size)
        assertEquals(listOf(ContentBlock.Merge(1, 3, 5000, 3)), sheet.mergesAt(2))
        assertTrue(sheet.mergesAt(Sheet.MAX_MERGED_ROWS + 1).isEmpty(), "very tall merges only cover their first rows")
        assertTrue(sheet.mergesAt(null).isEmpty())
        assertTrue(sheet.mergesAt(1) !== sheet.mergesAt(1), "a fresh list, so rows recompose when merges arrive")
    }

    @Test
    fun styled_runs_reach_their_cell() {
        val workbook = Workbook()
        val red = TextSpan(10, 16, bold = true, color = 0xFFC00000.toInt())
        workbook.update(
            listOf(listOf(ContentBlock.Section("S"), row(2, "Plain", "Quarterly review"), ContentBlock.CellRuns(2, 1, listOf(red)))),
        )
        val sheet = workbook.sheets.single()
        assertEquals(listOf(red), sheet.runsAt(2, 1))
        assertTrue(sheet.runsAt(2, 0).isEmpty())
        assertTrue(sheet.runsAt(null, 1).isEmpty())
        assertEquals(1, sheet.rows.size, "runs are not rows")
    }

    @Test
    fun numbers_are_recognised_in_their_usual_formats() {
        for (cell in listOf("42", "-1,234.57", "$616,800", "12.5%", "1.23E-04", "(1,200)", "€ 9.99")) {
            assertTrue(looksNumeric(cell), cell)
        }
        for (cell in listOf("", "SO-10001", "2026-08-17", "Q1", "TRUE", "1,2,3", "$")) {
            assertFalse(looksNumeric(cell), cell)
        }
    }

    @Test
    fun columns_are_lettered_like_a_spreadsheet() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map(::columnName))
    }

    @Test
    fun text_spills_over_empty_cells_only() {
        val widths = { _: Int -> 10f }
        assertEquals(3, overflowSpan(listOf("long title", "", ""), 0, needed = 25f, widthOf = widths))
        assertEquals(1, overflowSpan(listOf("long title", "x"), 0, needed = 25f, widthOf = widths))
        assertEquals(1, overflowSpan(listOf("123456789", ""), 0, needed = 25f, widthOf = widths))
        assertEquals(1, overflowSpan(listOf("short"), 0, needed = 5f, widthOf = widths))
        // Past the last cell everything is empty, within the limit.
        assertEquals(4, overflowSpan(listOf("x"), 0, needed = 1000f, widthOf = widths, maxSpan = 4))
    }
}
