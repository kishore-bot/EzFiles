package com.ezfiles.reader.domain

import kotlin.math.pow

/** Visits lose half their weight every week. */
const val FRECENCY_HALF_LIFE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Orders recent documents by frecency: each visit counts, and counts less the
 * older it is (half as much after [halfLifeMillis]). With one visit each this
 * is plain recency. Ties keep the given (newest-first) order.
 *
 * Scores are measured from the newest visit in the list rather than from the
 * clock: the common factor doesn't change the order, and nothing underflows.
 */
fun List<RecentDocument>.byFrecency(halfLifeMillis: Long = FRECENCY_HALF_LIFE_MILLIS): List<RecentDocument> {
    if (size < 2) return this
    val reference = maxOf { doc -> doc.visits.maxOrNull() ?: doc.openedAt }
    val scored = map { doc ->
        val visits = doc.visits.ifEmpty { listOf(doc.openedAt) }
        doc to visits.sumOf { 0.5.pow((reference - it).coerceAtLeast(0).toDouble() / halfLifeMillis) }
    }
    return scored.sortedByDescending { it.second }.map { it.first }
}
