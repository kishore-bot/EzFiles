package com.ezfiles.reader.data

import com.ezfiles.reader.platform.DrawnSignature
import com.ezfiles.reader.platform.MAX_SAVED_SIGNATURES
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.platform.SavedSignatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SavedSignatures] persisted as JSON in the app's private storage. Unreadable
 * data is discarded rather than failing the app, and is read only when a
 * signature tool first asks, not at every start.
 */
internal class SignatureStore(private val storage: KeyValueStorage) : SavedSignatures {
    private val state by lazy { MutableStateFlow(load()) }
    override val signatures: StateFlow<List<DrawnSignature>> get() = state

    override fun save(signature: DrawnSignature): DrawnSignature? {
        val current = state.value
        if (current.size >= MAX_SAVED_SIGNATURES) return null
        val stored = signature.copy(id = (current.maxOfOrNull { it.id } ?: 0L) + 1)
        write(current + stored)
        return stored
    }

    override fun delete(id: Long) {
        val current = state.value
        val remaining = current.filterNot { it.id == id }
        if (remaining.size != current.size) write(remaining)
    }

    private fun write(list: List<DrawnSignature>) {
        storage.put(KEY, json.encodeToString(list.map(StoredSignature::from)))
        state.value = list
    }

    private fun load(): List<DrawnSignature> = try {
        storage.get(KEY)
            ?.takeIf { it.length <= MAX_STORED_CHARS }
            ?.let { json.decodeFromString<List<StoredSignature>>(it) }
            ?.mapNotNull(StoredSignature::toDomain)
            ?.distinctBy { it.id }
            ?.take(MAX_SAVED_SIGNATURES)
            .orEmpty()
    } catch (e: IllegalArgumentException) {
        // Corrupt or from an incompatible version: start fresh.
        emptyList()
    }

    /** Coordinates as whole numbers, each stroke flat (x, y, x, y...): far smaller than JSON floats. */
    @Serializable
    private data class StoredSignature(
        val id: Long,
        val aspect: Float,
        val color: Int,
        val pen: Float,
        val strokes: List<List<Int>>,
    ) {
        /** `null` when it doesn't describe a drawable signature: this comes back from storage, so it isn't trusted. */
        fun toDomain(): DrawnSignature? {
            if (!aspect.isFinite() || aspect <= 0f || !pen.isFinite() || pen <= 0f) return null
            val points = strokes.map { flat ->
                if (flat.isEmpty() || flat.size % 2 != 0 || flat.any { it !in 0..SCALE }) return null
                flat.chunked(2) { (x, y) -> PdfPoint(x.toFloat() / SCALE, y.toFloat() / SCALE) }
            }
            if (points.isEmpty()) return null
            return DrawnSignature(id, points, aspect, color, pen)
        }

        companion object {
            fun from(signature: DrawnSignature) = with(signature) {
                StoredSignature(
                    id,
                    aspect,
                    color,
                    penWidth,
                    strokes.map { stroke -> stroke.flatMap { listOf(scaled(it.x), scaled(it.y)) } },
                )
            }

            private fun scaled(value: Float): Int = (value * SCALE + 0.5f).toInt().coerceIn(0, SCALE)
        }
    }

    private companion object {
        const val KEY = "saved_signatures_v1"
        const val SCALE = 10_000
        const val MAX_STORED_CHARS = 1_000_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
