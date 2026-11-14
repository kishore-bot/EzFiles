package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException

@Composable
actual fun rememberWritableFolders(): WritableFolders = remember {
    object : WritableFolders {
        override suspend fun createFile(folder: String, name: String, mimeType: String): String =
            throw DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

        override suspend fun delete(file: String) = Unit

        override suspend fun displayName(folder: String): String? = null
    }
}
