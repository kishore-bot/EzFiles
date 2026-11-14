package com.ezfiles.reader.platform

import com.ezfiles.reader.domain.DocumentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentDeliveryTest {
    @Test
    fun clampsAllPagesAndIgnoresOutOfDocumentRanges() {
        assertEquals(listOf(0..4), selectedPrintRanges(5, listOf(0..Int.MAX_VALUE)))
        assertEquals(emptyList(), selectedPrintRanges(5, listOf(7..9, -5..-1)))
        assertEquals(emptyList(), selectedPrintRanges(0, listOf(0..Int.MAX_VALUE)))
    }

    @Test
    fun sortsAndMergesRepeatedOverlappingAndAdjacentRanges() {
        assertEquals(listOf(0..4, 7..9), selectedPrintRanges(10, listOf(8..20, 2..4, 0..2, 7..8, 0..0)))
    }

    @Test
    fun richFormatsRequireAnExplicitExportBeforePrinting() {
        for (extension in listOf("docx", "xlsx", "pptx", "epub", "html", "zip")) {
            assertNull(printKind(DocumentRef("content://example/file", "document.$extension")))
        }
        assertEquals(PrintKind.Pdf, printKind(DocumentRef("content://example/file", "Document.PDF")))
        assertEquals(PrintKind.Image, printKind(DocumentRef("content://example/file", "photo.jpg")))
        assertEquals(PrintKind.Text, printKind(DocumentRef("content://example/file", "notes.txt")))
    }
}
