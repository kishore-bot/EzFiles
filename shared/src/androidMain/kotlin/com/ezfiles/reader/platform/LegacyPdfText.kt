package com.ezfiles.reader.platform

import android.graphics.Bitmap
import android.util.LruCache
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.RecognizedLine
import com.ezfiles.reader.domain.RecognizedText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Uses the existing bundled Latin model only on devices without native PDF text APIs. */
internal class LegacyPdfText(private val render: suspend (Int) -> Bitmap?) {
    private val work = Mutex()
    @Volatile private var closed = false
    private val cache = object : LruCache<Int, RecognizedText>(512 * 1024) {
        override fun sizeOf(key: Int, value: RecognizedText): Int =
            (value.text.length * 4 + value.lines.size * 96).coerceAtLeast(1)
    }
    private val unregister = MemoryPressure.register { cache.evictAll() }

    suspend fun page(index: Int): RecognizedText? = withContext(Dispatchers.Default) {
        work.withLock {
            if (closed) return@withLock null
            cache[index]?.let { return@withLock it.takeUnless { closed } }
            ensureActive()
            val bitmap = render(index) ?: return@withLock null
            val result = recognize(bitmap)
            if (closed) return@withLock null
            cache.put(index, result)
            val snapshot = cache.snapshot()
            snapshot.keys.take((snapshot.size - 16).coerceAtLeast(0)).forEach { cache.remove(it) }
            if (closed) { cache.evictAll(); return@withLock null }
            result
        }
    }

    fun close() { closed = true; cache.evictAll(); unregister() }

    /** The task owns the bitmap until actual completion, even when its coroutine is cancelled. */
    private suspend fun recognize(bitmap: Bitmap): RecognizedText = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) { bitmap.recycle(); return@suspendCancellableCoroutine }
        val width = bitmap.width
        val height = bitmap.height
        var client: com.google.mlkit.vision.text.TextRecognizer? = null
        try {
            val active = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            client = active
            active.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(DIRECT) { task ->
                try {
                    if (continuation.isActive) {
                        if (task.isSuccessful) continuation.resume(boundedText(task.result, width, height))
                        else continuation.resumeWithException(DocumentException(DocumentError.Internal("PDF text recognition failed. Try again.")))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } catch (_: OutOfMemoryError) {
                    if (continuation.isActive) continuation.resumeWithException(DocumentException(DocumentError.LimitExceeded("There isn't enough memory to recognize this PDF page.")))
                } finally {
                    bitmap.recycle()
                    active.close()
                }
            }
        } catch (error: Exception) {
            bitmap.recycle(); client?.close()
            if (continuation.isActive) continuation.resumeWithException(error)
        } catch (_: LinkageError) {
            bitmap.recycle(); client?.close()
            if (continuation.isActive) continuation.resumeWithException(DocumentException(DocumentError.Unsupported("Text recognition isn't available on this device.")))
        } catch (_: OutOfMemoryError) {
            bitmap.recycle(); client?.close()
            if (continuation.isActive) continuation.resumeWithException(DocumentException(DocumentError.LimitExceeded("There isn't enough memory to recognize this PDF page.")))
        }
    }

    private fun boundedText(result: Text, width: Int, height: Int): RecognizedText {
        val lines = ArrayList<RecognizedLine>()
        val text = StringBuilder()
        outer@ for (block in result.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val remaining = MAX_PAGE_CHARS - text.length - if (text.isEmpty()) 0 else 1
            if (remaining <= 0) break@outer
            var end = minOf(line.text.length, remaining)
            if (end > 0 && end < line.text.length && line.text[end - 1].isHighSurrogate() && line.text[end].isLowSurrogate()) end--
            val words = line.text.substring(0, end)
            if (words.isBlank()) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(words)
            lines += RecognizedLine(words,
                (box.left.toFloat() / width).coerceIn(0f, 1f), (box.top.toFloat() / height).coerceIn(0f, 1f),
                (box.right.toFloat() / width).coerceIn(0f, 1f), (box.bottom.toFloat() / height).coerceIn(0f, 1f))
        }
        return RecognizedText(text.toString(), lines)
    }

    private companion object {
        const val MAX_PAGE_CHARS = 32_768
        val DIRECT = Executor { it.run() }
    }
}

internal const val LEGACY_PDF_TEXT_NOTICE = "Text uses offline Latin-script recognition on this Android version. Results and highlights may be approximate; background summaries and grouping sample up to 8 pages."
