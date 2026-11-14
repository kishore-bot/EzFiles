package com.ezfiles.reader.presentation.pdftools

/** The outcome of reading what the user typed as pages. */
internal sealed interface PageRangeResult {
    /** Zero-based pages, in the order typed, each once. */
    data class Pages(val pages: List<Int>) : PageRangeResult

    data class Invalid(val message: String) : PageRangeResult
}

private val AROUND_DASH = Regex("""\s*[-‐‑‒–—−]\s*""")
private val SEPARATORS = Regex("""[,;\s]+""")

/**
 * Reads input like `1-3, 7` against a document of [pageCount] pages. Checks each
 * range against the document before expanding it, so no input can cost more
 * than the document's own length.
 */
internal fun parsePageRanges(input: String, pageCount: Int): PageRangeResult {
    val tokens = input.trim().replace(AROUND_DASH, "-").split(SEPARATORS).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return PageRangeResult.Invalid("Enter pages, for example 1-3, 7.")
    val pages = LinkedHashSet<Int>()
    for (token in tokens) {
        val parts = token.split('-')
        if (parts.size > 2 || parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) {
            return PageRangeResult.Invalid("\"$token\" isn't a page or a range. Write it like 1-3, 7.")
        }
        // Digits too long for an Int are simply past the end.
        val first = parts.first().toIntOrNull() ?: Int.MAX_VALUE
        val last = parts.last().toIntOrNull() ?: Int.MAX_VALUE
        when {
            first < 1 || last < 1 -> return PageRangeResult.Invalid("Pages start at 1.")
            first > pageCount || last > pageCount -> return PageRangeResult.Invalid(
                "\"$token\" is past the end. This PDF has ${pageCountLabel(pageCount)}.",
            )
            first > last -> return PageRangeResult.Invalid("\"$token\" runs backwards. Write it as $last-$first.")
        }
        for (page in first..last) pages += page - 1
    }
    return PageRangeResult.Pages(pages.toList())
}

/** Zero-based pages of each file when [pageCount] pages are split every [everyPages]. */
internal fun pageChunks(pageCount: Int, everyPages: Int): List<IntRange> {
    require(everyPages >= 1) { "everyPages must be at least 1" }
    return (0 until pageCount step everyPages).map { start -> start..minOf(start.toLong() + everyPages - 1, pageCount - 1L).toInt() }
}

/** `3` as `03` when the largest number is up to 99, so the files sort in order. */
internal fun paddedNumber(number: Int, largest: Int): String = number.toString().padStart(largest.toString().length, '0')

internal fun pageCountLabel(count: Int): String = if (count == 1) "1 page" else "$count pages"
