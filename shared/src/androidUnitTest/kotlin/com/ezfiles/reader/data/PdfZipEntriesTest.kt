package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfZipEntriesTest {
    private fun read(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes())
            }
        }
    }

    @Test
    fun aStreamedDocumentAndStoredPicturesReadBack() {
        val pdf = ByteArray(300_000) { (it % 7).toByte() }
        val picture = ByteArray(5_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        var lastProgress = 0L
        ZipOutputStream(out).use { zip ->
            val size = zip.addCompressedEntry("a.pdf", ByteArrayInputStream(pdf), Long.MAX_VALUE) { lastProgress = it }
            assertEquals(pdf.size.toLong(), size)
            zip.addStoredEntry("a-page-001.jpg", picture)
        }
        assertEquals(pdf.size.toLong(), lastProgress)
        val entries = read(out.toByteArray())
        assertContentEquals(pdf, entries["a.pdf"])
        assertContentEquals(picture, entries["a-page-001.jpg"])
        assertTrue(out.size() < pdf.size, "repetitive data really is compressed")
    }

    @Test
    fun inputPastTheLimitIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            assertFailsWith<DocumentException> {
                zip.addCompressedEntry("a.pdf", ByteArrayInputStream(ByteArray(1000)), maxBytes = 10)
            }
            zip.closeEntry()
        }
    }

    @Test
    fun aThrowingProgressCallbackStopsTheCopy() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            assertFailsWith<IllegalStateException> {
                zip.addCompressedEntry("a.pdf", ByteArrayInputStream(ByteArray(500_000)), Long.MAX_VALUE) { error("cancelled") }
            }
            zip.closeEntry()
        }
        assertNull(read(ByteArray(0))["a.pdf"])
    }
}
