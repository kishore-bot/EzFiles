package com.ezfiles.reader.presentation.viewers

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfCurrentPageTest {
    @Test
    fun the_page_under_the_middle_counts_not_the_one_at_the_top() {
        // A sliver of page 4 at the top, page 5 filling the screen.
        val rows = listOf(VisibleRow(3, -1100, 120), VisibleRow(4, 140, 1300), VisibleRow(5, 1320, 2500))
        assertEquals(4, currentRow(rows, viewportHeight = 1600, atEnd = false, fallback = 3))
    }

    @Test
    fun at_the_end_the_last_page_counts() {
        // Scrolled to the bottom: page 9 at the top, page 11 whole below.
        val rows = listOf(VisibleRow(8, -900, 300), VisibleRow(9, 320, 900), VisibleRow(10, 920, 1500))
        assertEquals(10, currentRow(rows, viewportHeight = 1600, atEnd = true, fallback = 8))
    }

    @Test
    fun a_gap_at_the_middle_picks_the_page_below() {
        val rows = listOf(VisibleRow(0, 0, 790), VisibleRow(1, 810, 1600))
        assertEquals(1, currentRow(rows, viewportHeight = 1600, atEnd = false, fallback = 0))
    }

    @Test
    fun nothing_laid_out_keeps_the_fallback() {
        assertEquals(2, currentRow(emptyList(), viewportHeight = 1600, atEnd = false, fallback = 2))
    }
}
