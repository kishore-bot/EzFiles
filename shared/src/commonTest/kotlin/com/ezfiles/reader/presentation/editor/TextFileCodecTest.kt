package com.ezfiles.reader.presentation.editor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TextFileCodecTest {
    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun utf8(text: String) = text.encodeToByteArray()

    private fun bom8(text: String) = bytesOf(0xEF, 0xBB, 0xBF) + text.encodeToByteArray()

    private fun utf16(text: String, littleEndian: Boolean): ByteArray {
        val body = text.flatMap { char ->
            val low = (char.code and 0xFF).toByte()
            val high = (char.code shr 8).toByte()
            if (littleEndian) listOf(low, high) else listOf(high, low)
        }.toByteArray()
        return (if (littleEndian) bytesOf(0xFF, 0xFE) else bytesOf(0xFE, 0xFF)) + body
    }

    private fun decoded(bytes: ByteArray) = assertIs<TextDecodeResult.Decoded>(TextFileCodec.decode(bytes))

    private fun refusal(bytes: ByteArray) = assertIs<TextDecodeResult.Refused>(TextFileCodec.decode(bytes)).reason

    @Test
    fun every_encoding_line_ending_and_final_newline_round_trips_exactly() {
        val encoders = mapOf<TextEncoding, (String) -> ByteArray>(
            TextEncoding.Utf8 to ::utf8,
            TextEncoding.Utf8Bom to ::bom8,
            TextEncoding.Utf16Le to { utf16(it, true) },
            TextEncoding.Utf16Be to { utf16(it, false) },
        )
        for ((encoding, encoder) in encoders) {
            for (ending in LineEnding.entries) {
                for (tail in listOf("", ending.sequence, ending.sequence + ending.sequence)) {
                    val content = "héllo €${ending.sequence}😀 world${ending.sequence}${ending.sequence}end$tail"
                    val bytes = encoder(content)
                    val result = decoded(bytes)
                    assertEquals(TextFileFormat(encoding, ending), result.format)
                    assertEquals(content.replace(ending.sequence, "\n"), result.text)
                    assertContentEquals(bytes, TextFileCodec.encode(result.text, result.format))
                }
            }
        }
    }

    @Test
    fun an_edit_keeps_the_encoding_line_ending_and_final_newline() {
        val result = decoded(utf16("one\r\ntwo\r\n", littleEndian = true))
        val edited = result.text.replace("two", "TWO")
        assertContentEquals(utf16("one\r\nTWO\r\n", littleEndian = true), TextFileCodec.encode(edited, result.format))

        val noFinal = decoded(utf8("one\ntwo"))
        assertContentEquals(utf8("ONE\ntwo"), TextFileCodec.encode(noFinal.text.replace("one", "ONE"), noFinal.format))
    }

    @Test
    fun empty_files_round_trip() {
        for (bytes in listOf(ByteArray(0), bytesOf(0xEF, 0xBB, 0xBF), bytesOf(0xFF, 0xFE), bytesOf(0xFE, 0xFF))) {
            val result = decoded(bytes)
            assertEquals("", result.text)
            assertContentEquals(bytes, TextFileCodec.encode("", result.format))
        }
    }

    @Test
    fun a_second_byte_order_mark_is_text_and_survives() {
        val bytes = bom8("﻿a")
        val result = decoded(bytes)
        assertEquals("﻿a", result.text)
        assertContentEquals(bytes, TextFileCodec.encode(result.text, result.format))
    }

    @Test
    fun malformed_utf8_is_refused() {
        val malformed = listOf(
            bytesOf(0x63, 0x61, 0x66, 0xE9), // Latin-1 "café"
            bytesOf(0xC3, 0x28), // bad continuation
            bytesOf(0x61, 0xE2, 0x82), // cut short
            bytesOf(0xC0, 0xAF), // overlong
            bytesOf(0xED, 0xA0, 0x80), // an encoded surrogate
            bytesOf(0xF5, 0x80, 0x80, 0x80), // beyond Unicode
            bytesOf(0xEF, 0xBB, 0xBF, 0xFF), // bad after a BOM
        )
        for (bytes in malformed) assertEquals(TextRefusal.InvalidEncoding, refusal(bytes), bytes.contentToString())
    }

    @Test
    fun malformed_utf16_is_refused() {
        assertEquals(TextRefusal.InvalidEncoding, refusal(bytesOf(0xFF, 0xFE, 0x61)), "odd length")
        assertEquals(TextRefusal.InvalidEncoding, refusal(bytesOf(0xFF, 0xFE, 0x3D, 0xD8, 0x61, 0x00)), "high surrogate alone")
        assertEquals(TextRefusal.InvalidEncoding, refusal(bytesOf(0xFF, 0xFE, 0x00, 0xDC)), "low surrogate alone")
        assertEquals(TextRefusal.InvalidEncoding, refusal(bytesOf(0xFE, 0xFF, 0xD8, 0x3D)), "cut pair, big endian")
    }

    @Test
    fun binary_and_undetectable_encodings_are_refused() {
        assertEquals(TextRefusal.NotText, refusal(bytesOf(0x61, 0x00, 0x62)), "NUL")
        assertEquals(TextRefusal.NotText, refusal(bytesOf(0x61, 0x00, 0x62, 0x00)), "UTF-16 without a BOM")
        assertEquals(TextRefusal.NotText, refusal(bytesOf(0xFF, 0xFE, 0x00, 0x00, 0x61, 0x00, 0x00, 0x00)), "UTF-32 LE")
    }

    @Test
    fun utf32_big_endian_is_refused() {
        // 0xFE 0xFF is not valid UTF-8, so this never reaches the NUL check.
        assertEquals(TextRefusal.InvalidEncoding, refusal(bytesOf(0x00, 0x00, 0xFE, 0xFF, 0x00, 0x00, 0x00, 0x61)))
    }

    @Test
    fun mixed_line_endings_are_refused() {
        for (text in listOf("a\r\nb\nc", "a\rb\nc", "a\r\nb\rc", "a\n\rb")) {
            assertEquals(TextRefusal.MixedLineEndings, refusal(utf8(text)), text)
        }
    }

    @Test
    fun text_pasted_with_other_line_endings_takes_the_files_one() {
        val crLf = TextFileFormat(TextEncoding.Utf8, LineEnding.CrLf)
        assertContentEquals(utf8("a\r\nb\r\nc\r\n"), TextFileCodec.encode("a\r\nb\rc\n", crLf))
        assertContentEquals(utf8("a\nb\nc"), TextFileCodec.encode("a\r\nb\rc", TextFileFormat.New))
    }

    @Test
    fun text_the_format_cant_store_is_not_encoded() {
        assertNull(TextFileCodec.encode("a\uD83D", TextFileFormat.New), "lone surrogate, UTF-8")
        assertNull(TextFileCodec.encode("a\uD83D", TextFileFormat(TextEncoding.Utf16Le, LineEnding.Lf)), "lone surrogate, UTF-16")
        assertNull(TextFileCodec.encode("a\u0000", TextFileFormat.New), "NUL")
        assertNull(TextFileCodec.encode("﻿a", TextFileFormat.New), "would read back as a BOM")
        assertNotNull(TextFileCodec.encode("﻿a", TextFileFormat(TextEncoding.Utf8Bom, LineEnding.Lf)))
    }

    @Test
    fun save_dialog_types_keep_the_chosen_extension() {
        assertEquals("text/plain", textMimeTypeFor("notes.txt"))
        assertEquals("text/markdown", textMimeTypeFor("Notes.MD"))
        assertEquals("text/csv", textMimeTypeFor("data.csv"))
        // Labelled text/plain, these would come back as config.yaml.txt.
        assertEquals("application/octet-stream", textMimeTypeFor("config.yaml"))
        assertEquals("application/octet-stream", textMimeTypeFor("Main.kt"))
        assertEquals("application/octet-stream", textMimeTypeFor("LICENSE"))
    }
}
