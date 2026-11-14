package com.ezfiles.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ezfiles.reader.domain.LibraryFolder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Folder trees through the Storage Access Framework. Providers are other apps'
 * code and can fail in any way, so their runtime exceptions are contained here.
 */
internal class AndroidDocumentTrees(context: Context) : DocumentTrees {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    override fun take(uri: String): LibraryFolder? = try {
        val tree = Uri.parse(uri)
        // Keep write access only when the provider grants it; read-only libraries still work.
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        LibraryFolder(uri, folderName(tree))
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun release(uri: String) {
        try {
            val tree = Uri.parse(uri)
            val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags != 0) resolver.releasePersistableUriPermission(tree, flags)
        } catch (_: SecurityException) {
            // Already gone.
        }
    }

    override fun status(uri: String): TreeStatus {
        val granted = resolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
        if (!granted) return TreeStatus.Lost
        val tree = Uri.parse(uri)
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val exists = resolver.query(root, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
            if (exists) TreeStatus.Readable(rootId) else missingStatus(tree)
        } catch (_: SecurityException) {
            TreeStatus.Lost
        } catch (_: RuntimeException) {
            missingStatus(tree)
        }
    }

    override fun forEachChild(uri: String, parentId: String, onEntry: (TreeEntry) -> Boolean) {
        val tree = Uri.parse(uri)
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR
                    // Android 10 and older allow choosing a storage root; its app-private data is noise.
                    if (isDirectory && name == "Android" && parentId.endsWith(":")) continue
                    val entry = TreeEntry(
                        id = id,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                        name = name,
                        isDirectory = isDirectory,
                        size = if (cursor.isNull(3)) null else cursor.getLong(3),
                        modifiedAt = if (cursor.isNull(4)) null else cursor.getLong(4),
                        identity = androidDocumentIdentity(DocumentsContract.buildDocumentUriUsingTree(tree, id)),
                    )
                    if (!onEntry(entry)) break
                }
            }
        } catch (e: CancellationException) {
            // A subclass of IllegalStateException, so it must not fall into the case below.
            throw e
        } catch (_: RuntimeException) {
            // One unreadable subfolder shouldn't cost the rest of the tree.
        }
    }

    /** Only a mounted local volume proves the folder was deleted; elsewhere it may come back. */
    private fun missingStatus(tree: Uri): TreeStatus {
        if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return TreeStatus.Unavailable
        val volumeId = try {
            DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        } catch (_: IllegalArgumentException) {
            return TreeStatus.Lost
        }
        val storage = app.getSystemService(StorageManager::class.java) ?: return TreeStatus.Unavailable
        val mounted = storage.storageVolumes.any { volume ->
            val matches = if (volumeId == "primary" || volumeId == "home") volume.isPrimary else volume.uuid == volumeId
            matches && (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY)
        }
        return if (mounted) TreeStatus.Lost else TreeStatus.Unavailable
    }

    private fun folderName(tree: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = try {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
