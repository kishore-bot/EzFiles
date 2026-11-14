package com.ezfiles.reader.bookmarks

import com.ezfiles.reader.data.BookmarksStore
import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkList
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.presentation.bookmarks.migrateBookBookmarks
import com.ezfiles.reader.presentation.bookmarks.pageBookmarks
import com.ezfiles.reader.presentation.viewers.BookAnnotations
import com.ezfiles.reader.presentation.viewers.BookBookmark
import com.ezfiles.reader.presentation.viewers.BookChapter
import com.ezfiles.reader.presentation.viewers.BookHighlight
import com.ezfiles.reader.presentation.viewers.BookManifest
import com.ezfiles.reader.presentation.viewers.BookTocEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemoryStorage : KeyValueStorage {
    val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class BookmarksTest {
    private val report = DocumentRef("content://docs/report", "report.pdf", 100, null, "id-report")
    private val book = DocumentRef("content://docs/book", "book.epub", 200, null, "id-book")

    private fun pdfMark(page: Int, at: Long, id: String = "p$page-$at", ref: DocumentRef = report) =
        Bookmark(id, ref, DocumentFormat.Pdf, BookmarkLocation.Page(page), createdAt = at)

    @Test
    fun adds_and_lists_newest_first() = runTest {
        val store = BookmarksStore(MemoryStorage())
        store.add(pdfMark(1, at = 10))
        store.add(pdfMark(5, at = 30))
        store.add(Bookmark("b1", book, DocumentFormat.Epub, BookmarkLocation.BookOffset(2, 40), createdAt = 20))
        assertEquals(listOf(30L, 20L, 10L), store.all.value.map { it.createdAt })
        assertEquals(listOf(5, 1), BookmarkList.forDocument(store.all.value, report.identity).map { (it.location as BookmarkLocation.Page).index })
    }

    @Test
    fun the_same_place_is_kept_once() = runTest {
        val store = BookmarksStore(MemoryStorage())
        val first = store.add(pdfMark(3, at = 10, id = "first"))
        val again = store.add(pdfMark(3, at = 50, id = "second"))
        assertEquals("first", again.id)
        assertEquals(listOf(first), store.all.value)
        // The same page of another file is another place.
        store.add(pdfMark(3, at = 60, ref = book))
        assertEquals(2, store.all.value.size)
    }

    @Test
    fun toggle_adds_then_removes() = runTest {
        val store = BookmarksStore(MemoryStorage())
        assertTrue(store.toggle(pdfMark(7, at = 1, id = "a")))
        assertFalse(store.toggle(pdfMark(7, at = 2, id = "b")))
        assertTrue(store.all.value.isEmpty())
    }

    @Test
    fun removes_by_id_and_by_place() = runTest {
        val store = BookmarksStore(MemoryStorage())
        store.addAll(listOf(pdfMark(1, at = 1, id = "one"), pdfMark(2, at = 2, id = "two"), pdfMark(3, at = 3, id = "three")))
        store.remove("two")
        store.remove(BookmarkSpot(report.identity, BookmarkLocation.Page(3)))
        assertEquals(listOf("one"), store.all.value.map { it.id })
    }

    @Test
    fun keeps_at_most_the_cap_dropping_the_oldest() = runTest {
        val store = BookmarksStore(MemoryStorage())
        store.addAll((0 until BookmarkList.MAX_BOOKMARKS + 5).map { pdfMark(it, at = it.toLong()) })
        assertEquals(BookmarkList.MAX_BOOKMARKS, store.all.value.size)
        assertEquals(5L, store.all.value.minOf { it.createdAt })
    }

    @Test
    fun long_text_is_trimmed() = runTest {
        val store = BookmarksStore(MemoryStorage())
        store.add(pdfMark(0, at = 1).copy(label = "x".repeat(1000), snippet = "y".repeat(1000)))
        val kept = store.all.value.single()
        assertEquals(BookmarkList.MAX_LABEL_CHARS, kept.label.length)
        assertEquals(BookmarkList.MAX_SNIPPET_CHARS, kept.snippet.length)
    }

    @Test
    fun survives_a_restart() = runTest {
        val storage = MemoryStorage()
        BookmarksStore(storage).apply {
            add(pdfMark(4, at = 10))
            add(Bookmark("b1", book, DocumentFormat.Epub, BookmarkLocation.BookOffset(2, 40), "Chapter Two", "It was", 20))
        }
        val reloaded = BookmarksStore(storage).all.value
        assertEquals(2, reloaded.size)
        assertEquals(Bookmark("b1", book, DocumentFormat.Epub, BookmarkLocation.BookOffset(2, 40), "Chapter Two", "It was", 20), reloaded[0])
        assertEquals(report, reloaded[1].ref)
    }

    @Test
    fun unreadable_data_is_dropped_entry_by_entry() {
        val storage = MemoryStorage()
        storage.values["bookmarks_v1"] = """[
            {"id":"ok","uri":"u","name":"a.pdf","identity":"i","location":{"type":"page","index":2},"createdAt":5},
            {"id":"future","uri":"u","name":"a.pdf","identity":"i","location":{"type":"somethingNew"}},
            {"nonsense":true}
        ]"""
        assertEquals(listOf("ok"), BookmarksStore(storage).all.value.map { it.id })
        storage.values["bookmarks_v1"] = "not json"
        assertTrue(BookmarksStore(storage).all.value.isEmpty())
    }

    @Test
    fun groups_by_file_most_recently_marked_first() {
        val list = listOf(pdfMark(1, at = 10), pdfMark(2, at = 40, ref = book), pdfMark(3, at = 30))
        val groups = BookmarkList.byDocument(list)
        assertEquals(listOf(book.identity, report.identity), groups.map { it.first.identity })
        assertEquals(listOf(30L, 10L), groups[1].second.map { it.createdAt })
    }

    @Test
    fun location_labels() {
        assertEquals("Page 12", pdfMark(11, at = 0).locationLabel)
        val epub = Bookmark("b", book, DocumentFormat.Epub, BookmarkLocation.BookOffset(4, 0))
        assertEquals("Chapter 5", epub.locationLabel)
        assertEquals("The Storm", epub.copy(label = "The Storm").locationLabel)
    }

    @Test
    fun old_book_bookmarks_move_to_the_shared_list_and_highlights_stay() = runTest {
        val manifest = BookManifest(
            chapters = listOf(BookChapter("c1.xhtml"), BookChapter("c2.xhtml")),
            toc = listOf(BookTocEntry("Opening", "c1.xhtml"), BookTocEntry("The Storm", "c2.xhtml")),
        )
        val highlight = BookHighlight("aaaaaaaa", 0, 1, 5, "text")
        val saved = BookAnnotations(
            highlights = listOf(highlight),
            bookmarks = listOf(BookBookmark("0123abcd", 1, 300, "Rain fell", created = 77), BookBookmark("4567abcd", 0, 0, "", created = 0)),
        )
        val migration = migrateBookBookmarks(saved, book, manifest, now = 999)
        assertEquals(BookAnnotations(highlights = listOf(highlight)), migration.left)
        val storage = MemoryStorage()
        val store = BookmarksStore(storage)
        store.addAll(migration.moved)
        // Moving twice (a crash before the book's notes were saved) adds nothing more.
        store.addAll(migrateBookBookmarks(saved, book, manifest, now = 1000).moved)
        val moved = BookmarkList.forDocument(BookmarksStore(storage).all.value, book.identity)
        assertEquals(2, moved.size)
        val storm = moved.first { it.id == "0123abcd" }
        assertEquals(BookmarkLocation.BookOffset(1, 300), storm.location)
        assertEquals("The Storm", storm.label)
        assertEquals("Rain fell", storm.snippet)
        assertEquals(77L, storm.createdAt)
        assertEquals(999L, moved.first { it.id == "4567abcd" }.createdAt)
        // And the reader page gets them back as its own bookmarks, in reading order.
        assertEquals(
            listOf(BookBookmark("4567abcd", 0, 0, "", 999), BookBookmark("0123abcd", 1, 300, "Rain fell", 77)),
            pageBookmarks(moved),
        )
    }

    @Test
    fun the_reader_page_only_gets_its_own_kind_of_id() {
        val bad = Bookmark("\"};alert(1);//", book, DocumentFormat.Epub, BookmarkLocation.BookOffset(0, 0))
        val pdf = pdfMark(1, at = 0, id = "abcdef0123")
        assertTrue(pageBookmarks(listOf(bad, pdf)).isEmpty())
    }
}
