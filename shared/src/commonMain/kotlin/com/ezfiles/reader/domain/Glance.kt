package com.ezfiles.reader.domain

/**
 * What a document says, for the reader's "At a glance" sheet: worked out on
 * the phone from the file's plain text, shown beside the file and never
 * changing how the file itself looks.
 */
data class DocumentGlance(
    /** The most central sentences, quoted as written, in document order. Empty for short texts. */
    val keySentences: List<String>,
    /** Strongest first, as written. */
    val keywords: List<String>,
    /** In document order. */
    val items: List<GlanceItem>,
    val words: Int,
    val readingMinutes: Int,
    /** English name of the language, when it is clear. */
    val language: String?,
)

enum class GlanceKind { Date, Amount, Phone, Email, Link, Reference }

/** Something in the document to copy or hand to another app. */
data class GlanceItem(
    val kind: GlanceKind,
    /** As written in the document. */
    val text: String,
    /**
     * For acting on: `YYYY-MM-DD` for a date, a plain number for an amount,
     * digits (with a leading `+` if written) for a phone number, the address,
     * an `http(s)` URL, or the reference itself.
     */
    val value: String,
    /** The currency, a link's site, or what a reference is ("Invoice number"). */
    val label: String?,
) {
    /** Year, month (1–12) and day of a date. */
    val date: Triple<Int, Int, Int>?
        get() {
            if (kind != GlanceKind.Date) return null
            val parts = value.split('-').mapNotNull { it.toIntOrNull() }
            return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
        }
}
