package com.ezfiles.reader.application

import com.ezfiles.reader.domain.CollectionKind
import com.ezfiles.reader.domain.CollectionReason
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.SensitiveFindings

/**
 * The algorithms behind Tidy up, implemented in Rust (`document-tidy`). Every
 * call is blocking and CPU or I/O bound; callers run them off the main thread.
 */
internal interface TidyEngine {
    /**
     * Groups identical files. Only files sharing a size are opened, through
     * [source]; hashes already in [files] are reused, and all known ones come
     * back for caching.
     */
    fun findIdentical(files: List<HashInput>, source: HashSource): IdenticalHashes

    /** One pass over a document's text: its SimHash and its collection. */
    fun analyzeText(name: String, text: String): TextFacts

    /** The collection a file belongs to by its name alone. */
    fun classifyName(name: String): CollectionMatch?

    /** dHash of a [width] x [height] luminance buffer; `null` if flat or too small. */
    fun imageFingerprint(width: Int, height: Int, gray: ByteArray): Long?

    /** Indices of fingerprints near each other; [images] picks the photo threshold. */
    fun groupSimilar(fingerprints: List<Long>, images: Boolean): List<List<Int>>

    fun sensitive(text: String): SensitiveFindings
}

internal data class HashInput(val size: Long, val partial: Long? = null, val full: Long? = null)

internal data class IdenticalHashes(val groups: List<List<Int>>, val partial: List<Long?>, val full: List<Long?>)

internal data class TextFacts(val fingerprint: Long?, val collection: CollectionMatch?)

internal data class CollectionMatch(val kind: CollectionKind, val reason: CollectionReason)

/** Called back from the engine on the scanning thread. Must not throw. */
internal interface HashSource {
    /** An open descriptor for file [index], owned by the engine from here; `null` if it can't be opened. */
    fun open(index: Int): Int?

    fun onProgress(done: Int, total: Int)

    fun isCancelled(): Boolean
}

/**
 * The platform side of Tidy up: opening files, decoding small previews of
 * pictures, and the user's explicit actions on a file.
 */
interface TidyFiles {
    /** Blocking. A readable descriptor for [ref] that the caller then owns, or `null`. */
    fun openDescriptor(ref: DocumentRef): Int?

    /**
     * Blocking. [ref] decoded at about [side] x [side] pixels as one luminance
     * byte per pixel, decoding as little of the file as the format allows.
     */
    fun grayThumbnail(ref: DocumentRef, side: Int): GrayImage?

    /** Opens the system share sheet for [ref]; false if nothing can take it. */
    fun share(ref: DocumentRef): Boolean

    /** Whether [delete] can work on this platform. */
    val canDelete: Boolean

    /**
     * Deletes [ref]. Only called after the user confirmed it in the app.
     * Blocking; false if the provider refused.
     */
    fun delete(ref: DocumentRef): Boolean

    /** Re-read both files, keep [retained], and delete only if their bytes still match. */
    fun deleteDuplicate(ref: DocumentRef, retained: DocumentRef): Boolean = false
}

class GrayImage(val width: Int, val height: Int, val pixels: ByteArray)
