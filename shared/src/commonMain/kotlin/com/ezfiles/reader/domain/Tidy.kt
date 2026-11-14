package com.ezfiles.reader.domain

/**
 * "Tidy up": what a scan of the library's folders found. Nothing here
 * changes a file; the user decides what to open, share or delete.
 */
data class TidyReport(
    /** When the scan finished, in epoch milliseconds. */
    val scannedAt: Long,
    /** Files looked at. */
    val fileCount: Int,
    val identical: List<IdenticalGroup>,
    val similarDocuments: List<SimilarGroup>,
    val similarPhotos: List<SimilarGroup>,
    val collections: List<TidyCollection>,
) {
    val isEmpty: Boolean
        get() = identical.isEmpty() && similarDocuments.isEmpty() && similarPhotos.isEmpty() && collections.isEmpty()

    /** Space that removing every extra copy of an identical file would free. */
    val reclaimableBytes: Long get() = identical.sumOf { it.reclaimableBytes }
}

/** Byte-for-byte copies of one file. */
data class IdenticalGroup(val files: List<DocumentRef>, val sizeBytes: Long) {
    /** Keeping one copy frees this much. */
    val reclaimableBytes: Long get() = sizeBytes * (files.size - 1).coerceAtLeast(0)
}

/** Files that are nearly the same: one document edited, one photo saved twice. */
data class SimilarGroup(val files: List<DocumentRef>)

enum class CollectionKind(val title: String) {
    Receipts("Receipts & bills"),
    Travel("Tickets & travel"),
    BankStatements("Banking & statements"),
    Certificates("Certificates & IDs"),
    PayAndTax("Pay & tax"),
    Medical("Medical"),
    Resumes("Resumes & CVs"),
}

/** Why a file is in a collection, as the engine found it. */
data class CollectionReason(
    val terms: List<String> = emptyList(),
    val nameTerms: List<String> = emptyList(),
    val amounts: Int = 0,
    val dates: Int = 0,
) {
    /** For example "mentions 'invoice', 'subtotal' · 3 amounts". */
    fun describe(): String {
        val parts = buildList {
            if (terms.isNotEmpty()) add("mentions " + terms.joinToString(", ") { "‘$it’" })
            if (nameTerms.isNotEmpty()) add("named " + nameTerms.joinToString(", ") { "‘$it’" })
            if (amounts > 0) add(if (amounts == 1) "1 amount" else "$amounts amounts")
            if (dates > 0) add(if (dates == 1) "1 date" else "$dates dates")
        }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

data class CollectionEntry(val file: DocumentRef, val reason: CollectionReason)

data class TidyCollection(val kind: CollectionKind, val entries: List<CollectionEntry>)

/** The step a running scan is on, for its progress line. */
enum class TidyPhase(val label: String) {
    Listing("Listing files"),
    Identical("Looking for identical files"),
    Documents("Reading documents"),
    Photos("Looking at photos"),
    Grouping("Grouping"),
}

data class TidyProgress(val phase: TidyPhase, val done: Int = 0, val total: Int = 0) {
    /** 0..1, or `null` while the total isn't known. */
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

/** Kinds of sensitive number found in a document. The numbers themselves are never kept. */
data class SensitiveFindings(val paymentCard: Boolean = false, val nationalId: Boolean = false, val bankAccount: Boolean = false) {
    val any: Boolean get() = paymentCard || nationalId || bankAccount

    /** For the hint: "a card number", "an ID number and a bank account number". */
    fun describe(): String {
        val kinds = buildList {
            if (paymentCard) add("a card number")
            if (nationalId) add("an ID number")
            if (bankAccount) add("a bank account number")
        }
        return when (kinds.size) {
            0 -> ""
            1 -> kinds[0]
            else -> kinds.dropLast(1).joinToString(", ") + " and " + kinds.last()
        }
    }
}
