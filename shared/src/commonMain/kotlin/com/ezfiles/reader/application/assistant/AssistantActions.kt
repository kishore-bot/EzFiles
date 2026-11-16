package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.Bookmarks
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.RecentDocuments
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

data class AssistantDocument(val id: String, val name: String, val sizeBytes: Long?, val modifiedAt: Long?)
data class AssistantDocumentResults(val documents: List<AssistantDocument>, val partial: Boolean)
data class AssistantBookmark(val id: String, val document: AssistantDocument, val pageNumber: Int?, val chapterNumber: Int?)
data class AssistantBookmarkTarget(val ref: DocumentRef, val location: BookmarkLocation)
data class AssistantExcerpt(val document: AssistantDocument, val text: String, val available: Boolean, val truncated: Boolean)
data class AssistantTextMatch(val offset: Int, val snippet: String)
data class AssistantTextMatches(val document: AssistantDocument, val matches: List<AssistantTextMatch>, val partial: Boolean, val available: Boolean)

enum class AssistantSort { Relevance, ModifiedNewest, OpenedNewest, SizeLargest }

/** A library search the assistant can narrow; every field is optional and matched against names and metadata only. */
data class AssistantLibraryQuery(
    val query: String = "",
    /** At least one of these must appear in the name (a category's keywords, e.g. resume or cv). */
    val anyOf: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
    val exclude: List<String> = emptyList(),
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val minBytes: Long? = null,
    val sort: AssistantSort = AssistantSort.Relevance,
)

/** Local, bounded actions. Platform wrappers never send [DocumentRef] or raw URIs to an assistant. */
class AssistantActions internal constructor(
    private val device: DeviceDocuments,
    private val recents: RecentDocuments,
    val access: AssistantAccess,
    private val opaqueId: (DocumentRef) -> String,
    private val validateRef: suspend (DocumentRef) -> Boolean,
    private val documentText: DocumentText,
    private val bookmarks: Bookmarks,
) {
    suspend fun searchDocuments(query: String = "", limit: Int = 0): AssistantDocumentResults = access.authorized { token ->
        require(query.length <= MAX_QUERY_CHARS) { "Search text is too long." }
        val snapshot = snapshot(token)
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val matches = snapshot.refs.asSequence().filter { ref ->
            val name = ref.displayName.take(512)
            terms.all { name.contains(it, ignoreCase = true) }
        }.take(MAX_RESULT_CANDIDATES + 1).toList()
        val readable = readable(matches, token, resultLimit(limit))
        AssistantDocumentResults(readable.refs.map(::metadata), snapshot.partial || readable.partial)
    }

    suspend fun searchLibrary(search: AssistantLibraryQuery, limit: Int = 0): AssistantDocumentResults = access.authorized { token ->
        require(search.query.length <= MAX_QUERY_CHARS) { "Search text is too long." }
        val snapshot = snapshot(token)
        val terms = search.query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val anyOf = search.anyOf.filter(String::isNotBlank)
        val exclude = search.exclude.filter(String::isNotBlank)
        val extensions = search.extensions.map { it.lowercase().removePrefix(".") }.toSet()
        val matches = snapshot.refs.filter { ref ->
            val name = ref.displayName.take(512)
            terms.all { name.contains(it, ignoreCase = true) } &&
                (anyOf.isEmpty() || anyOf.any { name.contains(it, ignoreCase = true) }) &&
                exclude.none { name.contains(it, ignoreCase = true) } &&
                (extensions.isEmpty() || name.substringAfterLast('.', "").lowercase() in extensions) &&
                (search.modifiedAfter == null || (ref.modifiedAt ?: Long.MIN_VALUE) >= search.modifiedAfter) &&
                (search.modifiedBefore == null || (ref.modifiedAt ?: Long.MAX_VALUE) < search.modifiedBefore) &&
                (search.minBytes == null || (ref.sizeBytes ?: 0) >= search.minBytes)
        }
        val ordered = when (search.sort) {
            AssistantSort.Relevance -> matches
            // Unknown dates sort last: they cannot prove a file is the newest.
            AssistantSort.ModifiedNewest -> matches.sortedByDescending { it.modifiedAt ?: Long.MIN_VALUE }
            AssistantSort.SizeLargest -> matches.sortedByDescending { it.sizeBytes ?: -1L }
            AssistantSort.OpenedNewest -> {
                val opened = recents.documents.value.withIndex().associate { (index, recent) -> opaqueId(recent.ref) to index }
                matches.filter { opaqueId(it) in opened }.sortedBy { opened.getValue(opaqueId(it)) }
            }
        }
        val readable = readable(ordered.take(MAX_RESULT_CANDIDATES + 1), token, resultLimit(limit))
        AssistantDocumentResults(readable.refs.map(::metadata), snapshot.partial || readable.partial)
    }

    /** The assistant's opaque ID for a document the user already has open. */
    fun documentId(ref: DocumentRef): String = opaqueId(ref)

    suspend fun listRecentDocuments(limit: Int = 0): AssistantDocumentResults = access.authorized { token ->
        val snapshot = snapshot(token)
        val allowed = snapshot.refs.associateBy(opaqueId)
        val refs = recents.documents.value.mapNotNull { allowed[opaqueId(it.ref)] }.distinctBy(opaqueId)
        val readable = readable(refs, token, resultLimit(limit))
        AssistantDocumentResults(readable.refs.map(::metadata), snapshot.partial || readable.partial)
    }

    suspend fun getDocumentInfo(documentId: String): AssistantDocument = access.authorized { token ->
        metadata(resolve(snapshot(token), documentId, token))
    }

    /** Only for a platform handoff; resolve again when the foreground consumes it. */
    suspend fun resolveDocument(documentId: String): DocumentRef = access.authorized { token ->
        resolve(snapshot(token), documentId, token)
    }

    /** Resolve an ordered, bounded selection with one library refresh rather than one scan per file. */
    suspend fun resolveDocuments(documentIds: List<String>): List<DocumentRef> = access.authorized { token ->
        require(documentIds.size in 1..MAX_RESULTS && documentIds.distinct().size == documentIds.size) { "Choose up to twenty distinct documents." }
        val snapshot = snapshot(token)
        val refs = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
            documentIds.map { resolve(snapshot, it, token) }
        } ?: throw IllegalArgumentException("These documents could not be verified in time. Choose them again.")
        refs.forEach(::requireCurrentScope)
        refs
    }

    /** Revalidate current scope and grants when a platform handoff or confirmation resumes. */
    suspend fun revalidateDocument(ref: DocumentRef): DocumentRef = access.authorized { token ->
        val current = resolve(snapshot(token), opaqueId(ref), token)
        val sizeChanged = ref.sizeBytes != null && current.sizeBytes != null && ref.sizeBytes != current.sizeBytes
        val modifiedChanged = ref.modifiedAt != null && current.modifiedAt != null && ref.modifiedAt != current.modifiedAt
        require(!sizeChanged && !modifiedChanged) { "This document changed. Select it again before continuing." }
        current
    }

    suspend fun listBookmarks(documentId: String = "", limit: Int = 0): List<AssistantBookmark> = access.authorized { token ->
        val snapshot = snapshot(token)
        val refs = snapshot.refs.associateBy(opaqueId)
        if (documentId.isNotEmpty()) resolve(snapshot, documentId, token)
        val result = ArrayList<AssistantBookmark>()
        val readable = HashMap<String, Boolean>()
        for (bookmark in bookmarks.all.value) {
            val id = opaqueId(bookmark.ref)
            val ref = refs[id] ?: continue
            if (documentId.isNotEmpty() && id != documentId) continue
            val permitted = readable[id] ?: validateRef(ref).also { readable[id] = it }
            access.recheckAuthorization(token)
            if (!permitted || !inCurrentScope(ref)) continue
            result += AssistantBookmark(
                bookmark.id,
                metadata(ref),
                (bookmark.location as? BookmarkLocation.Page)?.index?.plus(1),
                (bookmark.location as? BookmarkLocation.BookOffset)?.chapter?.plus(1),
            )
            if (result.size == resultLimit(limit)) break
        }
        // An earlier result may have been removed while another provider query was suspended.
        result.filter { bookmark -> refs[bookmark.document.id]?.let(::inCurrentScope) == true }
    }

    suspend fun resolveBookmark(bookmarkId: String): AssistantBookmarkTarget = access.authorized { token ->
        require(bookmarkId.length in 1..MAX_ID_CHARS) { "Invalid bookmark identifier." }
        val bookmark = bookmarks.all.value.firstOrNull { it.id == bookmarkId }
            ?: throw IllegalArgumentException("This bookmark is no longer available.")
        AssistantBookmarkTarget(resolve(snapshot(token), opaqueId(bookmark.ref), token), bookmark.location)
    }

    suspend fun getDocumentExcerpt(documentId: String, maxChars: Int = 0): AssistantExcerpt = access.authorized(content = true) { token ->
        val ref = resolve(snapshot(token), documentId, token)
        val count = if (maxChars == 0) DEFAULT_EXCERPT_CHARS else maxChars.coerceIn(1, MAX_EXCERPT_CHARS)
        val text = withTimeoutOrNull(TEXT_TIMEOUT_MS) { documentText.plainText(ref, count + 1) }
        checkStillReadable(ref, token)
        AssistantExcerpt(metadata(ref), text.orEmpty().take(count), text != null, (text?.length ?: 0) > count)
    }

    suspend fun findTextInDocument(documentId: String, query: String, limit: Int = 0): AssistantTextMatches = access.authorized(content = true) { token ->
        require(query.isNotBlank() && query.length <= MAX_QUERY_CHARS) { "Enter between 1 and 256 search characters." }
        val ref = resolve(snapshot(token), documentId, token)
        val text = withTimeoutOrNull(TEXT_TIMEOUT_MS) { documentText.plainText(ref, MAX_SEARCH_CHARS + 1) }
        checkStillReadable(ref, token)
        val searchable = text.orEmpty().take(MAX_SEARCH_CHARS)
        val matches = ArrayList<AssistantTextMatch>()
        val count = resultLimit(limit)
        var position = 0
        var more = false
        while (position <= searchable.length - query.length) {
            currentCoroutineContext().ensureActive()
            val offset = searchable.indexOf(query, position, ignoreCase = true)
            if (offset < 0) break
            if (matches.size == count) {
                more = true
                break
            }
            matches += AssistantTextMatch(offset, searchable.substring((offset - 40).coerceAtLeast(0), (offset + query.length + 80).coerceAtMost(searchable.length)))
            position = offset + query.length
        }
        AssistantTextMatches(metadata(ref), matches, more || text == null || text.length > MAX_SEARCH_CHARS, available = text != null)
    }

    private data class Snapshot(val refs: List<DocumentRef>, val partial: Boolean)

    private suspend fun snapshot(token: Long): Snapshot {
        val refreshed = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { device.refresh(); true } == true
        access.recheckAuthorization(token)
        // iOS has no folder library yet; only explicitly picked, still-readable recents qualify.
        val candidates = if (device.supportsFolders) device.files.value else recents.documents.value.map { it.ref }
        val refs = candidates.distinctBy(opaqueId).take(MAX_LIBRARY_FILES)
        return Snapshot(refs, !refreshed || device.scanning.value || device.limitReached.value || candidates.size > MAX_LIBRARY_FILES)
    }

    private suspend fun resolve(snapshot: Snapshot, id: String, token: Long): DocumentRef {
        require(id.length in 1..MAX_ID_CHARS) { "Invalid document identifier." }
        val ref = snapshot.refs.firstOrNull { opaqueId(it) == id }
            ?: throw IllegalArgumentException("This document is outside the enabled assistant scope or is no longer available.")
        checkStillReadable(ref, token)
        return ref
    }

    private suspend fun readable(candidates: List<DocumentRef>, token: Long, limit: Int): Snapshot {
        val result = ArrayList<DocumentRef>()
        val partial = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
            for ((index, ref) in candidates.withIndex()) {
                currentCoroutineContext().ensureActive()
                // Bound both the number of provider queries and the total time waiting for them.
                if (index == MAX_RESULT_CANDIDATES) return@withTimeoutOrNull true
                if (!inCurrentScope(ref)) continue
                val permitted = validateRef(ref)
                access.recheckAuthorization(token)
                if (permitted && inCurrentScope(ref)) {
                    if (result.size == limit) return@withTimeoutOrNull true
                    result += ref
                }
            }
            false
        } ?: true
        return currentResults(result, partial)
    }

    private suspend fun checkStillReadable(ref: DocumentRef, token: Long) {
        access.recheckAuthorization(token)
        requireCurrentScope(ref)
        if (!validateRef(ref)) throw IllegalArgumentException("This document is no longer available.")
        access.recheckAuthorization(token)
        requireCurrentScope(ref)
    }

    private fun inCurrentScope(ref: DocumentRef): Boolean = if (device.supportsFolders) {
        device.files.value.any { it.uri == ref.uri }
    } else {
        recents.documents.value.any { it.ref.uri == ref.uri }
    }

    private fun requireCurrentScope(ref: DocumentRef) {
        require(inCurrentScope(ref)) { "This document is outside the enabled assistant scope or is no longer available." }
    }

    private fun currentResults(refs: List<DocumentRef>, partial: Boolean): Snapshot {
        val current = refs.filter(::inCurrentScope)
        return Snapshot(current, partial || current.size != refs.size)
    }

    private fun metadata(ref: DocumentRef): AssistantDocument {
        requireCurrentScope(ref)
        return AssistantDocument(opaqueId(ref), ref.displayName.take(512), ref.sizeBytes, ref.modifiedAt)
    }
    private fun resultLimit(value: Int): Int = if (value == 0) MAX_RESULTS else value.coerceIn(1, MAX_RESULTS)

    private companion object {
        const val MAX_RESULTS = 20
        const val MAX_ID_CHARS = 256
        const val MAX_QUERY_CHARS = 256
        const val MAX_LIBRARY_FILES = 7000
        const val MAX_RESULT_CANDIDATES = 100
        const val DEFAULT_EXCERPT_CHARS = 2000
        const val MAX_EXCERPT_CHARS = 4000
        const val MAX_SEARCH_CHARS = 200_000
        const val REFRESH_TIMEOUT_MS = 5000L
        const val RESULT_TIMEOUT_MS = 3000L
        const val TEXT_TIMEOUT_MS = 10_000L
    }
}
