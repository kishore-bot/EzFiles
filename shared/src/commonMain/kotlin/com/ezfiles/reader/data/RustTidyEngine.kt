package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeCollection
import com.ezfiles.engine.ffi.NativeCollectionMatch
import com.ezfiles.engine.ffi.NativeSimilarKind
import com.ezfiles.engine.ffi.NativeTidyFile
import com.ezfiles.engine.ffi.NativeTidyListener
import com.ezfiles.engine.ffi.NativeTidySource
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.tidyAnalyzeText
import com.ezfiles.engine.ffi.tidyClassifyName
import com.ezfiles.engine.ffi.tidyFindIdentical
import com.ezfiles.engine.ffi.tidyGroupSimilar
import com.ezfiles.engine.ffi.tidyImageFingerprint
import com.ezfiles.engine.ffi.tidySensitiveScan
import com.ezfiles.reader.application.CollectionMatch
import com.ezfiles.reader.application.HashInput
import com.ezfiles.reader.application.HashSource
import com.ezfiles.reader.application.IdenticalHashes
import com.ezfiles.reader.application.TextFacts
import com.ezfiles.reader.application.TidyEngine
import com.ezfiles.reader.domain.CollectionKind
import com.ezfiles.reader.domain.CollectionReason
import com.ezfiles.reader.domain.SensitiveFindings
import kotlinx.coroutines.CancellationException

/** [TidyEngine] over the Gobley-generated bindings to `document-ffi`'s tidy module. */
internal class RustTidyEngine : TidyEngine {
    override fun findIdentical(files: List<HashInput>, source: HashSource): IdenticalHashes {
        val listener = object : NativeTidyListener {
            override fun open(index: UInt): NativeTidySource? = try {
                source.open(index.toInt())?.let { NativeTidySource(it, null) }
            } catch (e: Exception) {
                // Must not throw across the boundary: an unreadable file is left out.
                null
            }

            override fun onProgress(done: UInt, total: UInt) = source.onProgress(done.toInt(), total.toInt())

            override fun isCancelled(): Boolean = source.isCancelled()
        }
        val native = files.map { NativeTidyFile(it.size.toULong(), it.partial?.toULong(), it.full?.toULong()) }
        val result = try {
            tidyFindIdentical(native, listener)
        } catch (e: ReadException.Cancelled) {
            throw CancellationException("Tidy scan cancelled")
        }
        return IdenticalHashes(
            groups = result.groups.map { group -> group.map { it.toInt() } },
            partial = result.partialHashes.map { it?.toLong() },
            full = result.fullHashes.map { it?.toLong() },
        )
    }

    override fun analyzeText(name: String, text: String): TextFacts {
        val facts = tidyAnalyzeText(name, text)
        return TextFacts(facts.fingerprint?.toLong(), facts.collection?.toDomain())
    }

    override fun classifyName(name: String): CollectionMatch? = tidyClassifyName(name)?.toDomain()

    override fun imageFingerprint(width: Int, height: Int, gray: ByteArray): Long? =
        tidyImageFingerprint(width.toUInt(), height.toUInt(), gray)?.toLong()

    override fun groupSimilar(fingerprints: List<Long>, images: Boolean): List<List<Int>> =
        tidyGroupSimilar(fingerprints.map { it.toULong() }, if (images) NativeSimilarKind.IMAGES else NativeSimilarKind.DOCUMENTS)
            .map { group -> group.map { it.toInt() } }

    override fun sensitive(text: String): SensitiveFindings {
        val found = tidySensitiveScan(text)
        return SensitiveFindings(found.paymentCard, found.nationalId, found.bankAccount)
    }

    private fun NativeCollectionMatch.toDomain() = CollectionMatch(
        kind = when (collection) {
            NativeCollection.RECEIPTS -> CollectionKind.Receipts
            NativeCollection.TRAVEL -> CollectionKind.Travel
            NativeCollection.BANK_STATEMENTS -> CollectionKind.BankStatements
            NativeCollection.CERTIFICATES -> CollectionKind.Certificates
            NativeCollection.PAY_AND_TAX -> CollectionKind.PayAndTax
            NativeCollection.MEDICAL -> CollectionKind.Medical
            NativeCollection.RESUMES -> CollectionKind.Resumes
        },
        reason = CollectionReason(terms, nameTerms, amounts.toInt(), dates.toInt()),
    )
}
