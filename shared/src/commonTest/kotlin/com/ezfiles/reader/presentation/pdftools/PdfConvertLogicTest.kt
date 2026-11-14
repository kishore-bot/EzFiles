package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.platform.PageImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ConvertPageRangeTest {
    private fun pages(text: String, count: Int = 10) = assertIs<ConvertPageRange.Pages>(parseConvertPageRange(text, count)).indices

    private fun invalid(text: String, count: Int = 10) = assertIs<ConvertPageRange.Invalid>(parseConvertPageRange(text, count)).message

    @Test
    fun singlePagesAndRanges() {
        assertEquals(listOf(0), pages("1"))
        assertEquals(listOf(0, 1, 2, 4), pages("1-3,5"))
        assertEquals(listOf(0, 1, 2, 4), pages(" 1 - 3 ,  5 "))
    }

    @Test
    fun overlapsAndOrderCollapse() {
        assertEquals(listOf(0, 1, 2, 3), pages("3-4,1-3,2"))
    }

    @Test
    fun openEndsRunToTheFirstOrLastPage() {
        assertEquals(listOf(7, 8, 9), pages("8-"))
        assertEquals(listOf(0, 1), pages("-2"))
    }

    @Test
    fun otherDashesWork() {
        assertEquals(listOf(0, 1), pages("1–2"))
        assertEquals(listOf(0, 1), pages("1—2"))
    }

    @Test
    fun aStrayCommaIsForgiven() {
        assertEquals(listOf(0, 1), pages("1,,2,"))
    }

    @Test
    fun badInputSaysWhy() {
        assertTrue(invalid("").contains("Enter"))
        assertTrue(invalid(",").contains("Enter"))
        assertTrue(invalid("0").contains("start at 1"))
        assertTrue(invalid("11").contains("10 pages"))
        assertTrue(invalid("5-11").contains("10 pages"))
        assertTrue(invalid("5-3").contains("backwards"))
        assertTrue(invalid("abc").contains("isn't a page"))
        assertTrue(invalid("-").contains("isn't a page"))
        assertTrue(invalid("1-2-3").contains("isn't a page"))
        assertTrue(invalid("99999999999").contains("isn't a page"), "too large for an Int")
        assertTrue(invalid("١").contains("isn't a page"))
    }

    @Test
    fun oneLessPageReadsNaturally() {
        assertTrue(invalid("2", 1).contains("1 page."))
    }

    @Test
    fun aHugeInputIsRefused() {
        assertTrue(invalid("1,".repeat(1000)).contains("too long"))
    }

    @Test
    fun anEmptyDocumentHasNoPages() {
        assertTrue(invalid("1", 0).contains("no pages"))
    }
}

class ConvertFileNameTest {
    @Test
    fun theStemDropsThePdfEnding() {
        assertEquals("report", convertFileStem("report.pdf"))
        assertEquals("report", convertFileStem("report.PDF"))
        assertEquals("report.v2", convertFileStem("report.v2.pdf"))
    }

    @Test
    fun pathsAndReservedCharactersCantEscape() {
        assertFalse(convertFileStem("/../../etc/passwd").any { it == '/' || it == '\\' })
        assertEquals("a_b_c", convertFileStem("a\\b:c.pdf"))
        assertEquals("a_b", convertFileStem("a\u0000b.pdf"))
    }

    @Test
    fun anEmptyNameFallsBack() {
        assertEquals("document", convertFileStem(".pdf"))
        assertEquals("document", convertFileStem("   "))
        assertEquals("document", convertFileStem("..."))
    }

    @Test
    fun longNamesAreCutWithoutSplittingACharacter() {
        assertEquals(80, convertFileStem("x".repeat(500)).length)
        val emoji = "x".repeat(79) + "😀" + "tail"
        assertEquals("x".repeat(79), convertFileStem(emoji))
    }

    @Test
    fun pageFilesSortInPageOrder() {
        assertEquals("name-page-001.jpg", pageImageFileName("name", 0, 12, PageImageFormat.Jpeg))
        assertEquals("name-page-012.png", pageImageFileName("name", 11, 12, PageImageFormat.Png))
        assertEquals("name-page-0999.jpg", pageImageFileName("name", 998, 1200, PageImageFormat.Jpeg))
    }
}

class ExportImageSizeTest {
    @Test
    fun a4AtEachResolution() {
        assertEquals(ExportImageSize(826, 1169, false), exportImageSize(595f, 842f, 100))
        val at300 = exportImageSize(595f, 842f, 300)
        assertEquals(2479, at300.width)
        assertEquals(3508, at300.height)
        assertFalse(at300.reduced)
    }

    @Test
    fun hugePagesAreReducedKeepingTheirShape() {
        val a0 = exportImageSize(2384f, 3370f, 300)
        assertTrue(a0.reduced)
        assertTrue(a0.width.toLong() * a0.height <= MAX_EXPORT_PIXELS)
        assertTrue(kotlin.math.abs(a0.width.toFloat() / a0.height - 2384f / 3370f) < 0.01f)
    }

    @Test
    fun aVeryLongPageIsLimitedBySide() {
        val strip = exportImageSize(100f, 14400f, 300)
        assertTrue(strip.height <= MAX_EXPORT_SIDE)
        assertTrue(strip.width >= 1)
    }

    @Test
    fun nonsensePageSizesFallBack() {
        listOf(0f to 0f, -5f to 10f, Float.NaN to 10f, Float.POSITIVE_INFINITY to 10f).forEach { (w, h) ->
            val size = exportImageSize(w, h, 100)
            assertTrue(size.width > 0 && size.height > 0)
            assertFalse(size.reduced)
        }
    }
}

class ExportPagesTest {
    @Test
    fun everyPageGoesThroughInOrder() = runTest {
        val written = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val result = exportPagesOneByOne(listOf(2, 5, 7), { byteArrayOf(it.toByte()) }, { page, _ -> written += page }, { done, _ -> progress += done })
        assertEquals(PageExportResult(3, 3, null), result)
        assertEquals(listOf(2, 5, 7), written)
        assertEquals(listOf(0, 1, 2, 3), progress)
    }

    @Test
    fun aFailureReportsHowManyWereWritten() = runTest {
        val written = mutableListOf<Int>()
        val result = exportPagesOneByOne(
            listOf(0, 1, 2),
            { if (it == 1) throw DocumentException(DocumentError.LimitExceeded("too big")) else ByteArray(0) },
            { page, _ -> written += page },
            { _, _ -> },
        )
        assertEquals(1, result.written)
        assertEquals(listOf(0), written)
        assertEquals("Page 2: too big", result.failure)
    }

    @Test
    fun aWriteFailureDoesNotCount() = runTest {
        val result = exportPagesOneByOne(
            listOf(0),
            { ByteArray(0) },
            { _, _ -> throw DocumentException(DocumentError.FileAccess("full")) },
            { _, _ -> },
        )
        assertEquals(0, result.written)
        assertNotNull(result.failure)
    }

    @Test
    fun aRendererExceptionBecomesAReportNotACrash() = runTest {
        val result = exportPagesOneByOne(listOf(0), { error("damaged") }, { _, _ -> }, { _, _ -> })
        assertEquals("Page 1 couldn't be read.", result.failure)
    }

    @Test
    fun cancellationPassesThrough() = runTest {
        assertFailsWith<CancellationException> {
            exportPagesOneByOne(listOf(0), { throw CancellationException("stop") }, { _, _ -> }, { _, _ -> })
        }
    }
}

class PdfTextCollectorTest {
    @Test
    fun pagesAreJoinedWithABlankLine() {
        val collector = PdfTextCollector(2)
        assertTrue(collector.add(0, "one"))
        assertTrue(collector.add(1, "two"))
        assertEquals("one\n\ntwo", collector.assemble(separators = false))
    }

    @Test
    fun separatorsNameTheRealPage() {
        val collector = PdfTextCollector(3)
        collector.add(0, "one")
        collector.add(1, "   ")
        collector.add(2, "three")
        assertEquals(
            "--- Page 1 of 3 ---\n\none\n\n--- Page 3 of 3 ---\n\nthree",
            collector.assemble(separators = true),
        )
    }

    @Test
    fun blankPagesAreCountedAsScansNotAsText() {
        val collector = PdfTextCollector(2)
        collector.add(0, " \n ")
        collector.add(1, "")
        assertFalse(collector.hasText)
        assertEquals(2, collector.blankPages)
        assertEquals("", collector.assemble(false))
    }

    @Test
    fun theCapCutsOnThePageThatReachesIt() {
        val collector = PdfTextCollector(5, maxChars = 10)
        assertTrue(collector.add(0, "12345"))
        assertFalse(collector.add(1, "6789012"))
        assertEquals(1, collector.cutAtPage)
        assertTrue(collector.isFull)
        assertFalse(collector.add(2, "more"))
        assertEquals("12345\n\n67890", collector.assemble(false))
    }

    @Test
    fun exactlyFillingTheCapIsNotACut() {
        val collector = PdfTextCollector(2, maxChars = 5)
        assertTrue(collector.add(0, "12345"))
        assertNull(collector.cutAtPage)
    }

    @Test
    fun aPageAfterAnExactFitAddsNoEmptySection() {
        val collector = PdfTextCollector(2, maxChars = 5)
        collector.add(0, "12345")
        assertFalse(collector.add(1, "more"))
        assertEquals(1, collector.cutAtPage)
        assertEquals("12345", collector.assemble(true).substringAfter("\n\n"))
    }

    @Test
    fun theCapDoesNotSplitACharacter() {
        val collector = PdfTextCollector(1, maxChars = 3)
        collector.add(0, "ab😀")
        assertEquals("ab", collector.assemble(false))
    }

    @Test
    fun textIsCleanedOfControlCharactersAndLineEndings() {
        assertEquals("a\nb\nc\td", cleanExtractedText("a\r\nb\rc\td\u0000\u0007 "))
    }
}

class FileSizeTextTest {
    @Test
    fun sizesAreRound() {
        assertEquals("12 B", formatFileSize(12))
        assertEquals("2 KB", formatFileSize(2048))
        assertEquals("1.5 MB", formatFileSize(1024 * 1024 * 3 / 2))
    }

    @Test
    fun theNoteIsHonestAboutWhatAZipDid() {
        assertTrue(zipSizeNote(1000, 1000, false).startsWith("About the same"))
        assertTrue(zipSizeNote(1000, 1010, false).startsWith("About the same"))
        assertTrue(zipSizeNote(1000, 950, false).startsWith("5% smaller"))
        assertEquals("50% smaller.", zipSizeNote(1000, 500, false))
        assertTrue(zipSizeNote(null, 500, false).contains("rarely"))
        assertTrue(zipSizeNote(1000, 500, true).contains("pictures"))
    }
}
