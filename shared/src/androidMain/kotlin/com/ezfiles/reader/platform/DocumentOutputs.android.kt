package com.ezfiles.reader.platform

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidDocumentOutputs(context: Context) : DocumentOutputs {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun sizeOf(uri: String): Long? = withContext(Dispatchers.IO) {
        queryColumn(Uri.parse(uri), OpenableColumns.SIZE) { getLong(it) }
    }

    override suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            // Providers signal a refusal with SecurityException, IllegalArgumentException and others.
            false
        }
    }

    override suspend fun canCreateIn(folder: String): Boolean = withContext(Dispatchers.IO) {
        val flags = try {
            queryColumn(folderDocument(folder), DocumentsContract.Document.COLUMN_FLAGS) { getInt(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
        flags != null && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
    }

    override suspend fun createIn(folder: String, name: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val created = try {
            DocumentsContract.createDocument(resolver, folderDocument(folder), mimeType, name)
        } catch (e: IOException) {
            throw DocumentException(DocumentError.FileAccess(e.message ?: "The file could not be created."))
        } catch (_: RuntimeException) {
            throw DocumentException(DocumentError.FileAccess("Xfiles can't create files in this folder."))
        }
        created?.toString() ?: throw DocumentException(DocumentError.FileAccess("The app that provides this folder did not create the file."))
    }

    override suspend fun nameOf(uri: String): String? = withContext(Dispatchers.IO) {
        queryColumn(Uri.parse(uri), OpenableColumns.DISPLAY_NAME) { getString(it) }
    }

    private fun folderDocument(folder: String): Uri {
        val tree = Uri.parse(folder)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    private fun <T> queryColumn(uri: Uri, column: String, read: Cursor.(Int) -> T): T? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.read(index) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

@Composable
actual fun rememberDocumentOutputs(): DocumentOutputs {
    val app = LocalContext.current.applicationContext
    return remember(app) { AndroidDocumentOutputs(app) }
}
