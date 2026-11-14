package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeDocumentKind
import com.ezfiles.engine.ffi.NativeLimits
import com.ezfiles.engine.ffi.NativeTextReader
import com.ezfiles.engine.ffi.textReaderOpenFd
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.platform.PdfOpener
import com.ezfiles.reader.platform.PdfPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A document's plain text, and where each PDF page starts in it (empty for other files). */
internal class PagedText(val text: String, val pageStarts: List<Int>)

/**
 * [DocumentText] read by the Rust engine straight from a descriptor: no block
 * objects are built, and reading stops once [DocumentText.plainText]'s
 * `maxChars` is reached. PDFs, which the engine leaves to the platform, are
 * read page by page through [PdfOpener]. Encrypted files give `null`: no
 * password is ever asked for. Blocking work runs on [io].
 */
internal class EngineDocumentText(
    private val access: DocumentAccess,
    private val pdf: PdfOpener,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentText {

    override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? =
        read(ref, maxChars)?.text?.takeIf { it.isNotBlank() }

    /** Like [plainText], with PDF page starts; `null` when unreadable. Rethrows cancellation. */
    suspend fun read(ref: DocumentRef, maxChars: Int): PagedText? {
        if (maxChars <= 0) return null
        var reader: NativeTextReader? = null
        try {
            // The engine only checks a PDF's header, so go straight to the platform's pages.
            if (ref.displayName.endsWith(".pdf", ignoreCase = true)) return pdfText(ref, maxChars)
            withContext(io) {
                // The descriptor goes straight to the engine, which owns it from here.
                val fd = access.openDescriptor(ref, LIMITS)
                reader = textReaderOpenFd(fd, ref.displayName, NATIVE_LIMITS)
            }
            val open = checkNotNull(reader)
            return when (open.kind()) {
                NativeDocumentKind.PDF -> {
                    open.close()
                    reader = null
                    pdfText(ref, maxChars)
                }
                // Pictures and archive listings say nothing a search would want.
                NativeDocumentKind.IMAGE, NativeDocumentKind.ARCHIVE -> null
                else -> PagedText(cancellable(open) { withContext(io) { open.read(maxChars.toUInt()) } }, emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Encrypted, damaged, unsupported or gone: nothing to read. A cancelled
            // engine read lands here too, so make sure cancellation still propagates.
            currentCoroutineContext().ensureActive()
            return null
        } finally {
            reader?.close()
        }
    }

    private suspend fun pdfText(ref: DocumentRef, maxChars: Int): PagedText? {
        // PdfPasswordRequired (and any other failure) is caught by read().
        val pages: PdfPages = withContext(io) { pdf.open(ref, null) }
        try {
            if (!pages.canSearch) return null
            val text = StringBuilder()
            val starts = ArrayList<Int>()
            val readablePages = if (pages.usesTextRecognition) minOf(pages.pageCount, 8) else pages.pageCount
            for (index in 0 until readablePages) {
                currentCoroutineContext().ensureActive()
                if (text.length >= maxChars) break
                val page = pages.pageText(index) ?: break
                if (text.isNotEmpty()) text.append('\n')
                starts += text.length
                appendCollapsed(text, page, maxChars)
            }
            return PagedText(text.toString(), starts)
        } finally {
            withContext(NonCancellable) { pages.close() }
        }
    }

    /**
     * Runs a blocking engine read that [NativeTextReader.cancel] can stop. The
     * watcher is unconfined, so it reacts even while the reading thread is busy.
     */
    private suspend fun <T> cancellable(reader: NativeTextReader, block: suspend () -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                reader.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        /** Very large files are skipped: their text isn't worth the memory on a small phone. */
        val LIMITS = ReadLimits(maxInputBytes = 128L * 1024 * 1024, maxBatchBytes = 64L * 1024, maxTotalOutputBytes = 4L * 1024 * 1024)
        val NATIVE_LIMITS = NativeLimits(
            maxInputBytes = LIMITS.maxInputBytes.toULong(),
            maxBatchBytes = LIMITS.maxBatchBytes.toULong(),
            maxTotalOutputBytes = LIMITS.maxTotalOutputBytes.toULong(),
        )
    }
}

/**
 * Appends [page] with each run of whitespace collapsed to one space, trimmed,
 * stopping at [maxChars] in total and never between the halves of a surrogate pair.
 */
internal fun appendCollapsed(out: StringBuilder, page: String, maxChars: Int) {
    var pendingSpace = false
    var started = false
    var i = 0
    while (i < page.length) {
        val c = page[i]
        if (c.isWhitespace() || c.isISOControl()) {
            pendingSpace = started
            i++
            continue
        }
        val width = if (c.isHighSurrogate() && i + 1 < page.length && page[i + 1].isLowSurrogate()) 2 else 1
        val needed = width + if (pendingSpace) 1 else 0
        if (out.length + needed > maxChars) return
        if (pendingSpace) out.append(' ')
        out.append(page, i, i + width)
        pendingSpace = false
        started = true
        i += width
    }
}
