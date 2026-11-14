package com.ezfiles.reader.application

import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.SensitiveFindings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Whether an open document shows a card, ID or bank account number, for the
 * reader's "Hide from screenshots" hint. The text comes from [documentText]
 * and the checksums run in Rust; only three flags per file are kept, in a
 * small cache keyed by identity, size and modification time, so reopening a
 * file costs nothing. The numbers themselves are never stored or shown.
 */
class SensitiveCheck internal constructor(
    private val documentText: DocumentText,
    private val engine: TidyEngine,
    private val storage: KeyValueStorage,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var cache: LinkedHashMap<String, StoredFindings>? = null

    /** Blocking work runs on [io]; `null` if the file has no readable text. */
    suspend fun check(ref: DocumentRef): SensitiveFindings? {
        val key = ref.sizeBytes?.let { size -> ref.modifiedAt?.let { "${ref.identity}|$size|$it" } }
        if (key != null) {
            lock.withLock { cached()[key] }?.let { return it.toDomain() }
        }
        val text = try {
            documentText.plainText(ref, MAX_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val found = withContext(io) { engine.sensitive(text) }
        if (key != null) {
            lock.withLock {
                val map = cached()
                map.remove(key)
                map[key] = StoredFindings(found.paymentCard, found.nationalId, found.bankAccount)
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
                val snapshot = map.toMap()
                withContext(io) { storage.put(KEY, json.encodeToString(snapshot)) }
            }
        }
        return found
    }

    private suspend fun cached(): LinkedHashMap<String, StoredFindings> = cache ?: withContext(io) {
        try {
            storage.get(KEY)?.let { LinkedHashMap(json.decodeFromString<Map<String, StoredFindings>>(it)) }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: LinkedHashMap()
    }.also { cache = it }

    @Serializable
    private data class StoredFindings(val card: Boolean = false, val id: Boolean = false, val bank: Boolean = false) {
        fun toDomain() = SensitiveFindings(card, id, bank)
    }

    private companion object {
        const val KEY = "sensitive_v1"
        const val MAX_CHARS = 100_000
        const val MAX_ENTRIES = 400
        val json = Json { ignoreUnknownKeys = true }
    }
}
