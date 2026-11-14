package com.ezfiles.reader.application

import com.ezfiles.reader.domain.ContentBatch
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.domain.DocumentReader
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.domain.RecentDocuments

/**
 * Owns the reading policy and the single open session, so callers never leak
 * or double-close native resources, and records successfully opened
 * documents in [recents]. Not thread-safe: call it from one thread (the
 * ViewModel's main dispatcher).
 */
class DocumentReadingService(
    private val reader: DocumentReader,
    private val limits: ReadLimits = ReadLimits.Default,
    private val recents: RecentDocuments? = null,
) : AutoCloseable {
    private var session: DocumentSession? = null

    /**
     * Closes the current document, then opens [ref]. Reopening [asSource] is a
     * different view of the same file, so it leaves the recents entry alone.
     * [password] is passed on for this one open and not kept.
     */
    suspend fun open(ref: DocumentRef, asSource: Boolean = false, password: String? = null): DocumentInfo {
        close()
        val opened = reader.open(ref, limits, asSource, password)
        session = opened
        if (!asSource) recents?.record(ref, opened.info.format)
        return opened.info
    }

    suspend fun nextBatch(): ContentBatch? =
        checkNotNull(session) { "no document is open" }.nextBatch()

    fun cancel() {
        session?.cancel()
    }

    suspend fun resource(name: String): ByteArray? = session?.resource(name)

    override fun close() {
        session?.close()
        session = null
    }
}
