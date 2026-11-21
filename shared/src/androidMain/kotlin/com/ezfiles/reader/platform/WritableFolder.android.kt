package com.ezfiles.reader.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberWritableFolders(): WritableFolders {
    val context = LocalContext.current
    return remember(context) { AndroidWritableFolders(context.applicationContext) }
}

private class AndroidWritableFolders(private val app: Context) : WritableFolders {
    private fun folderDocument(folder: String): Uri {
        val tree = Uri.parse(folder)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    override suspend fun createFile(folder: String, name: String, mimeType: String): String = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.createDocument(app.contentResolver, folderDocument(folder), mimeType, name)?.toString()
                ?: throw DocumentException(DocumentError.FileAccess("That folder didn't accept a new file."))
        } catch (_: SecurityException) {
            throw DocumentException(DocumentError.FileAccess("Xfiles doesn't have permission to save in that folder."))
        } catch (_: FileNotFoundException) {
            throw DocumentException(DocumentError.FileAccess("That folder is no longer available."))
        } catch (_: IllegalArgumentException) {
            throw DocumentException(DocumentError.FileAccess("That folder can't be saved into."))
        } catch (_: IllegalStateException) {
            throw DocumentException(DocumentError.FileAccess("That folder didn't accept a new file."))
        }
    }

    override suspend fun delete(file: String) {
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(file))
            } catch (_: Exception) {
                // The provider may not allow it; the user is told the save failed either way.
            }
        }
    }

    override suspend fun displayName(folder: String): String? = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.query(
                folderDocument(folder),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
