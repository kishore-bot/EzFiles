package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.RecentDocument
import com.ezfiles.reader.domain.RecentDocuments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Small persistent key-value storage: SharedPreferences / NSUserDefaults. */
internal interface KeyValueStorage {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

/**
 * [RecentDocuments] persisted as JSON. Only references and metadata are
 * stored, never file content. Unreadable data is discarded rather than
 * failing the app.
 */
internal class RecentDocumentsStore private constructor(
    private val storage: KeyValueStorage,
    private val normalizeRef: (DocumentRef) -> DocumentRef,
    private val now: () -> Long,
) : RecentDocuments {
    constructor(storage: KeyValueStorage, now: () -> Long) : this(storage, { it }, now)
    constructor(storage: KeyValueStorage, now: () -> Long, normalizeRef: (DocumentRef) -> DocumentRef) : this(storage, normalizeRef, now)
    private val lock = Mutex()
    private val state = MutableStateFlow(load())
    override val documents: StateFlow<List<RecentDocument>> = state.asStateFlow()

    override suspend fun record(ref: DocumentRef, format: DocumentFormat) = update { list ->
        val normalized = normalizeRef(ref)
        val openedAt = now()
        val earlier = list.firstOrNull { it.ref.identity == normalized.identity }?.visits.orEmpty()
        val entry = RecentDocument(normalized, format, openedAt, visits = (earlier + openedAt).takeLast(MAX_VISITS))
        (listOf(entry) + list.filterNot { it.ref.identity == normalized.identity }).take(MAX_ENTRIES)
    }

    override suspend fun remove(identity: String) = update { list -> list.filterNot { it.ref.identity == identity } }

    override suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<RecentDocument>) -> List<RecentDocument>) = lock.withLock {
        val updated = transform(state.value)
        storage.put(KEY, json.encodeToString(updated.map(StoredRecent::from)))
        state.value = updated
    }

    private fun load(): List<RecentDocument> = try {
        storage.get(KEY)?.let { json.decodeFromString<List<StoredRecent>>(it) }?.map(StoredRecent::toDomain).orEmpty()
            .map { it.copy(ref = normalizeRef(it.ref)) }.distinctBy { it.ref.identity }
    } catch (e: IllegalArgumentException) {
        // Corrupt or from an incompatible version: start fresh.
        emptyList()
    }

    @Serializable
    private data class StoredRecent(
        val uri: String,
        val name: String,
        val identity: String,
        val size: Long? = null,
        val modifiedAt: Long? = null,
        val format: String? = null,
        val openedAt: Long,
        /** Added later: older entries have only [openedAt]. */
        val visits: List<Long>? = null,
    ) {
        fun toDomain() = RecentDocument(
            ref = DocumentRef(uri, name, size, modifiedAt, identity),
            format = DocumentFormat.entries.firstOrNull { it.name == format },
            openedAt = openedAt,
            visits = visits?.takeIf { it.isNotEmpty() } ?: listOf(openedAt),
        )

        companion object {
            fun from(recent: RecentDocument) = with(recent) {
                StoredRecent(ref.uri, ref.displayName, ref.identity, ref.sizeBytes, ref.modifiedAt, format?.name, openedAt, visits)
            }
        }
    }

    private companion object {
        const val KEY = "recent_documents_v1"
        const val MAX_ENTRIES = 50
        /** Enough for frecency; older visits hardly count after a few weeks anyway. */
        const val MAX_VISITS = 10
        val json = Json { ignoreUnknownKeys = true }
    }
}
