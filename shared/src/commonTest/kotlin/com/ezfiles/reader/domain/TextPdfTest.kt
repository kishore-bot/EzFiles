package com.ezfiles.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextPdfTest {
    private fun body(height: Float = 10f, endsParagraph: Boolean = false) = TextLineBox(height, endsParagraph, blank = false, keepWithNext = false)

    private val blank = TextLineBox(10f, endsParagraph = true, blank = true, keepWithNext = false)
    private val heading = TextLineBox(10f, endsParagraph = true, blank = false, keepWithNext = true)

    private fun List<TextPageSlice>.ranges() = map { it.first until it.end }

    @Test
    fun everyLineLandsOnExactlyOnePageInOrder() {
        val lines = List(95) { body() }
        val pages = TextPaginator.paginate(lines, 100f, 0f)
        assertEquals(10, pages.size)
        assertEquals((0 until 95).toList(), pages.flatMap { it.first until it.end })
    }

    @Test
    fun noPageOverflows() {
        val lines = List(200) { index -> body(height = 7f + index % 13, endsParagraph = index % 5 == 0) }
        val pages = TextPaginator.paginate(lines, 120f, 4f)
        for (page in pages) {
            assertTrue(page.usedHeight <= 120f, "used ${page.usedHeight}")
            assertEquals(page.end - page.first, page.tops.size)
        }
    }

    @Test
    fun aLineThatExactlyFillsThePageStaysOnIt() {
        assertEquals(listOf(0 until 10), TextPaginator.paginate(List(10) { body() }, 100f, 0f).ranges())
    }

    @Test
    fun paragraphGapsMoveLaterLinesDown() {
        val page = TextPaginator.paginate(listOf(body(endsParagraph = true), body(), body()), 100f, 5f).single()
        assertEquals(listOf(0f, 15f, 25f), page.tops.toList())
        assertEquals(35f, page.usedHeight)
    }

    @Test
    fun aHeadingMovesToTheNextPageWithItsText() {
        val lines = List(9) { body() } + heading + List(5) { body() }
        assertEquals(listOf(0 until 9, 9 until 15), TextPaginator.paginate(lines, 100f, 0f).ranges())
    }

    @Test
    fun onlyHeadingsStillMakeProgress() {
        val pages = TextPaginator.paginate(List(25) { heading }, 100f, 0f)
        assertEquals((0 until 25).toList(), pages.flatMap { it.first until it.end })
    }

    @Test
    fun blankLinesAtTheTopOfAContinuedPageAreDropped() {
        val lines = List(10) { body() } + listOf(blank, blank) + body()
        val pages = TextPaginator.paginate(lines, 100f, 0f)
        assertEquals(listOf(0 until 10, 12 until 13), pages.ranges())
    }

    @Test
    fun trailingBlankLinesAddNoEmptyPage() {
        val lines = List(10) { body() } + listOf(blank, blank)
        assertEquals(1, TextPaginator.paginate(lines, 100f, 0f).size)
    }

    @Test
    fun aLineTallerThanThePageGetsItsOwnPage() {
        val lines = listOf(body(), body(height = 500f), body())
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), TextPaginator.paginate(lines, 100f, 0f).ranges())
    }

    @Test
    fun noLinesStillGiveOnePage() {
        val page = TextPaginator.paginate(emptyList(), 100f, 0f).single()
        assertEquals(0, page.end - page.first)
    }

    @Test
    fun centringOnlyAppliesWhenAskedAndNeverGoesNegative() {
        assertEquals(40f, TextPaginator.verticalOffset(20f, 100, center = true))
        assertEquals(0f, TextPaginator.verticalOffset(20f, 100, center = false))
        assertEquals(0f, TextPaginator.verticalOffset(150f, 100, center = true))
    }

    @Test
    fun theFirstShortLineBecomesTheTitle() {
        val formatted = TextPdfFormatter.format("\nMy title\nBody text", TextPdfTemplate.ProfessionalMemo)
        assertEquals("\nMy title\nBody text", formatted.text)
        assertEquals(listOf(TextPdfRun(1, 9, TextPdfRunKind.Title)), formatted.runs)
    }

    @Test
    fun aLongFirstParagraphIsNotMadeATitle() {
        assertTrue(TextPdfFormatter.format("word ".repeat(40), TextPdfTemplate.ProfessionalMemo).runs.isEmpty())
    }

    @Test
    fun plainTemplatesLeaveMarkersAlone() {
        val text = "# Not a heading\n- not a bullet\nNOT A HEADING"
        val formatted = TextPdfFormatter.format(text, TextPdfTemplate.MinimalLetter)
        assertEquals(text, formatted.text)
        assertTrue(formatted.runs.isEmpty())
    }

    @Test
    fun structuredTemplatesFindHeadingsAndBullets() {
        val formatted = TextPdfFormatter.format("Name\n# Skills\n- Kotlin\nEXPERIENCE\nTopics:\nplain line", TextPdfTemplate.ResumeStyle)
        assertEquals("Name\nSkills\n• Kotlin\nEXPERIENCE\nTopics:\nplain line", formatted.text)
        assertEquals(
            listOf(TextPdfRunKind.Title, TextPdfRunKind.Heading, TextPdfRunKind.Heading, TextPdfRunKind.Heading),
            formatted.runs.map { it.kind },
        )
        assertEquals("Skills", formatted.text.substring(formatted.runs[1].start, formatted.runs[1].end))
        assertEquals("EXPERIENCE", formatted.text.substring(formatted.runs[2].start, formatted.runs[2].end))
    }

    @Test
    fun scriptsWithoutCapitalsArentMistakenForHeadings() {
        val formatted = TextPdfFormatter.format("Title\nनमस्ते\n你好世界", TextPdfTemplate.StudyNotes)
        assertEquals(1, formatted.runs.size)
    }

    @Test
    fun lineEndsAreUnifiedAndControlCharactersRemoved() {
        val formatted = TextPdfFormatter.format("one\r\ntwo\rthree\u0000\tfour\u0007", TextPdfTemplate.MinimalLetter)
        assertEquals("one\ntwo\nthree\tfour", formatted.text)
    }

    @Test
    fun formattingIsDeterministicForEveryTemplate() {
        val text = "Heading\n\nSome text:\n- item\n"
        for (template in TextPdfTemplate.entries) {
            val first = TextPdfFormatter.format(text, template)
            val second = TextPdfFormatter.format(text, template)
            assertEquals(first.text, second.text)
            assertEquals(first.runs, second.runs)
            for (run in first.runs) assertTrue(run.start < run.end && run.end <= first.text.length)
        }
    }

    @Test
    fun everyTemplateLeavesAUsableTextArea() {
        assertEquals(8, TextPdfTemplate.entries.size)
        for (template in TextPdfTemplate.entries) {
            for (size in listOf(PdfPageSize.A4, PdfPageSize.Letter)) {
                val style = TextPdfStyle.of(template, size)
                val box = style.contentBox()
                assertTrue(box.left >= 0 && box.top >= 0, template.name)
                assertTrue(box.left + box.width <= box.pageWidth, template.name)
                assertTrue(box.top + box.height <= box.pageHeight, template.name)
                assertTrue(style.textSizePt in TextPdfStyle.MIN_TEXT_SIZE..TextPdfStyle.MAX_TEXT_SIZE, template.name)
            }
        }
    }

    @Test
    fun hugeMarginsCantEmptyTheTextArea() {
        for (template in TextPdfTemplate.entries) {
            val box = TextPdfStyle.of(template).copy(marginPt = 10_000f).contentBox()
            assertTrue(box.width > 100 && box.height > 100, template.name)
        }
    }

    @Test
    fun theFitPictureSizeCountsAsA4ForText() {
        val box = TextPdfStyle.of(TextPdfTemplate.Journal, PdfPageSize.FitImage).contentBox()
        assertEquals(595, box.pageWidth)
        assertEquals(842, box.pageHeight)
    }

    @Test
    fun textColourFollowsTheBackground() {
        val dark = TextPdfStyle.of(TextPdfTemplate.DarkPoster)
        val light = TextPdfStyle.of(TextPdfTemplate.MinimalLetter)
        assertTrue(relativeLuminance(dark.textArgb) > 0.5f)
        assertFalse(relativeLuminance(light.textArgb) > 0.5f)
        assertTrue(relativeLuminance(light.copy(backgroundArgb = dark.backgroundArgb).textArgb) > 0.5f)
    }
}
