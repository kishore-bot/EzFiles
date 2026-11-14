package com.ezfiles.reader.data

import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityMigrationTest {
    private class Storage : KeyValueStorage {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    @Test
    fun legacy_recent_identity_is_normalized_without_losing_visits() = runTest {
        val storage = Storage()
        val legacy = DocumentRef("provider/document/one", "same.pdf", 10, identity = "same.pdf|10")
        RecentDocumentsStore(storage, { 1L }).record(legacy, DocumentFormat.Pdf)
        val migrated = RecentDocumentsStore(storage, { 2L }) { it.copy(identity = it.uri) }
        migrated.record(legacy, DocumentFormat.Pdf)
        assertEquals(1, migrated.documents.value.size)
        assertEquals(legacy.uri, migrated.documents.value.single().ref.identity)
        assertEquals(listOf(1L, 2L), migrated.documents.value.single().visits)
    }

    @Test
    fun legacy_bookmark_identity_is_normalized_without_changing_its_location_or_id() = runTest {
        val storage = Storage()
        val legacy = DocumentRef("provider/document/one", "same.pdf", 10, identity = "same.pdf|10")
        val bookmark = Bookmark("saved", legacy, DocumentFormat.Pdf, BookmarkLocation.Page(3), createdAt = 4L)
        BookmarksStore(storage).add(bookmark)
        val migrated = BookmarksStore(storage) { it.copy(identity = it.uri) }.all.value.single()
        assertEquals(bookmark.copy(ref = legacy.copy(identity = legacy.uri)), migrated)
    }
}
