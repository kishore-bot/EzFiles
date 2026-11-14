package com.ezfiles.reader.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ranks file names against what the user types, tolerating typos ("invoce" finds Invoice_2025.pdf). */
interface FileNameSearch {
    /** Prepares [names] once, when the list changes; the result answers each keystroke. */
    fun prepare(names: List<String>): FileNameMatcher
}

fun interface FileNameMatcher {
    /** Positions in the prepared names that match [query], best first; a blank query keeps every name in order. */
    fun rank(query: String): List<Int>
}

/** Case-insensitive substring matching in the given order: for tests, and where the engine isn't available. */
object SubstringFileNameSearch : FileNameSearch {
    override fun prepare(names: List<String>) = FileNameMatcher { query ->
        val q = query.trim()
        names.indices.filter { names[it].contains(q, ignoreCase = true) }
    }
}

/**
 * A document whose text matches a search. [highlights] are the matched words in
 * [snippet] (UTF-16 ranges, as Kotlin strings index); [page] is the PDF page the
 * snippet is on, from 0, where known.
 */
data class ContentHit(
    val ref: DocumentRef,
    val snippet: String,
    val highlights: List<IntRange>,
    val page: Int?,
)

/** How far the search index has got: [indexed] of [total] library files; [running] while it works. */
data class IndexProgress(val indexed: Int = 0, val total: Int = 0, val running: Boolean = false)

/**
 * Port to search inside the library's files. The index is built in the
 * background only while the library is on screen, a few files at a time, and
 * lives in app-private storage. Encrypted files are never indexed.
 */
interface ContentSearch {
    val progress: StateFlow<IndexProgress>

    /** The library is on screen; indexing pauses while it isn't. */
    fun setActive(active: Boolean)

    /** Files whose text matches [query], best first. Indexing starts now if it hadn't. */
    suspend fun search(query: String, limit: Int = 20): List<ContentHit>

    /** Deletes the index. It is rebuilt only when the user next searches. */
    suspend fun clear()
}

/** No search inside files: for tests and platforms without the engine. */
object NoContentSearch : ContentSearch {
    private val idle = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = idle.asStateFlow()

    override fun setActive(active: Boolean) = Unit

    override suspend fun search(query: String, limit: Int): List<ContentHit> = emptyList()

    override suspend fun clear() = Unit
}

/** No document text: for tests and platforms without the engine. */
object NoDocumentText : DocumentText {
    override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? = null
}
