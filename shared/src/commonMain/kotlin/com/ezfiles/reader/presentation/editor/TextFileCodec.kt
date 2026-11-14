package com.ezfiles.reader.presentation.editor

/**
 * The most a file may hold to be edited, in bytes, and the most characters the
 * editor holds. The text field lays out all of its text again on every
 * keystroke, so the cost of typing grows with the file. 256 KiB is about 6,000
 * lines of code, which should keep that cost to tens of milliseconds on a
 * mid-range phone (an estimate, not yet measured on a device). It also keeps
 * "Select all, Copy" under Android's 1 MiB binder limit: 256 K characters are
 * 512 KiB in a clipboard parcel, and crossing the limit throws from the
 * framework's clipboard call.
 */
const val MAX_EDITABLE_BYTES: Int = 256 * 1024

enum class TextEncoding(val label: String) {
    Utf8("UTF-8"),
    Utf8Bom("UTF-8 BOM"),
    Utf16Le("UTF-16 LE"),
    Utf16Be("UTF-16 BE"),
}

enum class LineEnding(val label: String, val sequence: String) {
    Lf("LF", "\n"),
    CrLf("CRLF", "\r\n"),
    Cr("CR", "\r"),
}

/** What must be restored when saving, beyond the text. */
data class TextFileFormat(val encoding: TextEncoding, val lineEnding: LineEnding) {
    val label: String get() = "${encoding.label} · ${lineEnding.label}"

    companion object {
        val New = TextFileFormat(TextEncoding.Utf8, LineEnding.Lf)
    }
}

/** Why a file can't be edited without risking its content. */
enum class TextRefusal {
    /** Not valid UTF-8, or UTF-16 with a byte order mark: probably a legacy encoding. */
    InvalidEncoding,

    /** Holds NUL characters, so it is binary, UTF-16 without a BOM, or UTF-32. */
    NotText,

    /** More than one kind of line ending, which one setting can't restore. */
    MixedLineEndings,

    /** Encoding the decoded text did not give back the same bytes. */
    NotReproducible,
}

sealed interface TextDecodeResult {
    /** [text] uses `\n` for every line break. */
    data class Decoded(val text: String, val format: TextFileFormat) : TextDecodeResult

    data class Refused(val reason: TextRefusal) : TextDecodeResult
}

/**
 * Turns a text file's bytes into editor text and back, exactly. A file is only
 * accepted if encoding its decoded text gives back the same bytes, so saving
 * can change nothing but what the user edited. A final newline is ordinary
 * text: it stays unless the user deletes it.
 */
object TextFileCodec {
    private val Utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val Utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val Utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun decode(bytes: ByteArray): TextDecodeResult {
        val encoding = when {
            bytes.startsWith(Utf8Bom) -> TextEncoding.Utf8Bom
            bytes.startsWith(Utf16LeBom) -> TextEncoding.Utf16Le
            bytes.startsWith(Utf16BeBom) -> TextEncoding.Utf16Be
            else -> TextEncoding.Utf8
        }
        val raw = when (encoding) {
            TextEncoding.Utf8 -> decodeUtf8(bytes, 0)
            TextEncoding.Utf8Bom -> decodeUtf8(bytes, Utf8Bom.size)
            TextEncoding.Utf16Le -> decodeUtf16(bytes, littleEndian = true)
            TextEncoding.Utf16Be -> decodeUtf16(bytes, littleEndian = false)
        } ?: return TextDecodeResult.Refused(TextRefusal.InvalidEncoding)
        if ('\u0000' in raw) return TextDecodeResult.Refused(TextRefusal.NotText)
        val lineEnding = detectLineEnding(raw) ?: return TextDecodeResult.Refused(TextRefusal.MixedLineEndings)
        val format = TextFileFormat(encoding, lineEnding)
        val text = normalizeLineEndings(raw)
        val again = encode(text, format)
        if (again == null || !again.contentEquals(bytes)) return TextDecodeResult.Refused(TextRefusal.NotReproducible)
        return TextDecodeResult.Decoded(text, format)
    }

    /**
     * The file's bytes for [text], or `null` if [format] can't represent it
     * faithfully: a broken surrogate pair, a NUL, or a leading U+FEFF that
     * would read back as a byte order mark.
     */
    fun encode(text: String, format: TextFileFormat): ByteArray? {
        if ('\u0000' in text) return null
        val normalized = normalizeLineEndings(text)
        val lines = if (format.lineEnding == LineEnding.Lf) normalized else normalized.replace("\n", format.lineEnding.sequence)
        return when (format.encoding) {
            TextEncoding.Utf8 -> if (lines.startsWith('﻿')) null else encodeUtf8(lines)
            TextEncoding.Utf8Bom -> encodeUtf8(lines)?.let { Utf8Bom + it }
            TextEncoding.Utf16Le -> encodeUtf16(lines, Utf16LeBom, littleEndian = true)
            TextEncoding.Utf16Be -> encodeUtf16(lines, Utf16BeBom, littleEndian = false)
        }
    }

    /** Turns `\r\n` and lone `\r` into `\n`, for text from a file or the clipboard. */
    fun normalizeLineEndings(text: String): String =
        if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

    /** `null` if the text mixes line endings. Text with no line break counts as LF. */
    private fun detectLineEnding(text: String): LineEnding? {
        var lf = false
        var crLf = false
        var cr = false
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> lf = true
                '\r' -> if (text.getOrNull(i + 1) == '\n') {
                    crLf = true
                    i++
                } else {
                    cr = true
                }
            }
            i++
        }
        return when {
            listOf(lf, crLf, cr).count { it } > 1 -> null
            crLf -> LineEnding.CrLf
            cr -> LineEnding.Cr
            else -> LineEnding.Lf
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun decodeUtf8(bytes: ByteArray, start: Int): String? = try {
        bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    private fun encodeUtf8(text: String): ByteArray? = try {
        text.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String? {
        val start = Utf16LeBom.size
        if ((bytes.size - start) % 2 != 0) return null
        val chars = CharArray((bytes.size - start) / 2) { index ->
            val first = bytes[start + index * 2].toInt() and 0xFF
            val second = bytes[start + index * 2 + 1].toInt() and 0xFF
            (if (littleEndian) first or (second shl 8) else (first shl 8) or second).toChar()
        }
        return chars.concatToString().takeIf(::hasValidSurrogates)
    }

    private fun encodeUtf16(text: String, bom: ByteArray, littleEndian: Boolean): ByteArray? {
        if (!hasValidSurrogates(text)) return null
        val bytes = ByteArray(bom.size + text.length * 2)
        bom.copyInto(bytes)
        for (index in text.indices) {
            val code = text[index].code
            val low = (code and 0xFF).toByte()
            val high = (code shr 8).toByte()
            val at = bom.size + index * 2
            bytes[at] = if (littleEndian) low else high
            bytes[at + 1] = if (littleEndian) high else low
        }
        return bytes
    }

    private fun hasValidSurrogates(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char.isLowSurrogate()) return false
            if (char.isHighSurrogate()) {
                if (text.getOrNull(i + 1)?.isLowSurrogate() != true) return false
                i++
            }
            i++
        }
        return true
    }
}

/**
 * The MIME type for the system save dialog. Providers add an extension when a
 * name doesn't match its type (`config.yaml.txt`), while an
 * `application/octet-stream` file keeps the name as given.
 */
fun textMimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "txt" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "tsv" -> "text/tab-separated-values"
    "json" -> "application/json"
    "xml" -> "text/xml"
    else -> "application/octet-stream"
}
