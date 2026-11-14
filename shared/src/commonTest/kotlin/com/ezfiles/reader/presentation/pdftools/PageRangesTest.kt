package com.ezfiles.reader.presentation.pdftools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PageRangesTest {
    private fun pages(input: String, count: Int = 10) = assertIs<PageRangeResult.Pages>(parsePageRanges(input, count)).pages

    private fun error(input: String, count: Int = 10) = assertIs<PageRangeResult.Invalid>(parsePageRanges(input, count)).message

    @Test
    fun reads_pages_and_ranges_as_zero_based_in_the_order_typed() {
        assertEquals(listOf(0, 1, 2, 6), pages("1-3, 7"))
        assertEquals(listOf(6, 0, 1), pages("7,1-2"))
        assertEquals(listOf(4), pages("5"))
        assertEquals(listOf(0, 1, 2), pages("1-3"))
    }

    @Test
    fun is_forgiving_about_spaces_dashes_and_separators() {
        assertEquals(listOf(0, 1, 2, 6), pages("  1 - 3 ;  7 , "))
        assertEquals(listOf(0, 1, 2), pages("1–3"))
        assertEquals(listOf(0, 2), pages("1 3"))
        assertEquals(listOf(2), pages("3-3"))
    }

    @Test
    fun a_page_named_twice_goes_in_once() {
        assertEquals(listOf(0, 1, 2), pages("1-3, 2, 1"))
    }

    @Test
    fun explains_what_is_wrong() {
        assertTrue("Enter pages" in error(""))
        assertTrue("Enter pages" in error(" , "))
        assertTrue("abc" in error("1, abc"))
        assertTrue("1-2-3" in error("1-2-3"))
        assertTrue("3-" in error("3-"))
        assertTrue("start at 1" in error("0"))
        assertTrue("start at 1" in error("0-4"))
        assertTrue("past the end" in error("11"))
        assertTrue("has 10 pages" in error("2-11"))
        assertTrue("has 1 page." in error("2", count = 1))
        assertTrue("Write it as 2-5" in error("5-2"))
    }

    @Test
    fun enormous_numbers_are_past_the_end_and_cost_nothing() {
        assertTrue("past the end" in error("99999999999999"))
        assertTrue("past the end" in error("1-2147483647"))
    }

    @Test
    fun chunks_cover_every_page_once() {
        assertEquals(listOf(0..2, 3..5, 6..6), pageChunks(7, 3))
        assertEquals(listOf(0..0, 1..1), pageChunks(2, 1))
        assertEquals(listOf(0..1), pageChunks(2, 5))
        assertEquals(listOf(0..1), pageChunks(2, Int.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { pageChunks(2, 0) }
    }

    @Test
    fun split_names_sort_in_order() {
        assertEquals(
            listOf("report (part 1).pdf", "report (part 2).pdf"),
            splitOutputNames("report.pdf", 4, 2),
        )
        val perPage = splitOutputNames("report.pdf", 12, 1)
        assertEquals("report (page 01).pdf", perPage.first())
        assertEquals("report (page 12).pdf", perPage.last())
    }
}
