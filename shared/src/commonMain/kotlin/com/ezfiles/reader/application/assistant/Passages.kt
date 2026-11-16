package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.domain.spokenNumber
import kotlin.math.ln

/**
 * Picks the parts of a document that answer a question, so a small model sees the relevant
 * paragraphs instead of only the first page. Plain term scoring (BM25-style), no embeddings.
 * PDF passages are labelled "[page N]", so answers can cite real pages.
 */
internal object Passages {
    /** Small enough that a 1,500-character budget holds about five separate passages. */
    private const val CHUNK_CHARS = 280
    private const val SEPARATOR = "\n…\n"

    private val stopWords = setOf(
        "the", "and", "for", "are", "was", "were", "what", "which", "who", "whom", "when", "where", "why", "how",
        "does", "did", "this", "that", "these", "those", "there", "with", "from", "about", "into", "say", "says",
        "said", "tell", "show", "find", "give", "please", "file", "document", "doc", "page", "pages", "text",
        "can", "you", "your", "mine", "any", "all", "much", "many", "have", "has", "had", "its", "they", "them",
        "summarize", "summary", "read", "explain", "mention", "mentioned", "fily", "hey", "okay",
    )
    private val pageRequest = Regex("""\bpage (\w+)\b""")

    private class Chunk(val text: String, val page: Int?)

    /**
     * At most [budget] characters, in document order; a page the question names; or the start of
     * the document when nothing matches. [pageStarts] are PDF page offsets into [text], if any.
     * The second value is true when the document holds more than was returned.
     */
    fun select(text: String, question: String, budget: Int, pageStarts: List<Int> = emptyList()): Pair<String, Boolean> {
        pageText(text, pageStarts, question, budget)?.let { return it }
        val terms = terms(question)
        if (text.length <= budget || terms.isEmpty()) return start(text, pageStarts, budget)
        val chunks = chunks(text, pageStarts)
        val words = chunks.map { chunk -> words(chunk.text).map(::stem) }
        val documentFrequency = terms.associateWith { term -> words.count { term in it } }
        val scores = words.map { chunkWords ->
            terms.sumOf { term ->
                val frequency = chunkWords.count { it == term }
                if (frequency == 0) 0.0
                else ln(1.0 + chunks.size.toDouble() / documentFrequency.getValue(term)) * frequency / (frequency + 1.2)
            }
        }
        val ranked = chunks.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }
        if (ranked.isEmpty()) return start(text, pageStarts, budget).first to true
        val chosen = ArrayList<Int>()
        var used = 0
        for (index in ranked) {
            val cost = labelled(chunks[index]).length + if (chosen.isEmpty()) 0 else SEPARATOR.length
            if (used + cost > budget) continue
            chosen += index
            used += cost
        }
        if (chosen.isEmpty()) chosen += ranked.first()
        return chosen.sorted().joinToString(SEPARATOR) { labelled(chunks[it]) }.take(budget) to true
    }

    /** "What does page 3 say?" in a PDF: that page's text. */
    private fun pageText(text: String, pageStarts: List<Int>, question: String, budget: Int): Pair<String, Boolean>? {
        if (pageStarts.isEmpty()) return null
        val page = pageRequest.find(question.lowercase())?.groupValues?.get(1)?.let(::spokenNumber) ?: return null
        if (page !in 1..pageStarts.size) return null
        val start = pageStarts[page - 1].coerceIn(0, text.length)
        val end = pageStarts.getOrNull(page)?.coerceIn(start, text.length) ?: text.length
        val body = "[page $page] " + text.substring(start, end).trim()
        return body.take(budget) to true
    }

    private fun start(text: String, pageStarts: List<Int>, budget: Int): Pair<String, Boolean> {
        val body = if (pageStarts.isNotEmpty()) "[page 1] $text" else text
        return body.take(budget) to (body.length > budget)
    }

    private fun labelled(chunk: Chunk): String = if (chunk.page != null) "[page ${chunk.page}] ${chunk.text}" else chunk.text

    private fun terms(question: String): Set<String> =
        words(question).filter { it.length >= 3 && it !in stopWords }.map(::stem).toSet()

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)

    /** Crude suffix folding, so "payments" matches "payment" and "due" matches "due". */
    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        else -> word
    }

    /** Per PDF page when page starts are known, so no passage spans two pages. */
    private fun chunks(text: String, pageStarts: List<Int>): List<Chunk> {
        if (pageStarts.isEmpty()) return chunk(text).map { Chunk(it, null) }
        return pageStarts.indices.flatMap { index ->
            val start = pageStarts[index].coerceIn(0, text.length)
            val end = pageStarts.getOrNull(index + 1)?.coerceIn(start, text.length) ?: text.length
            chunk(text.substring(start, end)).map { Chunk(it, index + 1) }
        }
    }

    /** Paragraphs, merged up to about [CHUNK_CHARS] and split at sentences when longer. */
    private fun chunk(text: String): List<String> {
        val pieces = text.split(Regex("\\n\\s*\\n|\\r?\\n")).map(String::trim).filter(String::isNotEmpty)
            .flatMap { paragraph ->
                if (paragraph.length <= CHUNK_CHARS) listOf(paragraph)
                else paragraph.split(Regex("(?<=[.!?])\\s+")).flatMap { it.chunked(CHUNK_CHARS) }
            }
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + piece.length + 1 > CHUNK_CHARS) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
