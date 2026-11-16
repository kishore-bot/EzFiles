package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.presentation.viewers.parseCellAddress
import com.ezfiles.reader.presentation.viewers.spokenNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCommandsTest {
    private val pdf = setOf("next_page", "previous_page", "go_to_page", "zoom_in", "zoom_out", "scroll_down", "scroll_up", "scroll_top", "scroll_bottom")
    private val photos = setOf("next_photo", "previous_photo", "zoom_in", "zoom_out", "rotate_photo")
    private val sheet = setOf("select_cell", "next_sheet", "previous_sheet", "scroll_down", "scroll_up")

    @Test
    fun pageCommands() {
        assertEquals(QuickCommand("next_page"), QuickCommands.parse("Next page.", pdf))
        assertEquals(QuickCommand("next_page"), QuickCommands.parse("hey Fily, go to the next page please", pdf))
        assertEquals(QuickCommand("previous_page"), QuickCommands.parse("go back a page", pdf))
        assertEquals(QuickCommand("go_to_page", "5"), QuickCommands.parse("Go to page 5", pdf))
        assertEquals(QuickCommand("go_to_page", "five"), QuickCommands.parse("page five", pdf))
        assertEquals(QuickCommand("go_to_page", "12"), QuickCommands.parse("go to the last page", pdf, pageCount = 12))
        assertEquals(QuickCommand("go_to_page", "1"), QuickCommands.parse("first page", pdf))
        assertEquals(QuickCommand("zoom_in"), QuickCommands.parse("zoom in a little", pdf))
        assertEquals(QuickCommand("scroll_down"), QuickCommands.parse("scroll down", pdf))
        assertEquals(QuickCommand("scroll_top"), QuickCommands.parse("go to the top", pdf))
    }

    @Test
    fun bareNextFollowsWhatIsOnScreen() {
        assertEquals(QuickCommand("next_page"), QuickCommands.parse("next", pdf))
        assertEquals(QuickCommand("next_photo"), QuickCommands.parse("next", photos))
        assertEquals(QuickCommand("previous_photo"), QuickCommands.parse("back", photos))
    }

    @Test
    fun cellsAndSheets() {
        assertEquals(QuickCommand("select_cell", "b 3"), QuickCommands.parse("Select B3", sheet))
        assertEquals(QuickCommand("select_cell", "b 3"), QuickCommands.parse("click on cell b 3", sheet))
        val spoken = QuickCommands.parse("tap cell C three", sheet)
        assertEquals(2 to 3, parseCellAddress(spoken?.value))
        assertEquals(QuickCommand("next_sheet"), QuickCommands.parse("next sheet", sheet))
        assertEquals(1 to 3, parseCellAddress("b3"))
        assertEquals(27 to 10, parseCellAddress("AB 10"))
        assertNull(parseCellAddress("hello"))
    }

    @Test
    fun onlyWhatTheScreenSupportsAndNotQuestions() {
        assertNull(QuickCommands.parse("next page", emptySet()))
        assertNull(QuickCommands.parse("select B3", pdf))
        assertNull(QuickCommands.parse("what does page 5 say about the deadline", pdf))
        assertNull(QuickCommands.parse("find my resume", pdf))
        assertEquals(7, spokenNumber("seven"))
        assertEquals(21, spokenNumber("21st"))
    }

    @Test
    fun repliesReportWhereItLanded() {
        assertEquals("Page 5 of 12.", QuickCommands.reply(QuickCommand("go_to_page", "5"), 5, 12, null, null, null))
        assertEquals("There are only 12 pages.", QuickCommands.reply(QuickCommand("go_to_page", "40"), 3, 12, null, null, "out_of_range"))
        assertEquals("That's the last page.", QuickCommands.reply(QuickCommand("next_page"), 12, 12, null, null, "at_last_page"))
        assertEquals("Selected B3.", QuickCommands.reply(QuickCommand("select_cell", "b 3"), null, null, "Sheet 1", "B3", null))
    }

    @Test
    fun readerControlRefusesWhatTheViewerCannotDo() = runTest {
        val control = ReaderControl()
        assertTrue(control.perform("next_page", null).contains("no_document"))
        var page = 1
        control.attach(object : ReaderTarget {
            override val operations = setOf("next_page")
            override val position get() = ReaderPosition(page = page, pageCount = 3)
            override suspend fun perform(operation: String, value: String?): String? { page++; return null }
        })
        assertEquals(listOf("next_page"), control.operations)
        assertEquals("""{"status":"done","operation":"next_page","page":2,"pageCount":3}""", control.perform("next_page", null))
        assertTrue(control.perform("zoom_in", null).contains("unsupported_here"))
    }
}
