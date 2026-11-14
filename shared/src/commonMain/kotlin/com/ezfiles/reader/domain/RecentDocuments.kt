package com.ezfiles.reader.domain

import kotlinx.coroutines.flow.StateFlow

data class RecentDocument(
    val ref: DocumentRef,
    /** Known once the document has been opened successfully. */
    val format: DocumentFormat?,
    /** Epoch milliseconds. */
    val openedAt: Long,
    /** When it was opened, oldest first, the last few times: for ordering by frecency. */
    val visits: List<Long> = listOf(openedAt),
)

/** Port to the persisted list of recently opened documents, newest first. */
interface RecentDocuments {
    val documents: StateFlow<List<RecentDocument>>

    suspend fun record(ref: DocumentRef, format: DocumentFormat)

    suspend fun remove(identity: String)

    suspend fun clear()
}
