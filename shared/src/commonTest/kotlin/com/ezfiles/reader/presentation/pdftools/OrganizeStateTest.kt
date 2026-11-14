package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.PdfPageEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrganizeStateTest {
    private fun OrganizeState.order() = pages.map { it.sourcePage }

    @Test
    fun starts_as_the_document_with_nothing_to_save() {
        val state = OrganizeState(3)
        assertEquals(listOf(0, 1, 2), state.order())
        assertFalse(state.hasChanges)
        assertFalse(state.canUndo)
    }

    @Test
    fun moves_a_page_and_stops_at_the_ends() {
        val state = OrganizeState(3)
        assertEquals(listOf(1, 0, 2), state.moved(0, 1).order())
        assertEquals(listOf(0, 2, 1), state.moved(2, -1).order())
        assertSame(state, state.moved(0, -1))
        assertSame(state, state.moved(2, 1))
    }

    @Test
    fun moves_a_selection_together() {
        val state = OrganizeState(5).toggled(1).toggled(2)
        assertEquals(listOf(1, 2, 0, 3, 4), state.selectionMoved(later = false).order())
        assertEquals(listOf(0, 3, 1, 2, 4), state.selectionMoved(later = true).order())
        // A block already at the end stays, and the ones behind it close up.
        val atStart = OrganizeState(4).toggled(0).toggled(2)
        assertEquals(listOf(0, 2, 1, 3), atStart.selectionMoved(later = false).order())
        val whole = OrganizeState(2).selectedAll()
        assertSame(whole, whole.selectionMoved(later = true))
    }

    @Test
    fun rotates_clockwise_a_quarter_at_a_time_and_back_to_unchanged() {
        var state = OrganizeState(2).rotated(1)
        assertEquals(90, state.pages[1].rotationDegrees)
        assertEquals(0, state.pages[0].rotationDegrees)
        assertTrue(state.hasChanges)
        repeat(3) { state = state.rotated(1) }
        assertEquals(0, state.pages[1].rotationDegrees)
        assertFalse(state.hasChanges)
    }

    @Test
    fun rotates_only_the_selection() {
        val state = OrganizeState(3).toggled(0).toggled(2).selectionRotated()
        assertEquals(listOf(90, 0, 90), state.pages.map { it.rotationDegrees })
    }

    @Test
    fun deletes_but_never_the_last_page() {
        val two = OrganizeState(2).deleted(0)
        assertEquals(listOf(1), two.order())
        assertFalse(two.canDelete)
        assertSame(two, two.deleted(1))
    }

    @Test
    fun deleting_a_selection_of_everything_is_refused() {
        val all = OrganizeState(3).selectedAll()
        assertFalse(all.canDeleteSelection)
        assertSame(all, all.selectionDeleted())

        val some = OrganizeState(3).toggled(0).toggled(1)
        val left = some.selectionDeleted()
        assertEquals(listOf(2), left.order())
        assertTrue(left.selected.isEmpty())
    }

    @Test
    fun deleted_pages_leave_the_selection() {
        val state = OrganizeState(3).toggled(0).toggled(1).deleted(0)
        assertEquals(setOf(1), state.selected)
        assertTrue(state.toggled(1).toggled(2).selected == setOf(2))
    }

    @Test
    fun select_all_and_clear() {
        val all = OrganizeState(3).selectedAll()
        assertTrue(all.allSelected)
        assertEquals(emptySet(), all.selectionCleared().selected)
    }

    @Test
    fun undo_steps_back_and_is_bounded() {
        val state = OrganizeState(3).moved(0, 1).deleted(2).undone()
        assertEquals(listOf(1, 0, 2), state.order())
        assertEquals(listOf(0, 1, 2), state.undone().order())
        assertFalse(state.undone().canUndo)

        var long = OrganizeState(2)
        repeat(50) { long = long.rotated(0) }
        var steps = 0
        while (long.canUndo) {
            long = long.undone()
            steps++
        }
        assertEquals(20, steps)
    }

    @Test
    fun edits_describe_the_result_in_order() {
        val state = OrganizeState(3).moved(2, -2).rotated(2).deleted(1)
        assertEquals(listOf(PdfPageEdit(2, 90), PdfPageEdit(0, 0)), state.edits)
    }

    @Test
    fun a_long_document_stays_cheap() {
        val state = OrganizeState(100_000).selectedAll().selectionRotated()
        assertEquals(100_000, state.edits.size)
    }
}
