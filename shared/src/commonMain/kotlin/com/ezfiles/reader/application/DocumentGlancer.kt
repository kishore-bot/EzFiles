package com.ezfiles.reader.application

import com.ezfiles.reader.domain.DocumentGlance
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The algorithms behind "At a glance", in Rust (`document-glance`). Blocking and CPU bound. */
internal fun interface GlanceEngine {
    fun glance(text: String): DocumentGlance
}

/**
 * "At a glance" for an open document. Runs only when the sheet is opened, on
 * [background]; the last few results stay in memory so reopening the sheet is
 * instant. Nothing is written to disk and nothing leaves the phone.
 */
class DocumentGlancer internal constructor(
    private val documentText: DocumentText,
    private val engine: GlanceEngine,
    private val background: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val recent = LinkedHashMap<String, DocumentGlance>()

    /** `null` when the file has no readable text (scans, encrypted files, pictures). */
    suspend fun glance(ref: DocumentRef): DocumentGlance? {
        val key = "${ref.identity}|${ref.sizeBytes}|${ref.modifiedAt}"
        lock.withLock { recent[key] }?.let { return it }
        val text = try {
            documentText.plainText(ref, MAX_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) return null
        val result = withContext(background) { engine.glance(text) }
        if (result.words == 0) return null
        lock.withLock {
            recent.remove(key)
            recent[key] = result
            while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
        }
        return result
    }

    private companion object {
        const val MAX_CHARS = 200_000
        const val MAX_RECENT = 6
    }
}
