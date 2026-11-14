package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.ezfiles.reader.domain.DocumentRef

/** Reads and writes documents the user has granted through a system dialog. */
interface DocumentFiles {
    suspend fun read(uri: String): ByteArray

    /** Replaces the file's content. Throws [com.ezfiles.reader.domain.DocumentException] on failure. */
    suspend fun write(uri: String, bytes: ByteArray)

    /** Whether [uri] accepts [write], so the UI can offer Save or only Save As. */
    suspend fun canWrite(uri: String): Boolean

    /** A reference for opening a file just saved at [uri] in the reader. */
    suspend fun refOf(uri: String): DocumentRef
}

val LocalDocumentFiles = staticCompositionLocalOf<DocumentFiles> { error("DocumentFiles not provided") }

fun interface DocumentSaver {
    /** Shows the system's save dialog. */
    fun launch(suggestedName: String, mimeType: String)
}

/** The system save dialog; [onResult] gets the new file's URI, or `null` if dismissed. */
@Composable
expect fun rememberDocumentSaver(onResult: (String?) -> Unit): DocumentSaver

fun interface ImagePicker {
    fun launch()
}

/**
 * The system photo picker, which needs no permission. [onResult] gets the
 * chosen images in order, and an empty list if dismissed.
 */
@Composable
expect fun rememberImagePicker(multiple: Boolean = true, onResult: (List<String>) -> Unit): ImagePicker
