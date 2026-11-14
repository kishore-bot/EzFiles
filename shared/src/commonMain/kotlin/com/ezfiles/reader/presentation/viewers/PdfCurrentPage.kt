package com.ezfiles.reader.presentation.viewers

/** One row of pages on screen: its index and where it sits, in pixels from the top of the list's viewport. */
internal data class VisibleRow(val index: Int, val top: Int, val bottom: Int)

/**
 * The row the reader is on: the one under the middle of the screen, or the
 * last once the list can't scroll further. The first row merely touching the
 * top edge read one page behind, and the last pages, which can never reach
 * the top, could never be "current" at all.
 */
internal fun currentRow(rows: List<VisibleRow>, viewportHeight: Int, atEnd: Boolean, fallback: Int): Int {
    if (rows.isEmpty()) return fallback
    if (atEnd) return rows.maxOf { it.index }
    val middle = viewportHeight / 2
    return rows.firstOrNull { middle in it.top until it.bottom }?.index
        // Between two rows (the gap): the one below the middle.
        ?: rows.firstOrNull { it.top >= middle }?.index
        ?: rows.last().index
}
