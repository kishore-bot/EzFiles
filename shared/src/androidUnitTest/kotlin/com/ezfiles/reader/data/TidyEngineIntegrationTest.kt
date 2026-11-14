package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeTidyFile
import com.ezfiles.engine.ffi.NativeTidyListener
import com.ezfiles.engine.ffi.NativeTidySource
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.tidyFindIdentical
import com.ezfiles.reader.domain.CollectionKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the Rust tidy algorithms through the generated bindings on the host
 * JVM. Descriptors are Unix-only, so identical-file hashing opens by path.
 */
class TidyEngineIntegrationTest {
    private val engine = RustTidyEngine()

    private fun pathListener(files: List<File>, cancelled: Boolean = false) = object : NativeTidyListener {
        override fun open(index: UInt) = NativeTidySource(-1, files[index.toInt()].absolutePath)

        override fun onProgress(done: UInt, total: UInt) = Unit

        override fun isCancelled() = cancelled
    }

    private fun temp(text: String) = File.createTempFile("tidy", ".txt").apply {
        deleteOnExit()
        writeText(text)
    }

    @Test
    fun identical_files_are_grouped_through_the_ffi() {
        val files = listOf(temp("same content"), temp("different!!!"), temp("same content"))
        val result = tidyFindIdentical(files.map { NativeTidyFile(it.length().toULong(), null, null) }, pathListener(files))
        assertEquals(listOf(listOf(0u, 2u)), result.groups)
        assertNotNull(result.fullHashes[0])
        assertEquals(result.fullHashes[0], result.fullHashes[2])
    }

    @Test
    fun a_cancelled_scan_fails_as_cancelled() {
        val files = listOf(temp("abc"), temp("abc"))
        assertFailsWith<ReadException.Cancelled> {
            tidyFindIdentical(files.map { NativeTidyFile(3u, null, null) }, pathListener(files, cancelled = true))
        }
    }

    @Test
    fun text_is_fingerprinted_and_classified() {
        val words = (1..300).joinToString(" ") { "word${it % 97} item${it % 13}" }
        val a = engine.analyzeText("notes.txt", words)
        val b = engine.analyzeText("notes2.txt", words.replaceFirst("word5", "changed"))
        assertNotNull(a.fingerprint)
        assertEquals(listOf(listOf(0, 1)), engine.groupSimilar(listOf(a.fingerprint!!, b.fingerprint!!), images = false))
        val receipt = engine.analyzeText("scan.pdf", "TAX INVOICE\nSubtotal 100.00\nGST 18.00\nGrand total ₹ 118.00")
        assertEquals(CollectionKind.Receipts, receipt.collection?.kind)
        assertTrue("invoice" in receipt.collection!!.reason.terms)
        assertEquals(CollectionKind.Travel, engine.classifyName("boarding pass.pdf")?.kind ?: engine.classifyName("eticket.pdf")?.kind)
    }

    @Test
    fun semantic_collections_cross_the_native_boundary() {
        assertEquals(CollectionKind.Resumes, engine.classifyName("Kishore_CV.pdf")?.kind)
        assertEquals(CollectionKind.BankStatements, engine.classifyName("loan-agreement.pdf")?.kind)
        val career = "Work Experience: bank branch manager, preparing account statements. " +
            "Education: Bachelor of Commerce. Skills: debit, credit and customer service."
        assertEquals(CollectionKind.Resumes, engine.analyzeText("scan.pdf", career).collection?.kind)
        assertNull(engine.classifyName("statement.pdf"))
    }

    @Test
    fun pictures_and_sensitive_numbers() {
        val gradient = ByteArray(32 * 32) { i -> ((i % 32) * 7 + (i / 32) * 3).toByte() }
        assertNotNull(engine.imageFingerprint(32, 32, gradient))
        assertNull(engine.imageFingerprint(32, 32, ByteArray(32 * 32) { 100 }))
        assertTrue(engine.sensitive("Card 4111 1111 1111 1111").paymentCard)
        assertTrue(engine.sensitive("Aadhaar 2345 6789 0124").nationalId)
        assertTrue(!engine.sensitive("Order 12345, call 98765 43210").any)
    }
}
