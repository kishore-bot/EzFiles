package com.ezfiles.reader.presentation.viewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlLegacyCharsetTest {
    @Test
    fun decodes_by_the_charset_the_page_names() {
        val latin = "<meta charset=iso-8859-1><p>café €</p>"
        // As the standard says, the "iso-8859-1" label reads as windows-1252, where 0x80 is the euro.
        val bytes = latin.replace("€", "@").toByteArray(Charsets.ISO_8859_1)
        bytes[bytes.indexOf('@'.code.toByte())] = 0x80.toByte()
        assertEquals(latin, decodeHtml(bytes))

        val japanese = "<meta charset=shift_jis><p>日本語</p>"
        assertEquals(japanese, decodeHtml(japanese.toByteArray(charset("Shift_JIS"))))
    }

    @Test
    fun an_unknown_or_invalid_name_gives_null() {
        assertNull(decodeLegacyHtml(ByteArray(1), "no-such-charset"))
        assertNull(decodeLegacyHtml(ByteArray(1), "not a name!"))
    }
}
