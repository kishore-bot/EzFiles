package com.ezfiles.reader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaControlsTest {
    @Test
    fun srt_accepts_bom_crlf_and_cleans_formatting() {
        val document = parseSubtitles("captions.srt", "\uFEFF1\r\n00:00:01,250 --> 00:00:03,000\r\n<i>Hello</i> &amp; welcome\r\n")
        assertEquals("", document.textAt(1_249))
        assertEquals("Hello & welcome", document.textAt(1_250))
        assertEquals("", document.textAt(3_000))
    }

    @Test
    fun webvtt_ignores_comments_styles_and_position_settings() {
        val document = parseSubtitles("captions.vtt", """
            WEBVTT

            NOTE This comment is not a caption
            00:00.000 --> 00:20.000
            hidden

            STYLE
            ::cue { color: red; }

            greeting
            00:01.000 --> 00:03.000 align:start position:10%
            <v Anna>Hello</v>
        """.trimIndent())
        assertEquals("", document.textAt(500))
        assertEquals("Hello", document.textAt(1_000))
    }

    @Test
    fun invalid_ranges_are_ignored_without_accepting_a_broken_file() {
        assertFailsWith<IllegalArgumentException> {
            parseSubtitles("bad.srt", "1\n00:60:00,000 --> 00:61:00,000\nWrong\n\n2\n00:00:02,000 --> 00:00:01,000\nBackwards")
        }
    }

    @Test
    fun interval_lookup_retains_long_cues_across_many_shorter_cues_and_backward_seeks() {
        val cues = listOf(SubtitleCue(0, 100_000, "Long caption")) + (1..2_000).map {
            SubtitleCue(it * 20L, it * 20L + 5, "Short $it")
        }
        val document = SubtitleDocument("overlap.srt", cues)
        assertEquals("Long caption", document.textAt(40_010))
        assertEquals("Long caption\nShort 1", document.textAt(20))
        assertEquals("", document.textAt(100_000))
    }

    @Test
    fun overlapping_cues_are_combined_once_in_start_order() {
        val document = SubtitleDocument("overlap.srt", listOf(
            SubtitleCue(2_000, 4_000, "Second"), SubtitleCue(0, 3_000, "First"), SubtitleCue(1_000, 3_000, "First"),
        ))
        assertEquals("First\nSecond", document.textAt(2_500))
        assertEquals("Second", document.textAt(3_000))
    }

    @Test
    fun oversized_caption_files_are_rejected_before_splitting() {
        assertFailsWith<IllegalArgumentException> { parseSubtitles("huge.srt", "a".repeat(1_048_577)) }
    }

    @Test
    fun inband_webvtt_extracts_only_payload_boxes_and_rejects_truncated_binary() {
        fun box(type: String, bytes: ByteArray): ByteArray {
            val size = bytes.size + 8
            return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
                type.encodeToByteArray() + bytes
        }
        val sample = box("vttc", box("iden", "cue-id".encodeToByteArray()) + box("payl", "<b>Caption</b>".encodeToByteArray()))
        assertEquals("Caption", embeddedWebVtt(sample))
        assertEquals("", embeddedWebVtt(sample.copyOf(10)))
    }

    @Test
    fun seeking_is_bounded_for_short_and_long_media() {
        assertEquals(60_000L, draggedMediaPosition(30_000, 100f, 100f, 60_000))
        assertEquals(0L, draggedMediaPosition(30_000, -100f, 100f, 60_000))
        assertEquals(420_000L, draggedMediaPosition(300_000, 100f, 100f, 7_200_000))
        assertEquals(10L, draggedMediaPosition(10, 10f, 0f, 100))
    }

    @Test
    fun vertical_levels_clamp_and_do_not_invert() {
        assertEquals(1f, draggedMediaLevel(0.5f, -1_000f, 100f))
        assertEquals(0f, draggedMediaLevel(0.5f, 1_000f, 100f))
        assertEquals(0.5f, draggedMediaLevel(0.5f, Float.NaN, 100f))
    }
}
