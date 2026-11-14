package com.ezfiles.reader.tidy

import com.ezfiles.reader.application.CollectionMatch
import com.ezfiles.reader.application.GrayImage
import com.ezfiles.reader.application.HashInput
import com.ezfiles.reader.application.HashSource
import com.ezfiles.reader.application.IdenticalHashes
import com.ezfiles.reader.application.SensitiveCheck
import com.ezfiles.reader.application.TextFacts
import com.ezfiles.reader.application.TidyEngine
import com.ezfiles.reader.application.TidyFiles
import com.ezfiles.reader.application.TidyLimits
import com.ezfiles.reader.application.TidyService
import com.ezfiles.reader.application.without
import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.domain.CollectionKind
import com.ezfiles.reader.domain.CollectionReason
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.SensitiveFindings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TidyServiceTest {
    private fun ref(name: String, size: Long = 100, modified: Long = 1) = DocumentRef("content://$name", name, size, modified)

    private class World(files: List<DocumentRef>) {
        /** What each file "contains", for hashing and text. */
        val contents = mutableMapOf<String, String>()
        val device = FakeDevice(files)

        class FakeDevice(files: List<DocumentRef>) : DeviceDocuments {
            override val supportsFolders = true
            override val folders = MutableStateFlow(listOf(LibraryFolder("tree", "Docs")))
            override val files = MutableStateFlow(files)
            override val scanning = MutableStateFlow(false)
            override val limitReached = MutableStateFlow(false)
            var refreshes = 0

            override suspend fun refresh() {
                refreshes++
            }

            override suspend fun addFolder(uri: String) = true

            override suspend fun removeFolder(uri: String) = Unit
        }
        val textReads = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val text = object : DocumentText {
            override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? {
                gate?.await()
                textReads += ref.displayName
                return contents[ref.uri]
            }
        }
        val opened = mutableListOf<String>()
        val imageReads = mutableListOf<String>()
        val unreadableImages = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        var refuseDelete = false
        var lastRefs: List<DocumentRef> = emptyList()
        val tidyFiles = object : TidyFiles {
            override fun openDescriptor(ref: DocumentRef): Int? {
                opened += ref.displayName
                return 3
            }

            override fun grayThumbnail(ref: DocumentRef, side: Int): GrayImage? {
                imageReads += ref.displayName
                if (ref.uri in unreadableImages) return null
                return GrayImage(side, side, ByteArray(side * side) { (contents[ref.uri]?.length ?: 0).toByte() })
            }

            override fun share(ref: DocumentRef) = true

            override val canDelete = true

            override fun deleteDuplicate(ref: DocumentRef, retained: DocumentRef): Boolean =
                contents[ref.uri] != null && contents[ref.uri] == contents[retained.uri] && delete(ref)

            override fun delete(ref: DocumentRef): Boolean {
                if (refuseDelete) return false
                deleted += ref.displayName
                return true
            }
        }

        /** Groups by content; fingerprints are the content's length, so "near" means same length. */
        val engine = object : TidyEngine {
            override fun findIdentical(files: List<HashInput>, source: HashSource): IdenticalHashes {
                val refs = lastRefs.filter { (it.sizeBytes ?: 0) > 0 }
                val hashes = files.mapIndexed { i, input ->
                    input.full ?: run {
                        source.open(i)
                        contents[refs[i].uri]?.hashCode()?.toLong()
                    }
                }
                val groups = hashes.indices.filter { hashes[it] != null }.groupBy { hashes[it] to files[it].size }.values.filter { it.size > 1 }
                return IdenticalHashes(groups, hashes, hashes)
            }

            override fun analyzeText(name: String, text: String) = TextFacts(
                fingerprint = text.length.toLong().takeIf { text.length > 5 },
                collection = if ("invoice" in text) CollectionMatch(CollectionKind.Receipts, CollectionReason(terms = listOf("invoice"))) else null,
            )

            override fun classifyName(name: String): CollectionMatch? =
                if ("ticket" in name) CollectionMatch(CollectionKind.Travel, CollectionReason(nameTerms = listOf("ticket"))) else null

            override fun imageFingerprint(width: Int, height: Int, gray: ByteArray): Long? = gray[0].toLong().takeIf { it != 0L }

            override fun groupSimilar(fingerprints: List<Long>, images: Boolean): List<List<Int>> =
                fingerprints.indices.groupBy { fingerprints[it] }.values.filter { it.size > 1 }

            override fun sensitive(text: String) = SensitiveFindings(paymentCard = "4111" in text)
        }
        val storage = MemoryStorage()

        fun service(scope: TestScope, limits: TidyLimits = TidyLimits()) = TidyService(device, text, tidyFiles, engine, storage, StandardTestDispatcher(scope.testScheduler), { 42L }, limits).also {
            lastRefs = device.files.value
        }
    }

    class MemoryStorage : KeyValueStorage {
        val values = mutableMapOf<String, String>()

        override fun get(key: String) = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun library(): Pair<World, List<DocumentRef>> {
        val a = ref("report_final.docx")
        val b = ref("report_final2.docx", size = 101)
        val c = ref("copy of report_final.docx")
        val invoice = ref("scan.pdf", size = 300)
        val ticket = ref("train ticket.jpg", size = 500)
        val world = World(listOf(a, b, c, invoice, ticket))
        world.contents[a.uri] = "the quarterly report text"
        world.contents[c.uri] = "the quarterly report text"
        world.contents[b.uri] = "the quarterly report TEXT" // same length: "similar"
        world.contents[invoice.uri] = "invoice total 12.00"
        world.contents[ticket.uri] = "pixels"
        return world to listOf(a, b, c, invoice, ticket)
    }

    @Test
    fun a_scan_finds_copies_look_alikes_and_collections() = runTest {
        val (world, refs) = library()
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        val report = assertNotNull(service.report.value)
        assertNull(service.progress.value)
        assertEquals(5, report.fileCount)
        assertEquals(listOf(listOf(refs[0], refs[2])), report.identical.map { it.files })
        assertEquals(100L, report.reclaimableBytes)
        // The extra copy (refs[2]) stands in for nothing: only the original and the edit are "similar".
        assertEquals(listOf(listOf(refs[0], refs[1])), report.similarDocuments.map { it.files })
        assertEquals(listOf(CollectionKind.Receipts, CollectionKind.Travel), report.collections.map { it.kind })
        assertEquals(refs[3], report.collections[0].entries.single().file)
        assertEquals(1, world.device.refreshes)
        // Files with a size no other file has are never opened... (the fake engine opens all; the real one doesn't)
        assertTrue(world.opened.isNotEmpty())
    }

    @Test
    fun a_rescan_reads_only_new_or_changed_files() = runTest {
        val (world, refs) = library()
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        world.textReads.clear()
        world.opened.clear()
        world.device.files.value = refs.map { if (it == refs[3]) it.copy(modifiedAt = 2) else it }
        world.lastRefs = world.device.files.value
        service.scan()
        advanceUntilIdle()
        assertEquals(listOf("scan.pdf"), world.textReads)
        assertEquals(listOf("scan.pdf"), world.opened)
    }

    @Test
    fun the_last_report_comes_back_from_the_cache_without_a_scan() = runTest {
        val (world, _) = library()
        val first = world.service(this)
        first.scan()
        advanceUntilIdle()
        val again = world.service(this)
        again.load()
        assertEquals(first.report.value, again.report.value)
        assertEquals(1, world.device.refreshes)
    }

    @Test
    fun failed_reads_are_retried_without_file_metadata_changes() = runTest {
        val docs = listOf(ref("first.txt", 101), ref("second.txt", 102))
        val photos = listOf(ref("first.jpg", 201), ref("second.jpg", 202))
        val world = World(docs + photos)
        world.unreadableImages += photos.map { it.uri }
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        assertTrue(assertNotNull(service.report.value).isEmpty)

        world.contents[docs[0].uri] = "invoice alpha"
        world.contents[docs[1].uri] = "invoice bravo"
        world.contents[photos[0].uri] = "pixelsA"
        world.contents[photos[1].uri] = "pixelsB"
        world.unreadableImages.clear()
        service.scan()
        advanceUntilIdle()
        val recovered = assertNotNull(service.report.value)
        assertEquals(listOf(docs), recovered.similarDocuments.map { it.files })
        assertEquals(listOf(photos), recovered.similarPhotos.map { it.files })
        assertEquals(docs, recovered.collections.single().entries.map { it.file })

        world.textReads.clear()
        world.imageReads.clear()
        service.scan()
        advanceUntilIdle()
        assertTrue(world.textReads.isEmpty())
        assertTrue(world.imageReads.isEmpty())
    }

    @Test
    fun legacy_cached_failures_retry_but_keep_exact_hashes() = runTest {
        val doc = ref("train ticket.txt")
        val photo = ref("photo.jpg", 200)
        val world = World(listOf(doc, photo))
        world.contents[doc.uri] = "short"
        world.storage.values["tidy_facts_v1"] = """{
            "${doc.identity}|100|1":{"textRead":true,"full":17},
            "${photo.identity}|200|1":{"imageRead":true,"full":18}
        }"""
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        assertEquals(listOf(doc.displayName), world.textReads)
        assertEquals(listOf(photo.displayName), world.imageReads)
        assertTrue(world.opened.isEmpty())
        assertEquals(doc, assertNotNull(service.report.value).collections.single().entries.single().file)

        // Both successful reads intentionally yielded no fingerprint. Migration runs once.
        world.textReads.clear()
        world.imageReads.clear()
        world.service(this).scan()
        advanceUntilIdle()
        assertTrue(world.textReads.isEmpty())
        assertTrue(world.imageReads.isEmpty())
    }

    @Test
    fun filename_collections_do_not_require_a_content_read_budget() = runTest {
        val docs = listOf(ref("train ticket.pdf", 65L * 1024 * 1024), ref("train ticket.jpg"))
        val world = World(docs)
        val service = world.service(this, TidyLimits(maxDocuments = 0, maxPhotos = 0))
        service.scan()
        advanceUntilIdle()
        assertEquals(docs, assertNotNull(service.report.value).collections.single().entries.map { it.file })
        assertTrue(world.textReads.isEmpty())
        assertTrue(world.imageReads.isEmpty())
    }

    @Test
    fun successful_short_text_and_flat_photos_stay_cached_after_reopening() = runTest {
        val doc = ref("short.txt")
        val photo = ref("flat.jpg", 200)
        val world = World(listOf(doc, photo))
        world.contents[doc.uri] = "short" // Successful text read, too short for a fingerprint.
        // An empty synthetic pixel source gives a valid preview with no fingerprint.
        val first = world.service(this)
        first.scan()
        advanceUntilIdle()
        world.textReads.clear()
        world.imageReads.clear()

        val reopened = world.service(this)
        reopened.scan()
        advanceUntilIdle()
        assertTrue(world.textReads.isEmpty())
        assertTrue(world.imageReads.isEmpty())
        assertTrue(assertNotNull(reopened.report.value).isEmpty)
    }

    @Test
    fun new_collection_rules_reclassify_old_successful_facts_once() = runTest {
        val doc = ref("scan.pdf")
        val world = World(listOf(doc))
        world.contents[doc.uri] = "invoice total"
        world.storage.values["tidy_facts_v1"] = """{
            "${doc.identity}|100|1":{
                "readVersion":1,"textRead":true,"simhash":22,"full":17,
                "collection":{"kind":"Travel"}
            }
        }"""
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        assertEquals(CollectionKind.Receipts, assertNotNull(service.report.value).collections.single().kind)
        assertEquals(listOf(doc.displayName), world.textReads)
        assertTrue(world.opened.isEmpty())

        world.textReads.clear()
        world.service(this).scan()
        advanceUntilIdle()
        assertTrue(world.textReads.isEmpty())
    }

    @Test
    fun cancelling_keeps_the_previous_report() = runTest {
        val (world, _) = library()
        val service = world.service(this)
        world.gate = CompletableDeferred()
        service.scan()
        advanceUntilIdle()
        assertNotNull(service.progress.value)
        service.cancel()
        advanceUntilIdle()
        assertNull(service.progress.value)
        assertNull(service.report.value)
        assertNull(service.failure.value)
    }

    @Test
    fun deleting_takes_the_file_out_of_every_group() = runTest {
        val (world, refs) = library()
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        assertTrue(service.delete(refs[2]))
        advanceUntilIdle()
        assertEquals(listOf("copy of report_final.docx"), world.deleted)
        assertTrue(service.report.value!!.identical.isEmpty())
        assertEquals(2, world.device.refreshes)

        world.refuseDelete = true
        val before = service.report.value
        assertFalse(service.delete(refs[0]))
        assertEquals(before, service.report.value)
    }

    @Test
    fun deleting_kept_copy_is_refused() = runTest {
        val (world, _) = library()
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        val kept = service.report.value!!.identical.first().files.first()
        assertFalse(service.delete(kept))
        assertTrue(world.deleted.isEmpty())
    }

    @Test
    fun changed_duplicate_is_not_deleted_using_a_cached_scan() = runTest {
        val (world, _) = library()
        val service = world.service(this)
        service.scan()
        advanceUntilIdle()
        val extra = service.report.value!!.identical.first().files[1]
        world.contents[extra.uri] = "changed since scanning"
        assertFalse(service.delete(extra))
        assertTrue(world.deleted.isEmpty())
    }

    @Test
    fun a_report_without_a_file_drops_groups_left_alone() {
        val (_, refs) = library()
        val report = com.ezfiles.reader.domain.TidyReport(
            1, 5,
            identical = listOf(com.ezfiles.reader.domain.IdenticalGroup(listOf(refs[0], refs[2]), 100)),
            similarDocuments = listOf(com.ezfiles.reader.domain.SimilarGroup(listOf(refs[0], refs[1], refs[2]))),
            similarPhotos = emptyList(),
            collections = emptyList(),
        )
        val without = report.without(refs[2])
        assertTrue(without.identical.isEmpty())
        assertEquals(listOf(refs[0], refs[1]), without.similarDocuments.single().files)
    }

    @Test
    fun reasons_and_findings_read_naturally() {
        assertEquals("Mentions ‘invoice’, ‘subtotal’ · 3 amounts · 1 date", CollectionReason(listOf("invoice", "subtotal"), amounts = 3, dates = 1).describe())
        assertEquals("Named ‘ticket’", CollectionReason(nameTerms = listOf("ticket")).describe())
        assertEquals("a card number", SensitiveFindings(paymentCard = true).describe())
        assertEquals("a card number, an ID number and a bank account number", SensitiveFindings(true, true, true).describe())
        assertFalse(SensitiveFindings().any)
    }

    @Test
    fun the_sensitive_check_is_cached_per_file_version() = runTest {
        val (world, refs) = library()
        world.contents[refs[3].uri] = "card 4111 1111 1111 1111"
        val check = SensitiveCheck(world.text, world.engine, world.storage, StandardTestDispatcher(testScheduler))
        assertEquals(true, check.check(refs[3])?.paymentCard)
        assertEquals(true, check.check(refs[3])?.paymentCard)
        assertEquals(1, world.textReads.size)
        // A fresh instance reads the same cache from storage.
        val again = SensitiveCheck(world.text, world.engine, world.storage, StandardTestDispatcher(testScheduler))
        assertEquals(true, again.check(refs[3])?.paymentCard)
        assertEquals(1, world.textReads.size)
        // A changed file is checked again.
        assertEquals(true, again.check(refs[3].copy(modifiedAt = 9))?.paymentCard)
        assertEquals(2, world.textReads.size)
        assertNull(check.check(refs[4].copy(uri = "content://none")))
    }
}
