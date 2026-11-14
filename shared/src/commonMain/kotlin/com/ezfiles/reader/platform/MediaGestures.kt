package com.ezfiles.reader.platform

enum class MediaDrag { Seek, Brightness, Volume }

/** One horizontal screen spans at most two minutes, independent of a long movie's duration. */
internal fun draggedMediaPosition(startMs: Long, dragPx: Float, widthPx: Float, durationMs: Long): Long {
    if (widthPx <= 0 || !dragPx.isFinite()) return startMs.coerceIn(0, durationMs.coerceAtLeast(0))
    val span = minOf(durationMs.coerceAtLeast(0), 120_000L)
    return (startMs + dragPx.toDouble() / widthPx * span).toLong().coerceIn(0, durationMs.coerceAtLeast(0))
}

internal fun draggedMediaLevel(start: Float, dragPx: Float, heightPx: Float): Float =
    if (heightPx <= 0 || !dragPx.isFinite()) start.coerceIn(0f, 1f) else (start - dragPx / heightPx * 1.5f).coerceIn(0f, 1f)
