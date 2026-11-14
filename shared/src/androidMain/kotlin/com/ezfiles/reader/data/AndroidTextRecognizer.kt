package com.ezfiles.reader.data

import android.content.Context
import android.net.Uri
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.RecognizedLine
import com.ezfiles.reader.domain.RecognizedText
import com.ezfiles.reader.domain.TextRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * ML Kit with the Latin model bundled in the APK (`com.google.mlkit:text-recognition`),
 * so it works offline from the first use. A client holds the model in native
 * memory, so each call opens and closes its own.
 */
internal class AndroidTextRecognizer(context: Context) : TextRecognizer {
    private val app = context.applicationContext

    override suspend fun recognize(imageUri: String): RecognizedText = withContext(Dispatchers.Default) {
        val bitmap = guarded { decodeUpright(app, Uri.parse(imageUri), MAX_PICTURE_SIDE) }
        ensureActive()
        // Not recycled: after a cancel, ML Kit may still be reading it. The collector frees it.
        val image = InputImage.fromBitmap(bitmap, 0)
        try {
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                recognizedFrom(client.process(image).awaitResult(), bitmap.width, bitmap.height)
            } finally {
                client.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            throw if (e.errorCode == MlKitException.INVALID_ARGUMENT) {
                DocumentException(DocumentError.Malformed("This picture is too small to read."))
            } else {
                DocumentException(DocumentError.Internal("Text recognition failed: ${e.message}"))
            }
        } catch (e: LinkageError) {
            // The native recognizer couldn't load on this device.
            throw DocumentException(DocumentError.Unsupported("Text recognition isn't available on this device."))
        } catch (e: IllegalArgumentException) {
            throw DocumentException(DocumentError.Malformed("This picture couldn't be read."))
        } catch (_: OutOfMemoryError) {
            throw DocumentException(DocumentError.LimitExceeded("There isn't enough memory to read this picture."))
        }
    }

    private fun recognizedFrom(result: Text, width: Int, height: Int): RecognizedText {
        val lines = ArrayList<RecognizedLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                lines += RecognizedLine(
                    line.text,
                    (box.left.toFloat() / width).coerceIn(0f, 1f),
                    (box.top.toFloat() / height).coerceIn(0f, 1f),
                    (box.right.toFloat() / width).coerceIn(0f, 1f),
                    (box.bottom.toFloat() / height).coerceIn(0f, 1f),
                )
            }
        }
        val text = result.textBlocks.joinToString("\n\n") { block -> block.lines.joinToString("\n") { it.text } }
        return RecognizedText(text, lines)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
