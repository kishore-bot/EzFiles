package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeIndexEntry
import com.ezfiles.engine.ffi.NativeNameIndex
import com.ezfiles.engine.ffi.NativeSearchIndex
import com.ezfiles.reader.domain.FileNameMatcher
import com.ezfiles.reader.domain.FileNameSearch

/** A document as the index knows it: changed when its size or modification time differs. */
internal data class IndexEntry(val key: String, val size: Long, val modifiedAt: Long)

internal data class IndexHit(val key: String, val snippet: String, val highlights: List<IntRange>, val page: Int?)

/**
 * Port to the full-text index (BM25, snippets, persisted). Every call is
 * blocking and quick except [add]; the Rust index is the implementation.
 */
internal interface FullTextIndex {
    /** Positions in [entries] of documents that are new or changed. */
    fun stale(entries: List<IndexEntry>): List<Int>

    /** Forgets every document not in [keys]. */
    fun retain(keys: List<String>)

    /** Whether another document still fits in the index's budgets. */
    fun hasRoom(): Boolean

    /** Indexes [text] (empty for a file with none); `false` when the index is full. */
    fun add(entry: IndexEntry, text: String, pageStarts: List<Int>): Boolean

    fun query(query: String, limit: Int): List<IndexHit>

    fun save()

    /** Deletes the index, in memory and on disk. */
    fun clear()
}

/** [FullTextIndex] in the Rust engine, kept in [directory] (app-private cache). */
internal class RustFullTextIndex(directory: String) : FullTextIndex {
    private val native = NativeSearchIndex(directory)

    override fun stale(entries: List<IndexEntry>): List<Int> =
        native.stale(entries.map { NativeIndexEntry(it.key, it.size.coerceAtLeast(0).toULong(), it.modifiedAt) }).map { it.toInt() }

    override fun retain(keys: List<String>) {
        native.retain(keys)
    }

    override fun hasRoom(): Boolean = native.hasRoom()

    override fun add(entry: IndexEntry, text: String, pageStarts: List<Int>): Boolean =
        native.add(entry.key, entry.size.coerceAtLeast(0).toULong(), entry.modifiedAt, text, pageStarts.map { it.toUInt() })

    override fun query(query: String, limit: Int): List<IndexHit> =
        native.query(query, limit.coerceAtLeast(0).toUInt()).map { hit ->
            IndexHit(
                key = hit.key,
                snippet = hit.snippet,
                highlights = hit.ranges.map { it.start.toInt() until it.end.toInt() },
                page = hit.page?.toInt(),
            )
        }

    override fun save() = native.save()

    override fun clear() = native.clear()
}

/**
 * [FileNameSearch] in the Rust engine: the names cross the boundary once per
 * list, and each keystroke is a single call that returns the ranked positions.
 */
internal object RustFileNameSearch : FileNameSearch {
    override fun prepare(names: List<String>): FileNameMatcher {
        val index = NativeNameIndex(names)
        return FileNameMatcher { query ->
            if (query.isBlank()) names.indices.toList() else index.search(query, names.size.toUInt()).map { it.toInt() }
        }
    }
}
