package com.ezfiles.reader.crash

import com.ezfiles.reader.platform.isOpenableLink
import com.ezfiles.reader.presentation.about.Team
import com.ezfiles.reader.presentation.about.VisionBlock
import com.ezfiles.reader.presentation.about.VisionText
import com.ezfiles.reader.presentation.about.initialsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportingTest {
    private val device = DeviceDescription("1.2.3", 42, "Android 15 (API 35)", "Google Pixel 8")

    private fun assertClean(text: String, vararg secrets: String) {
        secrets.forEach { assertFalse(it in text, "\"$it\" leaked into: $text") }
    }

    @Test
    fun scrubs_content_uris_with_spaces() {
        val out = CrashScrubber.scrub("Failed to open content://com.android.providers.downloads/document/My Taxes 2025.pdf: denied")
        assertClean(out, "Taxes", "content://", "downloads", ".pdf")
        assertTrue(out.endsWith(": denied"), out)
    }

    @Test
    fun scrubs_file_paths_and_names() {
        val out = CrashScrubber.scrub("java.io.FileNotFoundException: /storage/emulated/0/Download/secret plan.docx (No such file or directory)")
        assertClean(out, "storage", "secret", "plan", "docx")
        assertTrue(out.startsWith("java.io.FileNotFoundException: <path>"), out)
        assertTrue("(No such file or directory)" in out, out)
    }

    @Test
    fun scrubs_bare_file_names_quoted_text_and_emails() {
        val out = CrashScrubber.scrub("Bad cell in budget_q4.xlsx near 'Salary of Alice' sent by alice@example.org, see \"notes\"")
        assertClean(out, "budget_q4", "Salary", "Alice", "alice@", "notes")
        assertTrue("Bad cell in <file>" in out, out)
    }

    @Test
    fun scrubs_windows_paths() {
        assertClean(CrashScrubber.scrub("at C:\\Users\\me\\Documents\\cv.pdf"), "Users", "cv")
    }

    @Test
    fun keeps_stack_frames_and_class_names() {
        val frames = listOf(
            "\tat com.ezfiles.reader.presentation.ReaderViewModel.open(ReaderViewModel.kt:120)",
            "\tat java.lang.Thread.run(Thread.java:1012)",
            "Caused by: java.lang.IllegalStateException: Index 5 out of bounds for length 3",
            "java.lang.NoSuchMethodError: No virtual method foo()V in class Lcom/ezfiles/Foo;",
            "\t... 12 more",
        )
        frames.forEach { assertEquals(it, CrashScrubber.scrub(it)) }
    }

    @Test
    fun formats_header_and_scrubbed_trace() {
        val trace = "java.lang.IllegalStateException: cannot read /sdcard/Docs/a.pdf\n\tat com.ezfiles.Foo.bar(Foo.kt:3)"
        val text = CrashReportFormatter.format(device, "2026-11-11T10:00:00Z", "main", "reader (Pdf)", trace)
        assertEquals(
            """
            Xfiles crash report
            App: 1.2.3 (42)
            System: Android 15 (API 35)
            Device: Google Pixel 8
            Time: 2026-11-11T10:00:00Z
            Thread: main
            Last screen: reader (Pdf)

            java.lang.IllegalStateException: cannot read <path>
            ${"\t"}at com.ezfiles.Foo.bar(Foo.kt:3)
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun formats_a_throwable_without_its_message_secrets() {
        val error = IllegalArgumentException("unreadable content://media/external/file/99 named holiday.jpg")
        val text = CrashReportFormatter.format(device, "t", "worker-1", null, error)
        assertTrue(text.contains("java.lang.IllegalArgumentException") || text.contains("IllegalArgumentException"), text)
        assertTrue("Last screen: unknown" in text, text)
        assertClean(text, "media/external", "holiday")
    }

    @Test
    fun caps_size_at_a_line_end() {
        val trace = (1..5000).joinToString("\n") { "\tat com.ezfiles.Frame$it.call(Frame.kt:$it)" }
        val text = CrashReportFormatter.format(device, "t", "main", null, trace, maxBytes = 4096)
        assertTrue(text.encodeToByteArray().size <= 4096, "size ${text.encodeToByteArray().size}")
        assertTrue(text.startsWith("Xfiles crash report"))
        assertTrue(text.endsWith("… (report shortened)"), text.takeLast(80))
        assertTrue(text.lines().dropLast(1).last().endsWith(")"), "cut mid-line")
    }

    @Test
    fun caps_a_single_huge_line_with_multibyte_text() {
        val text = CrashReportFormatter.capBytes("é".repeat(10_000), 1000)
        assertTrue(text.encodeToByteArray().size <= 1000)
        assertTrue(text.startsWith("éé"))
    }

    @Test
    fun the_default_cap_is_64_kb() {
        val trace = "x".repeat(400) + "\n" + (1..10_000).joinToString("\n") { "\tat a.B.c(B.kt:$it)" }
        val text = CrashReportFormatter.format(device, "t", "main", null, trace)
        assertTrue(text.encodeToByteArray().size <= MAX_CRASH_REPORT_BYTES)
        assertTrue(text.encodeToByteArray().size > MAX_CRASH_REPORT_BYTES - 200)
    }

    @Test
    fun long_messages_are_cut() {
        val text = CrashReportFormatter.format(device, "t", "main", null, "E: " + "y".repeat(5000))
        assertTrue(text.lines().all { it.length <= 501 }, "a line is too long")
    }

    @Test
    fun rotation_keeps_at_most_five_after_writing() {
        val existing = (1..7).map { CrashReportFiles.nameFor(1_000L * it) } + "notes.txt" + "crash-5000.txt.part"
        val delete = CrashReportFiles.toDeleteBeforeWrite(existing)
        // Seven reports, one more coming: the three oldest go, four stay, five after the write.
        assertEquals(listOf("crash-3000.txt", "crash-2000.txt", "crash-1000.txt"), delete)
        assertEquals(emptyList(), CrashReportFiles.toDeleteBeforeWrite(listOf("crash-1.txt", "crash-2.txt")))
    }

    @Test
    fun orders_by_time_then_sequence() {
        val names = listOf("crash-900.txt", "crash-1000.txt", "crash-1000-2.txt", "crash-1000-1.txt")
        assertEquals(listOf("crash-1000-2.txt", "crash-1000-1.txt", "crash-1000.txt", "crash-900.txt"), CrashReportFiles.newestFirst(names))
        assertEquals("crash-1000-1.txt", CrashReportFiles.nameFor(1000, 1))
    }

    @Test
    fun dismissing_clears_that_report_and_older_ones() {
        val names = listOf("crash-1.txt", "crash-2.txt", "crash-3.txt", "other.txt")
        assertEquals(listOf("crash-2.txt", "crash-1.txt"), CrashReportFiles.toDeleteOnDismiss(names, "crash-2.txt"))
        assertEquals(emptyList(), CrashReportFiles.toDeleteOnDismiss(names, "../other.txt"))
        assertEquals(emptyList(), CrashReportFiles.toDeleteOnDismiss(names, "crash-9.txt"))
    }

    @Test
    fun feedback_template_has_only_version_and_device() {
        val body = FeedbackTemplate.body(device)
        assertEquals("\n\n--\nXfiles 1.2.3 (42)\nAndroid 15 (API 35)\nGoogle Pixel 8", body)
        assertEquals("Xfiles crash report 1.2.3", CrashReportFormatter.subject("1.2.3"))
    }

    @Test
    fun only_https_and_mailto_links_open() {
        assertTrue(isOpenableLink("https://example.org/me"))
        assertTrue(isOpenableLink("mailto:me@example.org"))
        assertFalse(isOpenableLink("http://example.org"))
        assertFalse(isOpenableLink("content://x/y"))
        assertFalse(isOpenableLink("javascript:alert(1)"))
        assertFalse(isOpenableLink("https://"))
        assertFalse(isOpenableLink("https://a b"))
    }

    @Test
    fun initials_and_placeholders() {
        assertEquals("YN", initialsOf("Your name"))
        assertEquals("CN", initialsOf("Colleague's name"))
        assertEquals("A", initialsOf("ada"))
        assertEquals("?", initialsOf("  "))
        assertEquals(2, Team.size)
    }

    @Test
    fun vision_text_keeps_its_shape() {
        assertEquals(VisionBlock.Title("Why Xfiles Exists"), VisionText.first())
        assertEquals(3, VisionText.count { it is VisionBlock.PullQuote })
        assertEquals(5, VisionText.filterIsInstance<VisionBlock.Beliefs>().single().items.size)
        assertEquals(VisionBlock.SignOff("Built for people. Shared with everyone."), VisionText.last())
    }
}
