package com.ezfiles.reader.application

import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.domain.CollectionEntry
import com.ezfiles.reader.domain.CollectionKind
import com.ezfiles.reader.domain.CollectionReason
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.IdenticalGroup
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.SimilarGroup
import com.ezfiles.reader.domain.TidyCollection
import com.ezfiles.reader.domain.TidyPhase
import com.ezfiles.reader.domain.TidyProgress
import com.ezfiles.reader.domain.TidyReport
import com.ezfiles.reader.presentation.library.FileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Runs Tidy up scans over the library's folders and keeps their results.
 *
 * Kotlin only orchestrates: it lists files, opens descriptors, asks
 * [DocumentText] for text and [TidyFiles] for small grey previews, and hands
 * everything to [engine] (Rust) for hashing, fingerprints, grouping and
 * classification. Work runs on [io] (on Android a single low-priority
 * thread), one file at a time, so memory stays bounded and the UI is never
 * starved. Nothing starts until the user opens Tidy up.
 *
 * What each file yielded is cached by identity, size and modification time,
 * so a rescan only reads files that are new or changed; the last report is
 * cached too, so reopening the screen is instant.
 */
class TidyService internal constructor(
    private val device: DeviceDocuments,
    private val documentText: DocumentText,
    private val files: TidyFiles,
    private val engine: TidyEngine,
    private val storage: KeyValueStorage,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val limits: TidyLimits = TidyLimits(),
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Mutex()
    private var job: Job? = null
    private var loaded = false
    private var facts: MutableMap<String, FileFacts> = mutableMapOf()

    private val _report = MutableStateFlow<TidyReport?>(null)
    private val _progress = MutableStateFlow<TidyProgress?>(null)
    private val _failure = MutableStateFlow<String?>(null)

    /** The last finished scan, from this session or the cache. */
    val report: StateFlow<TidyReport?> = _report.asStateFlow()

    /** The running scan's step, or `null` when none is running. */
    val progress: StateFlow<TidyProgress?> = _progress.asStateFlow()

    /** Why the last scan stopped early, if it failed. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    val canDelete: Boolean get() = files.canDelete

    /** The library's folders: what a scan looks through. */
    val folders: StateFlow<List<LibraryFolder>> get() = device.folders

    /** Loads the cached report, once. Cheap to call again. */
    suspend fun load() {
        lock.withLock {
            if (loaded) return
            withContext(io) { loadCache() }
            loaded = true
        }
    }

    /** Starts a scan unless one is running. */
    fun scan() {
        if (job?.isActive == true) return
        _failure.value = null
        _progress.value = TidyProgress(TidyPhase.Listing)
        job = scope.launch {
            try {
                load()
                val report = run()
                _report.value = report
                saveCache(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failure.value = "The scan stopped: ${e.message ?: "something went wrong"}."
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun share(ref: DocumentRef): Boolean = files.share(ref)

    /**
     * Deletes [ref], which the user has just confirmed, and takes it out of
     * the results. False if the provider refused; nothing else changes then.
     */
    suspend fun delete(ref: DocumentRef): Boolean = lock.withLock {
        if (!files.canDelete || _progress.value != null) return@withLock false
        val group = _report.value?.identical?.firstOrNull { group ->
            group.files.drop(1).any { it.identity == ref.identity }
        } ?: return@withLock false
        val retained = group.files.first()
        val deleted = withContext(io) { files.deleteDuplicate(ref, retained) }
        if (!deleted) return@withLock false
        val current = _report.value
        if (current != null) {
            val updated = current.without(ref)
            _report.value = updated
            withContext(io) { saveCache(updated) }
        }
        // The library lists it until its next scan; ask for one now.
        scope.launch { device.refresh() }
        true
    }

    private suspend fun run(): TidyReport {
        device.refresh()
        val all = device.files.value
        currentCoroutineContext().ensureActive()

        val seen = HashMap<String, FileFacts>(all.size)
        fun factsOf(ref: DocumentRef): FileFacts {
            val key = keyOf(ref)
            return seen.getOrPut(key) { facts[key]?.copy() ?: FileFacts(readVersion = FACTS_VERSION) }
        }

        // 1. Identical files, all types.
        val hashable = all.filter { (it.sizeBytes ?: 0) > 0 }
        _progress.value = TidyProgress(TidyPhase.Identical, 0, hashable.size)
        val scanJob = currentCoroutineContext().job
        val hashes = engine.findIdentical(
            hashable.map { ref -> factsOf(ref).let { HashInput(ref.sizeBytes ?: 0, it.partial, it.full) } },
            object : HashSource {
                override fun open(index: Int): Int? = files.openDescriptor(hashable[index])

                override fun onProgress(done: Int, total: Int) {
                    _progress.value = TidyProgress(TidyPhase.Identical, done, total)
                }

                override fun isCancelled(): Boolean = !scanJob.isActive
            },
        )
        hashable.forEachIndexed { i, ref ->
            val f = factsOf(ref)
            f.partial = hashes.partial.getOrNull(i)
            f.full = hashes.full.getOrNull(i)
        }
        val identical = hashes.groups.map { group ->
            val members = group.map { hashable[it] }
            IdenticalGroup(members, members.first().sizeBytes ?: 0)
        }
        // Extra copies add nothing to "similar": one member of each identical group stands for it.
        val extras = identical.flatMap { it.files.drop(1) }.map { it.identity }.toSet()

        // 2. Documents: one text pass gives the fingerprint and the collection.
        val documents = all.filter { isTextual(it) && (it.sizeBytes ?: 0) <= limits.maxTextFileBytes }.take(limits.maxDocuments)
        documents.forEachIndexed { i, ref ->
            currentCoroutineContext().ensureActive()
            _progress.value = TidyProgress(TidyPhase.Documents, i, documents.size)
            val f = factsOf(ref)
            if (!f.textRead) {
                val text = try {
                    documentText.plainText(ref, limits.maxTextChars)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (text.isNullOrBlank()) {
                    f.simhash = null
                    f.collection = engine.classifyName(ref.displayName)?.let(::StoredMatch)
                } else {
                    val result = engine.analyzeText(ref.displayName, text)
                    f.simhash = result.fingerprint
                    f.collection = result.collection?.let(::StoredMatch)
                }
                // A provider/capability failure is retryable on the next explicit scan.
                f.textRead = !text.isNullOrBlank()
            }
            yield()
        }

        // 3. Photos: a 32x32 grey preview each, fingerprinted in Rust.
        val photos = all.filter { FileType.of(it.displayName, null) == FileType.Image }.take(limits.maxPhotos)
        photos.forEachIndexed { i, ref ->
            currentCoroutineContext().ensureActive()
            _progress.value = TidyProgress(TidyPhase.Photos, i, photos.size)
            val f = factsOf(ref)
            if (!f.imageRead) {
                val preview = try {
                    files.grayThumbnail(ref, THUMBNAIL_SIDE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                f.dhash = preview?.let { engine.imageFingerprint(it.width, it.height, it.pixels) }
                f.collection = engine.classifyName(ref.displayName)?.let(::StoredMatch)
                f.imageRead = preview != null
            }
            yield()
        }

        // 4. Grouping, all in Rust.
        _progress.value = TidyProgress(TidyPhase.Grouping)
        val similarDocuments = similar(documents.filter { it.identity !in extras }, images = false) { factsOf(it).simhash }
        val similarPhotos = similar(photos.filter { it.identity !in extras }, images = true) { factsOf(it).dhash }
        // Filename rules need no content read, including files beyond the read budgets.
        val collectionCandidates = all.filter { isTextual(it) || FileType.of(it.displayName, null) == FileType.Image }
        collectionCandidates.forEach { ref ->
            currentCoroutineContext().ensureActive()
            val f = factsOf(ref)
            if (f.collection == null) f.collection = engine.classifyName(ref.displayName)?.let(::StoredMatch)
        }
        val collections = collectionCandidates
            .mapNotNull { ref -> factsOf(ref).collection?.let { ref to it } }
            .groupBy { it.second.kind }
            .map { (kind, entries) -> TidyCollection(kind, entries.map { (ref, m) -> CollectionEntry(ref, m.reason()) }) }
            .sortedBy { it.kind.ordinal }

        facts = seen
        return TidyReport(clock(), all.size, identical, similarDocuments, similarPhotos, collections)
    }

    private fun similar(refs: List<DocumentRef>, images: Boolean, fingerprint: (DocumentRef) -> Long?): List<SimilarGroup> {
        val withPrints = refs.mapNotNull { ref -> fingerprint(ref)?.let { ref to it } }
        if (withPrints.size < 2) return emptyList()
        return engine.groupSimilar(withPrints.map { it.second }, images).map { group -> SimilarGroup(group.map { withPrints[it].first }) }
    }

    private fun isTextual(ref: DocumentRef): Boolean = when (FileType.of(ref.displayName, null)) {
        FileType.Image, FileType.Video, FileType.Audio, FileType.Zip, FileType.Other -> false
        else -> true
    }

    private fun loadCache() {
        try {
            storage.get(FACTS_KEY)?.let { stored -> facts = json.decodeFromString<Map<String, FileFacts>>(stored).toMutableMap() }
            // Reclassify once when collection rules change, retaining exact hashes.
            // Version zero also needs recovery from permanently cached failed previews.
            facts.values.forEach { f ->
                if (f.readVersion < FACTS_VERSION) {
                    if (f.readVersion == 0 && f.dhash == null) f.imageRead = false
                    f.textRead = false
                    f.collection = null
                    f.readVersion = FACTS_VERSION
                }
            }
            storage.get(REPORT_KEY)?.let { stored -> _report.value = json.decodeFromString<StoredReport>(stored).toDomain() }
        } catch (e: SerializationException) {
            // From another version, or damaged: the next scan rebuilds it.
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun saveCache(report: TidyReport) {
        storage.put(REPORT_KEY, json.encodeToString(StoredReport.of(report)))
        storage.put(FACTS_KEY, json.encodeToString<Map<String, FileFacts>>(facts))
    }

    private fun keyOf(ref: DocumentRef) = "${ref.identity}|${ref.sizeBytes}|${ref.modifiedAt}"

    private companion object {
        const val REPORT_KEY = "tidy_report_v1"
        const val FACTS_KEY = "tidy_facts_v1"
        const val FACTS_VERSION = 2
        const val THUMBNAIL_SIDE = 32
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Budgets for one scan, sized for a 2 GB phone. */
internal data class TidyLimits(
    /** Characters of text read per document: enough to fingerprint and classify it. */
    val maxTextChars: Int = 60_000,
    /** Larger documents skip content analysis; filename collection rules still apply. */
    val maxTextFileBytes: Long = 64L * 1024 * 1024,
    /** The newest this many documents are read. */
    val maxDocuments: Int = 2_000,
    val maxPhotos: Int = 3_000,
)

/** What one file yielded, cached between scans. */
@Serializable
internal data class FileFacts(
    var partial: Long? = null,
    var full: Long? = null,
    var simhash: Long? = null,
    var dhash: Long? = null,
    var collection: StoredMatch? = null,
    var textRead: Boolean = false,
    var imageRead: Boolean = false,
    /** Content/classification revision; zero denotes the legacy failed-read cache. */
    var readVersion: Int = 0,
)

@Serializable
internal data class StoredMatch(
    val kind: CollectionKind,
    val terms: List<String> = emptyList(),
    val nameTerms: List<String> = emptyList(),
    val amounts: Int = 0,
    val dates: Int = 0,
) {
    constructor(match: CollectionMatch) : this(match.kind, match.reason.terms, match.reason.nameTerms, match.reason.amounts, match.reason.dates)

    fun reason() = CollectionReason(terms, nameTerms, amounts, dates)
}

@Serializable
private data class StoredRef(val uri: String, val name: String, val size: Long? = null, val modified: Long? = null, val identity: String? = null) {
    fun toDomain() = DocumentRef(uri, name, size, modified, identity ?: uri)

    companion object {
        fun of(ref: DocumentRef) = StoredRef(ref.uri, ref.displayName, ref.sizeBytes, ref.modifiedAt, ref.identity.takeIf { it != ref.uri })
    }
}

@Serializable
private data class StoredEntry(val file: StoredRef, val match: StoredMatch)

@Serializable
private data class StoredReport(
    val scannedAt: Long,
    val fileCount: Int,
    val identical: List<List<StoredRef>>,
    val similarDocuments: List<List<StoredRef>>,
    val similarPhotos: List<List<StoredRef>>,
    val collections: List<StoredEntry>,
) {
    fun toDomain() = TidyReport(
        scannedAt = scannedAt,
        fileCount = fileCount,
        identical = identical.map { group -> group.map { it.toDomain() }.let { IdenticalGroup(it, it.first().sizeBytes ?: 0) } },
        similarDocuments = similarDocuments.map { group -> SimilarGroup(group.map { it.toDomain() }) },
        similarPhotos = similarPhotos.map { group -> SimilarGroup(group.map { it.toDomain() }) },
        collections = collections.groupBy { it.match.kind }
            .map { (kind, entries) -> TidyCollection(kind, entries.map { CollectionEntry(it.file.toDomain(), it.match.reason()) }) }
            .sortedBy { it.kind.ordinal },
    )

    companion object {
        fun of(report: TidyReport) = StoredReport(
            scannedAt = report.scannedAt,
            fileCount = report.fileCount,
            identical = report.identical.map { group -> group.files.map(StoredRef::of) },
            similarDocuments = report.similarDocuments.map { group -> group.files.map(StoredRef::of) },
            similarPhotos = report.similarPhotos.map { group -> group.files.map(StoredRef::of) },
            collections = report.collections.flatMap { collection ->
                collection.entries.map { StoredEntry(StoredRef.of(it.file), StoredMatch(collection.kind, it.reason.terms, it.reason.nameTerms, it.reason.amounts, it.reason.dates)) }
            },
        )
    }
}

/** [this] without [ref]: groups left with one file are dropped. */
internal fun TidyReport.without(ref: DocumentRef): TidyReport {
    fun List<DocumentRef>.minus() = filterNot { it.identity == ref.identity }
    return copy(
        identical = identical.map { it.copy(files = it.files.minus()) }.filter { it.files.size > 1 },
        similarDocuments = similarDocuments.map { SimilarGroup(it.files.minus()) }.filter { it.files.size > 1 },
        similarPhotos = similarPhotos.map { SimilarGroup(it.files.minus()) }.filter { it.files.size > 1 },
        collections = collections.map { c -> c.copy(entries = c.entries.filterNot { it.file.identity == ref.identity }) }.filter { it.entries.isNotEmpty() },
    )
}
