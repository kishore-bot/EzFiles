package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeDocumentKind
import com.ezfiles.engine.ffi.NativeLimits
import com.ezfiles.engine.ffi.textReaderOpenPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the Rust search exports through the generated bindings on the host JVM. */
class SearchIntegrationTest {
    @Test
    fun ranks_file_names_with_typos_in_one_call() {
        val names = listOf("Notes.txt", "Invoice_2025.pdf", "Reprot draft.docx", "Report.docx")
        val matcher = RustFileNameSearch.prepare(names)
        assertEquals(listOf(1), matcher.rank("invoce"))
        assertEquals(listOf("Report.docx", "Reprot draft.docx"), matcher.rank("report").map(names::get))
        assertEquals(names.indices.toList(), matcher.rank(" "))
    }

    @Test
    fun indexes_queries_and_persists_through_the_bindings() {
        val dir = Files.createTempDirectory("ezfiles-search").toFile()
        try {
            val index = RustFullTextIndex(dir.path)
            val entry = IndexEntry("doc", 10, 20)
            val text = "Page one: apples.\nPage two: the quarterly invoice total."
            assertTrue(index.add(entry, text, listOf(0, text.indexOf("Page two"))))
            assertEquals(listOf(1), index.stale(listOf(IndexEntry("doc", 10, 20), IndexEntry("new", 1, 1))))
            val hit = index.query("invoices", 5).single()
            assertEquals("doc", hit.key)
            assertEquals(1, hit.page)
            assertEquals("invoice", hit.snippet.substring(hit.highlights.single()))
            index.save()

            val reopened = RustFullTextIndex(dir.path)
            assertEquals("doc", reopened.query("quarterly", 5).single().key)
            reopened.retain(emptyList())
            assertTrue(reopened.query("quarterly", 5).isEmpty())
            reopened.clear()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reads_plain_text_without_building_blocks() {
        val file = File.createTempFile("ezfiles", ".md").apply {
            deleteOnExit()
            writeText("# Title\n\nSome   *text* here.\n")
        }
        textReaderOpenPath(file.path, file.name, NativeLimits(0u, 0u, 0u)).use { reader ->
            assertEquals(NativeDocumentKind.TEXT, reader.kind())
            assertEquals("Title\nSome text", reader.read(15u))
        }
    }
}
