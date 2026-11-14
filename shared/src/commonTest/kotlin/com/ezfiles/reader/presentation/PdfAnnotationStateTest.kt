package com.ezfiles.reader.presentation

import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfBox
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.platform.PdfTextSelection
import com.ezfiles.reader.presentation.viewers.PdfAnnotationHistory
import com.ezfiles.reader.presentation.viewers.PdfSelection
import com.ezfiles.reader.presentation.viewers.PdfSelectionHandle
import com.ezfiles.reader.presentation.viewers.annotatedFileName
import com.ezfiles.reader.presentation.viewers.markerAnnotation
import com.ezfiles.reader.presentation.viewers.noteAt
import com.ezfiles.reader.presentation.viewers.placeSignature
import com.ezfiles.reader.presentation.viewers.rangeAfterDrag
import com.ezfiles.reader.presentation.viewers.saveWarning
import com.ezfiles.reader.presentation.viewers.simplifyStroke
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun note(page: Int, x: Float = 0.5f, y: Float = 0.5f, text: String = "n") = PdfAnnotation.Note(page, 0, x, y, text)

class PdfAnnotationHistoryTest {
    @Test
    fun starts_empty_and_clean() {
        val history = PdfAnnotationHistory.Empty
        assertTrue(history.items.isEmpty())
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.isDirty)
        assertSame(history, history.undo())
        assertSame(history, history.redo())
    }

    @Test
    fun undo_and_redo_walk_the_edits() {
        val two = PdfAnnotationHistory.Empty.add(note(0)).add(note(1))
        assertEquals(2, two.items.size)
        val one = two.undo()
        assertEquals(listOf(note(0)), one.items)
        assertTrue(one.canRedo)
        assertEquals(two.items, one.redo().items)
        assertTrue(one.undo().items.isEmpty())
        assertFalse(one.undo().canUndo)
    }

    @Test
    fun a_new_edit_discards_redo() {
        val history = PdfAnnotationHistory.Empty.add(note(0)).undo().add(note(1))
        assertFalse(history.canRedo)
        assertEquals(listOf(note(1)), history.items)
    }

    @Test
    fun groups_by_page() {
        val history = PdfAnnotationHistory.Empty.add(note(2)).add(note(3)).add(note(2, x = 0.1f))
        assertEquals(2, history.onPage(2).size)
        assertEquals(1, history.onPage(3).size)
        assertTrue(history.onPage(0).isEmpty())
    }

    @Test
    fun dirty_follows_the_saved_state_through_undo() {
        val saved = PdfAnnotationHistory.Empty.add(note(0)).markSaved()
        assertFalse(saved.isDirty)
        assertEquals(1, saved.items.size)
        val more = saved.add(note(1))
        assertTrue(more.isDirty)
        assertFalse(more.undo().isDirty)
        assertTrue(more.undo().undo().isDirty, "undoing past a save differs from the saved copy")
    }

    @Test
    fun history_depth_is_bounded_but_items_are_not_dropped() {
        var history = PdfAnnotationHistory.Empty
        repeat(500) { history = history.add(note(it)) }
        assertEquals(500, history.items.size)
        var undone = 0
        while (history.canUndo) {
            history = history.undo()
            undone++
        }
        assertEquals(200, undone)
        assertEquals(300, history.items.size)
    }
}

class PdfAnnotationGeometryTest {
    private fun assertNear(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) <= 1e-4f, "expected $expected, got $actual")

    @Test
    fun simplify_drops_near_points_and_keeps_the_ends() {
        val points = List(100) { PdfPoint(it * 0.00001f, 0f) } + PdfPoint(0.5f, 0.5f)
        val thin = simplifyStroke(points)
        assertEquals(points.first(), thin.first())
        assertEquals(points.last(), thin.last())
        assertTrue(thin.size < 5, "${thin.size}")
    }

    @Test
    fun simplify_leaves_short_strokes_and_bounds_long_ones() {
        val dot = listOf(PdfPoint(0.1f, 0.1f))
        assertEquals(dot, simplifyStroke(dot))
        val long = List(50_000) { PdfPoint(it / 50_000f, it / 50_000f) }
        assertTrue(simplifyStroke(long, maxPoints = 2000).size <= 2000)
    }

    @Test
    fun note_hit_testing_finds_the_topmost_within_a_finger() {
        val low = note(0, 0.5f, 0.5f, "low")
        val high = note(0, 0.51f, 0.5f, "high")
        assertEquals("high", noteAt(listOf(low, high), PdfPoint(0.5f, 0.5f), 0.7f)?.text)
        assertNull(noteAt(listOf(low), PdfPoint(0.6f, 0.5f), 0.7f))
        assertNull(noteAt(listOf(low), PdfPoint(0.5f, 0.6f), 0.7f))
    }

    @Test
    fun signature_keeps_its_shape_and_stays_on_the_page() {
        val pad = listOf(listOf(PdfPoint(0f, 0f), PdfPoint(1f, 1f)))
        // A page twice as tall as wide, a pad three times as wide as tall.
        val placed = placeSignature(pad, PdfPoint(0.5f, 0.5f), pageAspect = 0.5f, padAspect = 3f, widthFraction = 0.3f)[0]
        assertNear(0.3f, placed[1].x - placed[0].x)
        // On the page: 0.3 of 1 wide, so 0.1 tall, which is 0.05 of a height of 2.
        assertNear(0.05f, placed[1].y - placed[0].y)
        assertNear(0.5f, (placed[0].x + placed[1].x) / 2)

        val corner = placeSignature(pad, PdfPoint(0.99f, 0.99f), pageAspect = 0.5f, padAspect = 3f)[0]
        assertNear(1f, corner[1].x)
        assertNear(1f, corner[1].y)
    }
}

class PdfSelectionTest {
    private val line1 = PdfBox(0.1f, 0.10f, 0.9f, 0.12f)
    private val line2 = PdfBox(0.1f, 0.13f, 0.4f, 0.15f)
    private val selection = PdfSelection(0, "text", listOf(line1, line2))

    @Test
    fun blank_or_boxless_results_are_no_selection() {
        assertNull(PdfSelection.of(0, null))
        assertNull(PdfSelection.of(0, PdfTextSelection("  ", listOf(line1))))
        assertNull(PdfSelection.of(0, PdfTextSelection("x", emptyList())))
        assertNotNull(PdfSelection.of(0, PdfTextSelection("x", listOf(line1))))
    }

    @Test
    fun anchors_sit_at_the_outer_ends() {
        assertEquals(PdfPoint(0.1f, (0.10f + 0.12f) / 2), selection.startAnchor)
        assertEquals(PdfPoint(0.4f, (0.13f + 0.15f) / 2), selection.endAnchor)
    }

    @Test
    fun dragging_the_end_extends_from_the_start() {
        val to = PdfPoint(0.8f, 0.5f)
        assertEquals(selection.startAnchor to to, selection.rangeAfterDrag(PdfSelectionHandle.End, to))
    }

    @Test
    fun dragging_the_start_extends_back_from_the_end() {
        val to = PdfPoint(0.2f, 0.01f)
        assertEquals(to to selection.endAnchor, selection.rangeAfterDrag(PdfSelectionHandle.Start, to))
    }

    @Test
    fun a_handle_dragged_past_the_other_stays_in_reading_order() {
        val below = PdfPoint(0.3f, 0.5f)
        assertEquals(selection.endAnchor to below, selection.rangeAfterDrag(PdfSelectionHandle.Start, below))
        val above = PdfPoint(0.3f, 0.01f)
        assertEquals(above to selection.startAnchor, selection.rangeAfterDrag(PdfSelectionHandle.End, above))
    }

    @Test
    fun on_one_line_reading_order_is_left_to_right() {
        val single = PdfSelection(0, "t", listOf(PdfBox(0.4f, 0.1f, 0.6f, 0.12f)))
        val left = PdfPoint(0.2f, 0.11f)
        assertEquals(left to single.startAnchor, single.rangeAfterDrag(PdfSelectionHandle.End, left))
    }
}

class PdfSaveHelpersTest {
    private val stroke = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.5f, 0.1f))

    @Test
    fun a_marker_over_text_becomes_a_highlight() {
        val boxes = listOf(PdfBox(0f, 0f, 1f, 1f))
        val made = markerAnnotation(3, 1, 0.02f, stroke, PdfTextSelection("word", boxes))
        assertEquals(PdfAnnotation.Highlight(3, 1, boxes), made)
    }

    @Test
    fun a_marker_over_nothing_stays_a_drawn_stroke() {
        for (selected in listOf(null, PdfTextSelection(" ", listOf(PdfBox(0f, 0f, 1f, 1f))), PdfTextSelection("x", emptyList()))) {
            val made = assertIs<PdfAnnotation.Ink>(markerAnnotation(3, 1, 0.02f, stroke, selected))
            assertEquals(PdfInkStyle.Marker, made.style)
            assertEquals(listOf(stroke), made.strokes)
        }
    }

    @Test
    fun warns_only_when_needed() {
        assertNull(saveWarning(signed = false, archival = false))
        assertTrue(saveWarning(signed = true, archival = false)!!.contains("signature"))
        assertTrue(saveWarning(signed = false, archival = true)!!.contains("PDF/A"))
        assertTrue(saveWarning(signed = true, archival = true)!!.let { "signature" in it && "PDF/A" in it })
    }

    @Test
    fun names_the_copy_after_the_original() {
        assertEquals("report (annotated).pdf", annotatedFileName("report.pdf"))
        assertEquals("REPORT (annotated).pdf", annotatedFileName("REPORT.PDF"))
        assertEquals("notes (annotated).pdf", annotatedFileName("notes"))
        assertEquals("document (annotated).pdf", annotatedFileName(".pdf"))
    }
}
