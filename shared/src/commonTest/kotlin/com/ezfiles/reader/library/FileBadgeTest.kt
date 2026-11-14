package com.ezfiles.reader.library

import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzBadgeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FileBadgeTest {
    private fun badge(name: String) = FileType.of(name, null).let { it.label to (it.top to it.bottom) }

    @Test
    fun eachExtensionGetsItsLabelAndColour() {
        val expected = mapOf(
            "report.pdf" to ("PDF" to EzBadgeColors.Red),
            "spec.docx" to ("DOCX" to EzBadgeColors.Blue),
            "budget.xlsx" to ("XLSX" to EzBadgeColors.Green),
            "customers.csv" to ("CSV" to EzBadgeColors.Green),
            "server.log" to ("LOG" to EzBadgeColors.Grey),
            "notes.txt" to ("TXT" to EzBadgeColors.Grey),
            "config.json" to ("JSON" to EzBadgeColors.Purple),
            "sitemap.xml" to ("XML" to EzBadgeColors.Orange),
            "landing_page.html" to ("HTML" to EzBadgeColors.Orange),
            "handbook.epub" to ("EPUB" to EzBadgeColors.Violet),
            "readme.md" to ("MD" to EzBadgeColors.Slate),
        )
        for ((name, badge) in expected) assertEquals(badge, badge(name), name)
    }

    @Test
    fun extensionCaseDoesNotMatter() {
        assertEquals(badge("A.PDF"), badge("a.pdf"))
    }

    @Test
    fun withoutAnExtensionTheFormatDecides() {
        assertEquals(FileType.Pdf, FileType.of("download", DocumentFormat.Pdf))
        assertEquals(FileType.Other, FileType.of("download", null))
    }

    @Test
    fun jsonAndEpubAreToldApart() {
        assertNotEquals(FileType.Json.bottom, FileType.Epub.bottom)
    }
}
