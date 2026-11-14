package com.ezfiles.app

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Provider calls are cancellable and time-bounded; cancelling assistant access cancels the Binder query. */
internal suspend fun validateAssistantDocument(context: Context, ref: DocumentRef): Boolean =
    withTimeoutOrNull(1_500L) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            Dispatchers.IO.dispatch(continuation.context) {
                if (!continuation.isActive) return@dispatch
                val readable = try {
                    val uri = Uri.parse(ref.uri)
                    if (uri.scheme != "content") false else {
                        context.contentResolver.openFileDescriptor(uri, "r", cancellation)?.use {
                            cancellation.throwIfCanceled()
                            val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            val cursor = try {
                                context.contentResolver.query(uri, columns, null, null, null, cancellation)
                            } catch (_: IllegalArgumentException) {
                                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, cancellation)
                            }
                            cursor?.use { metadata ->
                                if (!metadata.moveToFirst()) false else {
                                    val sizeColumn = metadata.getColumnIndex(OpenableColumns.SIZE)
                                    val modifiedColumn = metadata.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                    val size = sizeColumn.takeIf { index -> index >= 0 && !metadata.isNull(index) }?.let(metadata::getLong)
                                    val modified = modifiedColumn.takeIf { index -> index >= 0 && !metadata.isNull(index) }?.let(metadata::getLong)?.takeIf { value -> value > 0 }
                                    (ref.sizeBytes == null || size == null || ref.sizeBytes == size) &&
                                        (ref.modifiedAt == null || modified == null || ref.modifiedAt == modified)
                                }
                            } ?: true // A readable provider may supply no optional metadata.
                        } == true
                    }
                } catch (_: java.io.IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
                continuation.resume(readable)
            }
        }
    } == true
