package com.ezfiles.reader.presentation.editor

/** The editor's text and selection. Holding one copies nothing. */
data class TextSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * Undo and redo for one text. Stores what each edit changed rather than the
 * whole text, so a long session costs at most [maxChars] characters however
 * large the file is; the oldest edits are forgotten first.
 *
 * Fast typing or deleting in one place forms a single step, split at word
 * boundaries and line breaks, so one undo doesn't remove a whole paragraph.
 */
class EditHistory(
    private val maxEdits: Int = 1000,
    private val maxChars: Int = 2 * MAX_EDITABLE_BYTES,
    private val coalesceWindowMillis: Long = 1000,
) {
    private class Edit(
        var offset: Int,
        var removed: String,
        var inserted: String,
        val before: Pair<Int, Int>,
        var after: Pair<Int, Int>,
    ) {
        val chars: Int get() = removed.length + inserted.length
    }

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var chars = 0
    private var lastEditAt = 0L
    private var sealed = true

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records the change from [before] to [after]. Equal texts record nothing. */
    fun record(before: TextSnapshot, after: TextSnapshot, nowMillis: Long) {
        if (before.text == after.text) return
        val edit = diff(before, after)
        for (dropped in redoStack) chars -= dropped.chars
        redoStack.clear()
        val top = undoStack.lastOrNull()
        val inWindow = !sealed && top != null && nowMillis - lastEditAt in 0..coalesceWindowMillis
        if (top != null && inWindow && merge(top, edit)) {
            top.after = edit.after
        } else {
            undoStack.addLast(edit)
            chars += edit.chars
        }
        lastEditAt = nowMillis
        sealed = false
        while (undoStack.isNotEmpty() && (chars > maxChars || undoStack.size > maxEdits)) {
            chars -= undoStack.removeFirst().chars
        }
    }

    /** The next edit starts a new step, as after the caret moved or a save. */
    fun seal() {
        sealed = true
    }

    /** The state before the last step, or `null` if there is none. */
    fun undo(current: TextSnapshot): TextSnapshot? {
        val edit = undoStack.lastOrNull() ?: return null
        val text = replaceAt(current.text, edit.offset, edit.inserted, edit.removed) ?: return abandon()
        undoStack.removeLast()
        redoStack.addLast(edit)
        sealed = true
        return TextSnapshot(text, edit.before.first, edit.before.second)
    }

    /** The state after the last undone step, or `null` if there is none. */
    fun redo(current: TextSnapshot): TextSnapshot? {
        val edit = redoStack.lastOrNull() ?: return null
        val text = replaceAt(current.text, edit.offset, edit.removed, edit.inserted) ?: return abandon()
        redoStack.removeLast()
        undoStack.addLast(edit)
        sealed = true
        return TextSnapshot(text, edit.after.first, edit.after.second)
    }

    /** `null` if [text] doesn't hold [expected] at [offset], meaning it isn't what was recorded. */
    private fun replaceAt(text: String, offset: Int, expected: String, replacement: String): String? {
        if (!text.startsWith(expected, offset)) return null
        return text.replaceRange(offset, offset + expected.length, replacement)
    }

    /** Applying a step that doesn't fit would damage the text, so the history goes instead. */
    private fun abandon(): TextSnapshot? {
        undoStack.clear()
        redoStack.clear()
        chars = 0
        return null
    }

    /** The smallest replacement, placed at the caret when repeated characters leave a choice. */
    private fun diff(before: TextSnapshot, after: TextSnapshot): Edit {
        val old = before.text
        val new = after.text
        val shorter = minOf(old.length, new.length)
        val suffixLimit = if (after.selectionStart == after.selectionEnd) {
            minOf(shorter, new.length - after.selectionEnd)
        } else {
            shorter
        }
        var suffix = 0
        while (suffix < suffixLimit && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        var prefix = 0
        while (prefix < shorter - suffix && old[prefix] == new[prefix]) prefix++
        return Edit(
            offset = prefix,
            removed = old.substring(prefix, old.length - suffix),
            inserted = new.substring(prefix, new.length - suffix),
            before = before.selectionStart to before.selectionEnd,
            after = after.selectionStart to after.selectionEnd,
        )
    }

    /** Folds [next] into [top] if it continues the same typing or deleting. */
    private fun merge(top: Edit, next: Edit): Boolean {
        val typing = next.removed.isEmpty() && top.inserted.isNotEmpty() &&
            next.inserted.length <= MAX_COALESCED_INSERT &&
            next.offset == top.offset + top.inserted.length &&
            '\n' !in next.inserted && !(top.inserted.last().isWhitespace() && !next.inserted.first().isWhitespace())
        if (typing) {
            top.inserted += next.inserted
            chars += next.inserted.length
            return true
        }
        if (next.inserted.isNotEmpty() || top.inserted.isNotEmpty() || '\n' in next.removed) return false
        when {
            // Backspace walks left, forward delete stays put.
            next.offset + next.removed.length == top.offset -> {
                top.offset = next.offset
                top.removed = next.removed + top.removed
            }
            next.offset == top.offset -> top.removed += next.removed
            else -> return false
        }
        chars += next.removed.length
        return true
    }

    private companion object {
        /** Anything longer is a paste or a dictated phrase: its own step. */
        const val MAX_COALESCED_INSERT = 32
    }
}
