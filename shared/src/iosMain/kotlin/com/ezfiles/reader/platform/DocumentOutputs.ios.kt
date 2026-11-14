package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException

/** iOS is parked. */
private object IosDocumentOutputs : DocumentOutputs {
    override suspend fun sizeOf(uri: String): Long? = null

    override suspend fun delete(uri: String) = false

    override suspend fun canCreateIn(folder: String) = false

    override suspend fun createIn(folder: String, name: String, mimeType: String): String =
        throw DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

    override suspend fun nameOf(uri: String): String? = null
}

@Composable
actual fun rememberDocumentOutputs(): DocumentOutputs = IosDocumentOutputs
