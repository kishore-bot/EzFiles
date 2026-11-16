package com.ezfiles.reader.domain

/**
 * Port for the plain text of a document, for offline features that look at what
 * a file says (search, near-duplicate detection, collections) without showing it.
 *
 * Implementations read through the existing engine and PDF text APIs, stay within
 * [maxChars], never ask for or keep passwords (encrypted files give `null`), and
 * are cancellable. Text is whitespace-normalised: blocks and pages are separated
 * by a newline, runs of spaces collapse to one.
 */
/** A document's plain text, and the offset where each PDF page starts in it (empty for other files). */
class PagedText(val text: String, val pageStarts: List<Int>)

interface DocumentText {
    /**
     * Up to [maxChars] characters of [ref]'s text, in reading order, or `null`
     * if the file has no extractable text, is encrypted, or cannot be read.
     */
    suspend fun plainText(ref: DocumentRef, maxChars: Int = DEFAULT_MAX_CHARS): String?

    /** Like [plainText], with where each PDF page starts; no page starts for other files. */
    suspend fun pagedText(ref: DocumentRef, maxChars: Int = DEFAULT_MAX_CHARS): PagedText? =
        plainText(ref, maxChars)?.let { PagedText(it, emptyList()) }

    companion object {
        /** Enough for ranking and fingerprints, small enough for a 2 GB phone. */
        const val DEFAULT_MAX_CHARS: Int = 200_000
    }
}
