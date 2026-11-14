package com.ezfiles.reader.presentation.create

import com.ezfiles.reader.domain.normalizeRotation

/** A chosen picture. [id] tells apart the same picture chosen twice. */
data class ImagePage(val id: Int, val uri: String, val rotationDegrees: Int = 0)

/** The pictures of an Images to PDF document, in page order. Every operation returns a new list. */
data class ImagePageList(val pages: List<ImagePage> = emptyList(), private val nextId: Int = 0) {
    fun added(uris: List<String>): ImagePageList {
        val fresh = uris.mapIndexed { index, uri -> ImagePage(nextId + index, uri) }
        return ImagePageList(pages + fresh, nextId + uris.size)
    }

    fun removed(id: Int) = copy(pages = pages.filterNot { it.id == id })

    /** Turns the page a quarter clockwise. */
    fun rotated(id: Int) = copy(
        pages = pages.map { if (it.id == id) it.copy(rotationDegrees = normalizeRotation(it.rotationDegrees + 90)) else it },
    )

    /** Moves the page [by] places; a move past either end does nothing. */
    fun moved(id: Int, by: Int): ImagePageList {
        val from = pages.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in pages.indices) return this
        val reordered = pages.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return copy(pages = reordered)
    }

    /** A flat form for saved instance state, which holds only simple values. */
    fun toSaved(): List<String> = listOf(nextId.toString()) + pages.flatMap { listOf(it.id.toString(), it.rotationDegrees.toString(), it.uri) }

    companion object {
        fun fromSaved(saved: List<String>): ImagePageList {
            val pages = saved.drop(1).chunked(3).map { (id, rotation, uri) -> ImagePage(id.toInt(), uri, rotation.toInt()) }
            return ImagePageList(pages, saved.first().toInt())
        }
    }
}
