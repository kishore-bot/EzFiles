package com.ezfiles.reader.application.assistant

/**
 * Picks the few tools a request plausibly needs. The model was trained with 2-5 tools in its
 * prompt, never the whole catalog, and a short list also keeps the prompt fast to read.
 */
internal object ToolSelector {
    private const val MAX_TOOLS = 5

    private val rules: List<Pair<Regex, List<String>>> = listOf(
        """\b(scan|scanner|camera)\b""" to listOf("openScanner"),
        """\b(text from|ocr|read (the )?text (in|from)) .*(image|photo|picture)|\bimage to text\b""" to listOf("openImageToText"),
        """\b(share|send)\b""" to listOf("prepareShareDocument", "prepareShareDocuments"),
        """\bprint\b""" to listOf("preparePrintDocument"),
        """\bbookmark""" to listOf("listBookmarks", "openBookmark", "addBookmark"),
        """\b(merge|combine|join)\b""" to listOf("mergePdfDocuments"),
        """\b(extract|split|pull out)\b.*\bpages?\b""" to listOf("extractPdfPages"),
        """\b(organi[sz]e|reorder|rearrange)\b.*\bpages?\b""" to listOf("prepareOrganizePdf"),
        """\bresize\b|\bpage size\b""" to listOf("prepareResizePdf"),
        """\b(pdf|pages?) (to|into|as) (images?|pictures?|photos?|jpe?g|png)\b""" to listOf("preparePdfToImages"),
        """\b(pdf|it) (to|into|as) (text|txt)\b""" to listOf("preparePdfToText"),
        """\bzip\b""" to listOf("preparePdfToZip"),
        """\bpassword\b""" to listOf("openPasswordTool"),
        """\bpdf tools?\b""" to listOf("openPdfTools"),
        """\b(images?|photos?|pictures?) (to|into) (a )?pdf\b""" to listOf("openImagesToPdf", "createPdfFromImages"),
        """\btext (to|into) (a )?pdf\b|\b(make|create|write) (a )?pdf\b""" to listOf("openTextToPdf", "createPdfFromText"),
        """\b(note|text file|draft|write down|jot)\b""" to listOf("openTextEditor", "createTextDocument"),
        """\b(tidy|clean ?up|duplicates?|copies|declutter)\b""" to listOf("openTidyUp", "startTidyScan", "getTidyReport"),
        """\b(similar|look-?alikes?)\b""" to listOf("openSimilarDocuments"),
        """\b(page|zoom|scroll|play|pause|resume|seek|subtitles?|audio track|rotate|next|previous|back|contents|source|preview|cell|sheet)\b""" to listOf("controlReader"),
        """\b(open|show me|launch|view)\b""" to listOf("openDocument"),
        """\b(recent|recently|last opened|opened)\b""" to listOf("listRecentDocuments"),
        """\b(find|search|where|look for|latest|newest|oldest|biggest|largest|which|any)\b""" to listOf("searchLibrary"),
        """\b(word|phrase|mention|mentions|occurrences?|appear)\b""" to listOf("findTextInDocument"),
        """\b(say|says|said|about|summar|amount|total|due|deadline|date|who|what|how much|read)\b""" to listOf("getDocumentExcerpt"),
        """\b(how big|size|when (was|did)|modified|details|info)\b""" to listOf("getDocumentInfo"),
    ).map { (pattern, tools) -> Regex(pattern) to tools }

    fun select(request: String, screen: FilyScreen, available: Set<String>): List<String> {
        val text = request.lowercase()
        val matched = rules.filter { (regex, _) -> regex.containsMatchIn(text) }.flatMap { it.second }
        val base = if (screen.selectedDocumentId != null) {
            listOf("getDocumentExcerpt", "findTextInDocument", "searchLibrary")
        } else {
            listOf("searchLibrary", "openDocument", "listRecentDocuments")
        }
        val readerOnly = setOf("controlReader")
        return (matched + base)
            .filter { it in available }
            .filter { it !in readerOnly || screen.readerCapabilities.isNotEmpty() }
            .distinct()
            .take(MAX_TOOLS)
    }
}
