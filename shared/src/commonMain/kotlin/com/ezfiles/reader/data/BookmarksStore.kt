package com.ezfiles.reader.data

import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkList
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.Bookmarks
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.spot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * [Bookmarks] persisted as JSON, like the recent files: references, places
 * and short labels only, never file content. Unreadable data is discarded
 * rather than failing the app.
 */
internal class BookmarksStore(
    private val storage: KeyValueStorage,
    private val normalizeRef: (DocumentRef) -> DocumentRef = { it },
) : Bookmarks {
    private val lock = Mutex()
    private val state = MutableStateFlow(load())
    override val all: StateFlow<List<Bookmark>> = state.asStateFlow()

    override suspend fun add(bookmark: Bookmark): Bookmark = lock.withLock {
        val updated = BookmarkList.add(state.value, listOf(bookmark))
        save(updated)
        BookmarkList.find(updated, bookmark.spot) ?: bookmark
    }

    override suspend fun addAll(bookmarks: List<Bookmark>) = update { BookmarkList.add(it, bookmarks) }

    override suspend fun remove(id: String) = update { BookmarkList.remove(it, id) }

    override suspend fun remove(spot: BookmarkSpot) = update { BookmarkList.remove(it, spot) }

    override suspend fun toggle(bookmark: Bookmark): Boolean = lock.withLock {
        val updated = BookmarkList.toggle(state.value, bookmark)
        save(updated)
        BookmarkList.find(updated, bookmark.spot) != null
    }

    private suspend fun update(transform: (List<Bookmark>) -> List<Bookmark>) = lock.withLock { save(transform(state.value)) }

    private fun save(updated: List<Bookmark>) {
        if (updated == state.value) return
        storage.put(KEY, json.encodeToString(updated.map(StoredBookmark::from)))
        state.value = updated
    }

    /** Each entry on its own, so one this version can't read doesn't take the others with it. */
    private fun load(): List<Bookmark> {
        val entries = try {
            storage.get(KEY)?.let { json.parseToJsonElement(it) as? JsonArray }.orEmpty()
        } catch (e: SerializationException) {
            // Corrupt: start fresh.
            return emptyList()
        }
        val list = entries.mapNotNull { entry ->
            try {
                json.decodeFromJsonElement(StoredBookmark.serializer(), entry).toDomain().let { it.copy(ref = normalizeRef(it.ref)) }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return BookmarkList.add(emptyList(), list)
    }

    @Serializable
    private data class StoredBookmark(
        val id: String,
        val uri: String,
        val name: String,
        val identity: String,
        val size: Long? = null,
        val modifiedAt: Long? = null,
        val format: String? = null,
        val location: BookmarkLocation,
        val label: String = "",
        val snippet: String = "",
        val createdAt: Long = 0,
    ) {
        fun toDomain() = Bookmark(
            id = id,
            ref = DocumentRef(uri, name, size, modifiedAt, identity),
            format = DocumentFormat.entries.firstOrNull { it.name == format },
            location = location,
            label = label,
            snippet = snippet,
            createdAt = createdAt,
        )

        companion object {
            fun from(b: Bookmark) = StoredBookmark(
                b.id, b.ref.uri, b.ref.displayName, b.ref.identity, b.ref.sizeBytes, b.ref.modifiedAt,
                b.format?.name, b.location, b.label, b.snippet, b.createdAt,
            )
        }
    }

    private companion object {
        const val KEY = "bookmarks_v1"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Keeps bookmarks for this run only; for previews, tests and platforms without storage. */
internal fun memoryBookmarks(): Bookmarks = BookmarksStore(
    object : KeyValueStorage {
        private var value: String? = null

        override fun get(key: String) = value

        override fun put(key: String, value: String?) {
            this.value = value
        }
    },
)
