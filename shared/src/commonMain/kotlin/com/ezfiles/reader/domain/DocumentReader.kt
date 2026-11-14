package com.ezfiles.reader.domain

/**
 * Port to the document engine. Implemented in the data layer; faked in tests.
 * All failures are thrown as [DocumentException].
 */
interface DocumentReader {
    /**
     * With [asSource], text formats open as their raw lines instead of being
     * rendered. [password] unlocks an encrypted document; implementations must
     * not log or keep it.
     */
    suspend fun open(
        ref: DocumentRef,
        limits: ReadLimits,
        asSource: Boolean = false,
        password: String? = null,
    ): DocumentSession
}

/**
 * An open document whose content is pulled in bounded batches. The caller
 * owns it and must [close] it; closing releases the native session and file.
 */
interface DocumentSession : AutoCloseable {
    val info: DocumentInfo

    /** The next batch, or `null` after the last one. */
    suspend fun nextBatch(): ContentBatch?

    /** Asks a running [nextBatch] to stop early. Safe from any thread. */
    fun cancel()

    /**
     * Bytes of an embedded resource named by a block (a slide picture), or
     * `null` if the document has none by that name.
     */
    suspend fun resource(name: String): ByteArray? = null
}
