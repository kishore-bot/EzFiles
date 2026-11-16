package com.ezfiles.reader.application.assistant

import kotlin.math.ln

/**
 * Picks the parts of a document that answer a question, so a small model sees the relevant
 * paragraphs instead of only the first page. Plain term scoring (BM25-style), no embeddings.
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

    /** At most [budget] characters, in document order; the start of the document when nothing matches. */
    fun select(text: String, question: String, budget: Int): Pair<String, Boolean> {
        val terms = terms(question)
        if (text.length <= budget || terms.isEmpty()) return text.take(budget) to (text.length > budget)
        val chunks = chunk(text)
        val words = chunks.map { chunk -> words(chunk).map(::stem) }
        val documentFrequency = terms.associateWith { term -> words.count { term in it } }
        val scores = words.map { chunkWords ->
            terms.sumOf { term ->
                val frequency = chunkWords.count { it == term }
                if (frequency == 0) 0.0
                else ln(1.0 + chunks.size.toDouble() / documentFrequency.getValue(term)) * frequency / (frequency + 1.2)
            }
        }
        val ranked = chunks.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }
        if (ranked.isEmpty()) return text.take(budget) to true
        val chosen = ArrayList<Int>()
        var used = 0
        for (index in ranked) {
            val cost = chunks[index].length + if (chosen.isEmpty()) 0 else SEPARATOR.length
            if (used + cost > budget) continue
            chosen += index
            used += cost
        }
        if (chosen.isEmpty()) chosen += ranked.first()
        return chosen.sorted().joinToString(SEPARATOR) { chunks[it] }.take(budget) to true
    }

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
