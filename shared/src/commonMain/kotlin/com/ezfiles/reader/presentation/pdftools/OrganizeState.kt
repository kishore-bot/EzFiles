package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.PdfPageEdit

/** A page in the working order; [sourcePage] (zero-based) also identifies it. */
internal data class OrganizePage(val sourcePage: Int, val rotationDegrees: Int = 0)

private const val UNDO_STEPS = 20

/**
 * The working copy of a document's pages. Immutable; each edit returns the next
 * state. At least one page always remains. Holds no pixels and goes in a
 * ViewModel, not saved state: a long document would overflow the saved-state limit.
 */
internal class OrganizeState private constructor(
    val pageCount: Int,
    val pages: List<OrganizePage>,
    val selected: Set<Int>,
    private val history: List<List<OrganizePage>>,
) {
    constructor(pageCount: Int) : this(pageCount, List(pageCount) { OrganizePage(it) }, emptySet(), emptyList())

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Whether saving would give anything but a copy. */
    val hasChanges: Boolean
        get() = pages.size != pageCount || pages.withIndex().any { (index, page) -> page.sourcePage != index || page.rotationDegrees != 0 }

    val allSelected: Boolean get() = selected.size == pages.size

    val canDelete: Boolean get() = pages.size > 1

    val canDeleteSelection: Boolean get() = selected.isNotEmpty() && selected.size < pages.size

    val edits: List<PdfPageEdit> get() = pages.map { PdfPageEdit(it.sourcePage, it.rotationDegrees) }

    fun toggled(page: Int): OrganizeState = withSelection(if (page in selected) selected - page else selected + page)

    fun selectedAll(): OrganizeState = withSelection(pages.mapTo(HashSet()) { it.sourcePage })

    fun selectionCleared(): OrganizeState = withSelection(emptySet())

    /** Moves one page [by] places, stopping at the ends. */
    fun moved(page: Int, by: Int): OrganizeState {
        val from = pages.indexOfFirst { it.sourcePage == page }
        if (from < 0) return this
        val to = (from + by).coerceIn(pages.indices)
        if (to == from) return this
        return edited(pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Moves the selected pages one place, each keeping its order among them; those at the end stay. */
    fun selectionMoved(later: Boolean): OrganizeState {
        val next = pages.toMutableList()
        val step = if (later) 1 else -1
        val positions = if (later) next.indices.reversed() else next.indices
        for (index in positions) {
            val neighbour = index + step
            if (neighbour in next.indices && next[index].sourcePage in selected && next[neighbour].sourcePage !in selected) {
                val page = next[index]
                next[index] = next[neighbour]
                next[neighbour] = page
            }
        }
        return if (next == pages) this else edited(next)
    }

    fun rotated(page: Int): OrganizeState = edited(pages.map { if (it.sourcePage == page) it.turned() else it })

    fun selectionRotated(): OrganizeState =
        if (selected.isEmpty()) this else edited(pages.map { if (it.sourcePage in selected) it.turned() else it })

    /** Removes a page, unless it's the last one. */
    fun deleted(page: Int): OrganizeState {
        if (!canDelete || pages.none { it.sourcePage == page }) return this
        return edited(pages.filterNot { it.sourcePage == page })
    }

    /** Removes the selected pages, unless that would leave none. */
    fun selectionDeleted(): OrganizeState =
        if (!canDeleteSelection) this else edited(pages.filterNot { it.sourcePage in selected })

    fun undone(): OrganizeState {
        val previous = history.lastOrNull() ?: return this
        return OrganizeState(pageCount, previous, selected, history.dropLast(1))
    }

    private fun withSelection(next: Set<Int>) = OrganizeState(pageCount, pages, next, history)

    private fun edited(next: List<OrganizePage>): OrganizeState {
        val kept = next.mapTo(HashSet()) { it.sourcePage }
        return OrganizeState(pageCount, next, selected.filterTo(HashSet()) { it in kept }, (history + listOf(pages)).takeLast(UNDO_STEPS))
    }

    private fun OrganizePage.turned() = copy(rotationDegrees = (rotationDegrees + 90) % 360)
}
