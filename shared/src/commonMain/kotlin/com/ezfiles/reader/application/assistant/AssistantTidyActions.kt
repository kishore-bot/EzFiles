package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.TidyReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Cached aggregate results only: no filenames, snippets, paths or collection classifications. */
data class AssistantTidySummary(
    val available: Boolean,
    val scannedAt: Long?,
    /** Lower bound of cached report members whose current access and reported version were validated. */
    val validatedFileCount: Int,
    val duplicateGroups: Int,
    val similarDocumentGroups: Int,
    val similarPhotoGroups: Int,
    val reclaimableBytes: Long,
    /** True when filtering, stale/unknown metadata, provider failures or a work limit reduced certainty. */
    val partial: Boolean,
)

/** Reading a cached report never starts a Tidy scan or returns its private classification reasons. */
class AssistantTidyActions internal constructor(
    private val access: AssistantAccess,
    private val device: DeviceDocuments,
    private val loadReport: suspend () -> TidyReport?,
    private val validateRef: suspend (DocumentRef) -> Boolean,
) {
    suspend fun getReport(): AssistantTidySummary = access.authorized(content = true) { token ->
        val refreshed = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { device.refresh(); true } == true
        access.recheckAuthorization(token)
        val beforeLoad = device.files.value
        var loaded = false
        val report = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadReport().also { loaded = true } }
        access.recheckAuthorization(token)
        var partial = !refreshed || !loaded || device.scanning.value || device.limitReached.value || beforeLoad != device.files.value
        if (report == null) return@authorized AssistantTidySummary(false, null, 0, 0, 0, 0, 0, partial)

        var scopeRefs = device.files.value
        var scope = scopeRefs.take(MAX_SCOPE_FILES).associateBy { it.uri }
        partial = partial || scopeRefs.size > MAX_SCOPE_FILES
        fun currentScope(): Map<String, DocumentRef> {
            val next = device.files.value
            if (next !== scopeRefs) {
                partial = true
                scopeRefs = next
                scope = next.take(MAX_SCOPE_FILES).associateBy { it.uri }
            }
            return scope
        }
        fun sameVersion(cached: DocumentRef, current: DocumentRef): Boolean {
            if (cached.sizeBytes == null || cached.modifiedAt == null) partial = true
            return (cached.sizeBytes == null || cached.sizeBytes == current.sizeBytes) &&
                (cached.modifiedAt == null || cached.modifiedAt == current.modifiedAt)
        }

        val checked = hashSetOf<String>()
        val validated = linkedMapOf<String, DocumentRef>()
        var visited = 0
        suspend fun members(refs: Sequence<DocumentRef>): List<String> {
            val result = linkedSetOf<String>()
            for (ref in refs) {
                currentCoroutineContext().ensureActive()
                if (visited++ >= MAX_REPORT_MEMBERS) { partial = true; break }
                val current = currentScope()[ref.uri]
                if (current == null || !sameVersion(ref, current)) { partial = true; continue }
                if (ref.uri !in checked) {
                    if (checked.size == MAX_VALIDATIONS) { partial = true; continue }
                    checked += ref.uri
                    val readable = try {
                        validateRef(current)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    access.recheckAuthorization(token)
                    val after = currentScope()[ref.uri]
                    if (readable && after != null && sameVersion(current, after)) validated[ref.uri] = after
                    else partial = true
                }
                val accepted = validated[ref.uri]
                if (accepted != null && sameVersion(accepted, currentScope()[ref.uri] ?: run { partial = true; continue })) {
                    if (!result.add(ref.uri)) partial = true
                } else partial = true
            }
            return result.toList()
        }

        val identical = mutableListOf<Pair<List<String>, Long>>()
        val similarDocuments = mutableListOf<List<String>>()
        val similarPhotos = mutableListOf<List<String>>()
        val completed = withTimeoutOrNull(WORK_TIMEOUT_MS) {
            if (report.identical.size > MAX_GROUPS || report.similarDocuments.size > MAX_GROUPS ||
                report.similarPhotos.size > MAX_GROUPS || report.collections.size > MAX_GROUPS) partial = true
            report.identical.take(MAX_GROUPS).forEach { identical += members(it.files.asSequence()) to it.sizeBytes }
            report.similarDocuments.take(MAX_GROUPS).forEach { similarDocuments += members(it.files.asSequence()) }
            report.similarPhotos.take(MAX_GROUPS).forEach { similarPhotos += members(it.files.asSequence()) }
            report.collections.take(MAX_GROUPS).forEach { members(it.entries.asSequence().map { entry -> entry.file }) }
            true
        } == true
        access.recheckAuthorization(token)
        val finalScope = currentScope()
        val allowed = validated.filter { (uri, ref) -> finalScope[uri]?.let { sameVersion(ref, it) } == true }.keys
        partial = partial || !completed || allowed.size != validated.size || allowed.size != report.fileCount ||
            device.scanning.value || device.limitReached.value
        val usedIdentical = hashSetOf<String>()
        var duplicateGroups = 0
        var reclaimable = 0L
        for ((uris, size) in identical) {
            val kept = uris.filter { it in allowed }
            if (kept.size < uris.size) partial = true
            if (kept.size < 2) continue
            if (size < 0 || kept.any { it in usedIdentical || finalScope[it]?.sizeBytes?.let { actual -> actual != size } == true }) {
                partial = true
                continue
            }
            usedIdentical += kept
            duplicateGroups++
            val copies = kept.size - 1L
            if (size > (Long.MAX_VALUE - reclaimable) / copies) {
                reclaimable = Long.MAX_VALUE
                partial = true
            } else reclaimable += size * copies
        }
        fun groupCount(groups: List<List<String>>): Int = groups.map { it.filter { uri -> uri in allowed }.toSet() }
            .filter { it.size >= 2 }.distinct().size
        AssistantTidySummary(true, report.scannedAt, allowed.size, duplicateGroups, groupCount(similarDocuments),
            groupCount(similarPhotos), reclaimable, partial)
    }

    private companion object {
        const val MAX_SCOPE_FILES = 7000
        const val MAX_REPORT_MEMBERS = 7000
        const val MAX_VALIDATIONS = 100
        const val MAX_GROUPS = 100
        const val REFRESH_TIMEOUT_MS = 5000L
        const val LOAD_TIMEOUT_MS = 5000L
        const val WORK_TIMEOUT_MS = 3000L
    }
}
