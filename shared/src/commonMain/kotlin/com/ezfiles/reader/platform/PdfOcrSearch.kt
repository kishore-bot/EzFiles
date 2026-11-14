package com.ezfiles.reader.platform

import com.ezfiles.reader.domain.RecognizedText

/** OCR geometry is line-level; a wrapped phrase highlights every line it intersects. */
internal fun searchRecognizedPdf(page: RecognizedText, query: String): List<PdfTextMatch> {
    val needle = normalizePdfOcr(query)
    if (needle.isEmpty()) return emptyList()
    val text = StringBuilder()
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val boxes = ArrayList<PdfBox>()
    for (line in page.lines) {
        val words = normalizePdfOcr(line.text)
        if (words.isEmpty()) continue
        if (text.isNotEmpty()) text.append(' ')
        starts += text.length
        text.append(words)
        ends += text.length
        boxes += PdfBox(line.left, line.top, line.right, line.bottom)
    }
    val contents = text.toString()
    val matches = ArrayList<PdfTextMatch>()
    var from = 0
    while (from < contents.length) {
        val found = contents.indexOf(needle, from, ignoreCase = true)
        if (found < 0) break
        val end = found + needle.length
        // The first line whose end is after the match start; avoid scanning every line per hit.
        var low = 0
        var high = ends.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= found) low = middle + 1 else high = middle
        }
        val hit = ArrayList<PdfBox>()
        var line = low
        while (line < starts.size && starts[line] < end) { hit += boxes[line]; line++ }
        if (hit.isNotEmpty()) matches += PdfTextMatch(hit)
        from = end
    }
    return matches
}

private fun normalizePdfOcr(value: String): String = buildString(value.length) {
    var space = false
    for (character in value) {
        if (character.isWhitespace()) {
            if (isNotEmpty()) space = true
        } else {
            if (space) append(' ')
            append(character)
            space = false
        }
    }
}
