package com.ezfiles.reader.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where in a document a bookmark points. */
@Serializable
sealed interface BookmarkLocation {
    /** A PDF page, counted from 0. */
    @Serializable
    @SerialName("page")
    data class Page(val index: Int) : BookmarkLocation

    /**
     * A place in an e-book: the first character on the marked page, counted
     * from the start of [chapter]'s body, so it holds across text sizes and screens.
     */
    @Serializable
    @SerialName("book")
    data class BookOffset(val chapter: Int, val offset: Int) : BookmarkLocation
}

/**
 * A marked place in a file. [label] names the place ("Page 12" is derived for
 * pages, so it's empty there; an e-book's is its chapter title), [snippet] is
 * text from it. Only the file's reference is kept, never its content.
 */
data class Bookmark(
    val id: String,
    val ref: DocumentRef,
    val format: DocumentFormat?,
    val location: BookmarkLocation,
    val label: String = "",
    val snippet: String = "",
    /** Epoch milliseconds. */
    val createdAt: Long = 0,
) {
    /** "Page 12", or the chapter it is in. */
    val locationLabel: String
        get() = when (location) {
            is BookmarkLocation.Page -> "Page ${location.index + 1}"
            is BookmarkLocation.BookOffset -> label.ifBlank { "Chapter ${location.chapter + 1}" }
        }
}

/** A bookmark's file and place, enough to find it again. */
data class BookmarkSpot(val identity: String, val location: BookmarkLocation)

/** Port to the persisted bookmarks of every file, newest first. */
interface Bookmarks {
    val all: StateFlow<List<Bookmark>>

    /** Adds [bookmark] unless its file already has one at that place; returns the one kept. */
    suspend fun add(bookmark: Bookmark): Bookmark

    /** Adds several at once, as [add] does each. */
    suspend fun addAll(bookmarks: List<Bookmark>)

    suspend fun remove(id: String)

    suspend fun remove(spot: BookmarkSpot)

    /** Removes the bookmark at [bookmark]'s place, or adds it; true when the place is now bookmarked. */
    suspend fun toggle(bookmark: Bookmark): Boolean
}

/** The list operations behind [Bookmarks], free of storage so they can be tested alone. */
object BookmarkList {
    /** Bookmarks kept in all; the oldest go first past this. */
    const val MAX_BOOKMARKS = 500
    const val MAX_LABEL_CHARS = 120
    const val MAX_SNIPPET_CHARS = 200

    fun forDocument(list: List<Bookmark>, identity: String): List<Bookmark> = list.filter { it.ref.identity == identity }

    fun find(list: List<Bookmark>, spot: BookmarkSpot): Bookmark? =
        list.firstOrNull { it.ref.identity == spot.identity && it.location == spot.location }

    /** Newest first; an addition at a place already bookmarked keeps the first one. */
    fun add(list: List<Bookmark>, additions: List<Bookmark>): List<Bookmark> {
        var result = list
        for (b in additions) {
            if (find(result, b.spot) != null) continue
            result = result + b.trimmed()
        }
        return result.sortedByDescending { it.createdAt }.take(MAX_BOOKMARKS)
    }

    fun remove(list: List<Bookmark>, id: String): List<Bookmark> = list.filterNot { it.id == id }

    fun remove(list: List<Bookmark>, spot: BookmarkSpot): List<Bookmark> =
        list.filterNot { it.ref.identity == spot.identity && it.location == spot.location }

    fun toggle(list: List<Bookmark>, bookmark: Bookmark): List<Bookmark> =
        if (find(list, bookmark.spot) != null) remove(list, bookmark.spot) else add(list, listOf(bookmark))

    /** Files with bookmarks, the most recently marked first, each with its bookmarks newest first. */
    fun byDocument(list: List<Bookmark>): List<Pair<DocumentRef, List<Bookmark>>> =
        list.sortedByDescending { it.createdAt }
            .groupBy { it.ref.identity }
            .values
            .map { group -> group.first().ref to group }

    private fun Bookmark.trimmed() = copy(label = label.take(MAX_LABEL_CHARS), snippet = snippet.take(MAX_SNIPPET_CHARS))
}

val Bookmark.spot: BookmarkSpot get() = BookmarkSpot(ref.identity, location)
