package com.ezfiles.reader.presentation.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {
    private var clock = 0L
    private val history = EditHistory()
    private var current = TextSnapshot("", 0, 0)

    private fun set(text: String, caret: Int = text.length, history: EditHistory = this.history) {
        val next = TextSnapshot(text, caret, caret)
        history.record(current, next, clock)
        current = next
    }

    private fun typeSlowly(text: String) {
        clock += 5000
        set(text)
    }

    private fun undo(history: EditHistory = this.history): TextSnapshot? = history.undo(current)?.also { current = it }

    private fun redo(): TextSnapshot? = history.redo(current)?.also { current = it }

    @Test
    fun undo_and_redo_step_through_separate_edits() {
        typeSlowly("a")
        typeSlowly("ab")
        assertEquals("a", undo()?.text)
        assertEquals("", undo()?.text)
        assertNull(undo())
        assertFalse(history.canUndo)
        assertEquals("a", redo()?.text)
        assertEquals("ab", redo()?.text)
        assertNull(redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun a_new_edit_clears_redo() {
        typeSlowly("a")
        undo()
        assertTrue(history.canRedo)
        typeSlowly("b")
        assertFalse(history.canRedo)
    }

    @Test
    fun undo_restores_the_selection_before_the_edit() {
        current = TextSnapshot("hello", 1, 4)
        history.record(current, TextSnapshot("hXo", 2, 2), clock)
        current = TextSnapshot("hXo", 2, 2)
        assertEquals(TextSnapshot("hello", 1, 4), undo())
        assertEquals(TextSnapshot("hXo", 2, 2), redo())
    }

    @Test
    fun fast_typing_is_one_step() {
        for (text in listOf("h", "he", "hel", "hell", "hello")) set(text)
        assertEquals("", undo()?.text)
        assertEquals("hello", redo()?.text)
    }

    @Test
    fun fast_typing_splits_at_words_and_line_breaks() {
        for (text in listOf("a", "ab", "ab ", "ab c", "ab cd", "ab cd\n", "ab cd\ne")) set(text)
        assertEquals("ab cd\n", undo()?.text)
        assertEquals("ab cd", undo()?.text)
        assertEquals("ab ", undo()?.text)
        assertEquals("", undo()?.text)
    }

    @Test
    fun a_pause_starts_a_new_step() {
        set("a")
        clock += 1001
        set("ab")
        assertEquals("a", undo()?.text)
    }

    @Test
    fun moving_the_caret_starts_a_new_step() {
        set("a")
        history.seal()
        set("ab")
        assertEquals("a", undo()?.text)
    }

    @Test
    fun typing_elsewhere_starts_a_new_step() {
        set("abc")
        set("Xabc", caret = 1)
        assertEquals("abc", undo()?.text)
    }

    @Test
    fun backspacing_is_one_step() {
        typeSlowly("hello")
        clock += 5000
        set("hell")
        set("hel")
        set("he")
        assertEquals(TextSnapshot("hello", 5, 5), undo())
    }

    @Test
    fun forward_delete_is_one_step() {
        typeSlowly("hello")
        clock += 5000
        current = TextSnapshot("hello", 1, 1)
        history.seal()
        set("hllo", caret = 1)
        set("hlo", caret = 1)
        assertEquals("hello", undo()?.text)
    }

    @Test
    fun typing_after_deleting_is_a_new_step() {
        typeSlowly("abc")
        clock += 5000
        set("ab")
        set("abX")
        assertEquals("ab", undo()?.text)
        assertEquals("abc", undo()?.text)
    }

    @Test
    fun a_paste_is_its_own_step() {
        set("a")
        set("a" + "x".repeat(100))
        assertEquals("a", undo()?.text)
    }

    @Test
    fun repeated_characters_are_placed_at_the_caret() {
        current = TextSnapshot("aaaa", 2, 2)
        set("aaaaa", caret = 3)
        set("aaaaaa", caret = 4)
        assertEquals(TextSnapshot("aaaa", 2, 2), undo())
    }

    @Test
    fun any_sequence_of_edits_undoes_back_exactly() {
        val texts = listOf("", "x", "xyz", "xyz\n", "abc", "ab😀c", "ab😁c", "😁", "\n\n", "")
        for (text in texts) typeSlowly(text)
        for (expected in texts.dropLast(1).filterIndexed { i, _ -> i > 0 }.reversed()) {
            assertEquals(expected, undo()?.text)
        }
        assertEquals("", undo()?.text)
        for (expected in texts.drop(1)) assertEquals(expected, redo()?.text)
    }

    @Test
    fun the_oldest_edits_are_forgotten_past_the_character_budget() {
        val small = EditHistory(maxChars = 10)
        current = TextSnapshot("", 0, 0)
        clock += 5000
        set("aaaaaa", history = small)
        clock += 5000
        set("aaaaaabbbbbb", history = small)
        assertEquals("aaaaaa", undo(small)?.text)
        assertNull(undo(small))
    }

    @Test
    fun the_oldest_edits_are_forgotten_past_the_edit_count() {
        val small = EditHistory(maxEdits = 2)
        for (text in listOf("a", "ab", "abc")) {
            clock += 5000
            set(text, history = small)
        }
        assertEquals("ab", undo(small)?.text)
        assertEquals("a", undo(small)?.text)
        assertNull(undo(small))
    }

    @Test
    fun an_edit_larger_than_the_budget_leaves_no_history_rather_than_failing() {
        val small = EditHistory(maxChars = 4)
        set("aaaaaaaa", history = small)
        assertFalse(small.canUndo)
    }

    @Test
    fun a_step_that_no_longer_fits_the_text_drops_the_history_and_changes_nothing() {
        typeSlowly("abc")
        val stranger = TextSnapshot("something else", 0, 0)
        assertNull(history.undo(stranger))
        assertFalse(history.canUndo)
    }

    @Test
    fun equal_texts_record_nothing() {
        history.record(TextSnapshot("a", 0, 0), TextSnapshot("a", 1, 1), clock)
        assertFalse(history.canUndo)
    }
}
