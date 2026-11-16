package com.ezfiles.reader.data

import com.ezfiles.reader.domain.ContentHit
import com.ezfiles.reader.domain.ContentSearch
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.IndexProgress
import com.ezfiles.reader.domain.PagedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Search inside the library's files, over a [FullTextIndex] built in the
 * background from [DeviceDocuments.files].
 *
 * Indexing is lazy and gentle: nothing happens until the library is on screen
 * ([setActive]), and then only after [startDelayMillis] of it being there, or
 * at once when the user searches. It pauses whenever the library is left
 * (opening a document cancels it), takes one file at a time on [indexing] (a
 * single low-priority thread on Android), reads at most [maxChars] of each,
 * and saves every few files. Only new or changed files (by size and
 * modification time) are read again; removed ones are dropped. Queries run on
 * [queries], so they never wait behind a file being read.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class LibraryContentSearch(
    private val device: DeviceDocuments,
    private val text: suspend (DocumentRef, Int) -> PagedText?,
    private val openIndex: () -> FullTextIndex,
    private val indexing: CoroutineDispatcher,
    private val queries: CoroutineDispatcher,
    private val startDelayMillis: Long = 5_000,
    private val pauseMillis: Long = 15,
    private val maxChars: Int = 100_000,
) : ContentSearch {
    // Background indexing must never take the app down; failures are handled where they happen.
    private val scope = CoroutineScope(SupervisorJob() + indexing + CoroutineExceptionHandler { _, _ -> })
    private val active = MutableStateFlow(false)
    /** The user searched: index now rather than after the delay. */
    private val wanted = MutableStateFlow(false)
    /** After [clear], the index is rebuilt only once the user searches again. */
    private val automatic = MutableStateFlow(true)
    /** Bumped to cancel a pass in progress. */
    private val generation = MutableStateFlow(0)
    private val _progress = MutableStateFlow(IndexProgress())
    override val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    private val openLock = Mutex()
    private var index: FullTextIndex? = null
    /** Held for a whole pass, so [clear] waits for one being cancelled to finish. */
    private val passLock = Mutex()

    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        // Files settle first: a scan publishes folder by folder.
        combine(device.files.debounce(FILES_SETTLE_MILLIS), device.scanning, active, wanted, automatic) { files, scanning, on, now, auto ->
            Run(files, scanning, on, now, auto)
        }.combine(generation) { run, _ -> run }.collectLatest { run ->
            // A scan in progress may not have listed every folder yet: wait for it.
            if (!run.active || run.scanning) return@collectLatest
            if (!run.wanted) {
                if (!run.automatic) return@collectLatest
                delay(startDelayMillis)
            }
            try {
                passLock.withLock { pass(run.files) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Out of space, or the index can't be written: search keeps what it has, names still work.
            }
        }
    }

    private data class Run(val files: List<DocumentRef>, val scanning: Boolean, val active: Boolean, val wanted: Boolean, val automatic: Boolean)

    override fun setActive(active: Boolean) {
        this.active.value = active
        if (active) worker.start() else wanted.value = false
    }

    override suspend fun search(query: String, limit: Int): List<ContentHit> {
        if (query.isBlank()) return emptyList()
        automatic.value = true
        wanted.value = true
        worker.start()
        val index = index()
        return withContext(queries) {
            val hits = index.query(query, limit)
            if (hits.isEmpty()) return@withContext emptyList()
            val wanted = hits.mapTo(HashSet()) { it.key }
            val byKey = HashMap<String, DocumentRef>(wanted.size)
            // Resolve only returned keys against the current authorized snapshot.
            // Scan all entries so duplicate identities retain associateBy's last-wins behavior.
            var checked = 0
            for (ref in device.files.value) {
                if (checked++ % 256 == 0) ensureActive()
                if (ref.identity in wanted) byKey[ref.identity] = ref
            }
            hits.mapNotNull { hit -> byKey[hit.key]?.let { ContentHit(it, hit.snippet, hit.highlights, hit.page) } }
        }
    }

    override suspend fun clear() {
        automatic.value = false
        wanted.value = false
        generation.update { it + 1 }
        passLock.withLock {
            withContext(queries) { index().clear() }
            _progress.value = IndexProgress()
        }
    }

    /** Opened on first use, never at start-up: loading it reads a file. */
    private suspend fun index(): FullTextIndex = openLock.withLock {
        index ?: withContext(queries) { openIndex() }.also { index = it }
    }

    private suspend fun pass(files: List<DocumentRef>) {
        val index = index()
        val docs = files.filter(::indexable)
        val entries = docs.map { IndexEntry(it.identity, it.sizeBytes ?: 0, it.modifiedAt ?: 0) }
        index.retain(entries.map { it.key })
        val stale = index.stale(entries)
        var done = docs.size - stale.size
        _progress.value = IndexProgress(done, docs.size, running = stale.isNotEmpty())
        var unsaved = 0
        try {
            for (position in stale) {
                if (!index.hasRoom()) break
                val text = try {
                    text(docs[position], maxChars)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // A read cut short by cancellation must not be recorded as an empty file.
                currentCoroutineContext().ensureActive()
                if (!index.add(entries[position], text?.text.orEmpty(), text?.pageStarts.orEmpty())) break
                done++
                _progress.value = IndexProgress(done, docs.size, running = true)
                if (++unsaved >= SAVE_EVERY) {
                    index.save()
                    unsaved = 0
                }
                yield()
                delay(pauseMillis)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    index.save()
                } catch (e: Exception) {
                    // Out of space, say: the next pass tries again.
                }
                _progress.update { it.copy(running = false) }
            }
        }
    }

    private fun indexable(ref: DocumentRef): Boolean {
        val extension = ref.displayName.substringAfterLast('.', "").lowercase()
        return extension !in SKIPPED && (ref.sizeBytes ?: 0) <= MAX_FILE_BYTES
    }

    private companion object {
        const val FILES_SETTLE_MILLIS = 1_000L
        const val SAVE_EVERY = 8
        /** Larger files take long to read and rarely are what a text search is after. */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        /** Pictures, media and archives: no text worth indexing. */
        val SKIPPED = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg",
            "mp4", "m4v", "mov", "3gp", "3g2", "webm", "mkv",
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi",
            "zip",
        )
    }
}
