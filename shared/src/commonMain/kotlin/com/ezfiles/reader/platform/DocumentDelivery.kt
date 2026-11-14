package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import com.ezfiles.reader.domain.DocumentRef

/** Visible system delivery. Only references the user has already authorized may be passed. */
interface DocumentDelivery {
    /** Shares original bytes, with a temporary read grant; never grants write access. */
    suspend fun share(refs: List<DocumentRef>)

    /** Opens the system print UI. Protected PDFs still require their permitted password. */
    suspend fun print(ref: DocumentRef, password: String? = null)

    /** PDF, supported pictures and plain-text sources; rich documents need an explicit export first. */
    fun canPrint(ref: DocumentRef): Boolean

    suspend fun shareText(text: String, title: String = "EzFiles text")
}

@Composable
expect fun rememberDocumentDelivery(): DocumentDelivery

internal enum class PrintKind { Pdf, Image, Text }

internal fun printKind(ref: DocumentRef): PrintKind? = when (ref.displayName.substringAfterLast('.', "").lowercase()) {
    "pdf" -> PrintKind.Pdf
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> PrintKind.Image
    "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
    "toml", "ini", "cfg", "kt", "kts", "java", "py", "js", "ts", "css", "rs", "c", "h", "cpp", "sh" -> PrintKind.Text
    else -> null
}

/** Clamp and merge printer ranges before enumerating pages, including ALL_PAGES (Int.MAX_VALUE). */
internal fun selectedPrintRanges(pageCount: Int, ranges: List<IntRange>): List<IntRange> {
    if (pageCount <= 0) return emptyList()
    val sorted = ranges.mapNotNull { range ->
        val first = maxOf(0, range.first)
        val last = minOf(pageCount - 1, range.last)
        if (first <= last) first..last else null
    }.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (range in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first.toLong() <= previous.last.toLong() + 1L) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
