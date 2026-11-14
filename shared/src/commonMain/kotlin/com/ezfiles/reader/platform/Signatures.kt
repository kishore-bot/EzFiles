package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** How many drawn signatures are kept for reuse. */
const val MAX_SAVED_SIGNATURES = 5

/**
 * A drawing of a signature, not a digital signature. [strokes] are fractions
 * (0..1) of its own box, [aspect] is that box's width over height, and
 * [penWidth] is a fraction of the box width, so it scales with the drawing.
 */
data class DrawnSignature(
    val id: Long,
    val strokes: List<List<PdfPoint>>,
    val aspect: Float,
    val color: Int,
    val penWidth: Float,
)

/** Signatures kept as vector strokes in app-private storage. Nothing here leaves the device. */
interface SavedSignatures {
    val signatures: StateFlow<List<DrawnSignature>>

    /** The stored copy with its new id, or `null` when [MAX_SAVED_SIGNATURES] are kept already: none is replaced silently. */
    fun save(signature: DrawnSignature): DrawnSignature?

    fun delete(id: Long)
}

val LocalSavedSignatures = staticCompositionLocalOf<SavedSignatures> { error("SavedSignatures not provided") }
