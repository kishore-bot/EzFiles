package com.ezfiles.reader.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ezfiles.reader.domain.DocumentRef
import java.io.IOException

/** Folders the user chose for pages opened some other way, by page identity. */
private const val PREFS = "page_folders"

/**
 * The folder of a page, read through the Storage Access Framework. Paths are
 * walked one name at a time from the page's folder and can go up only as far
 * as the granted folder, so a page reads nothing outside what the user gave.
 * Providers are other apps' code, so their failures count as "not found".
 */
internal class PageFolder(private val context: Context, private val tree: Uri, ancestors: List<String>) {
    /** From the granted folder down to the page's folder. */
    private val base = ancestors
    private val listings = HashMap<String, Map<String, String>>()

    /** The bytes of the file at [path], relative to the page, or `null`. */
    fun read(path: String, maxBytes: Int): ByteArray? = synchronized(this) {
        val folders = ArrayDeque(base)
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        var id: String? = null
        for ((i, segment) in segments.withIndex()) {
            if (segment == "..") {
                if (folders.size <= 1) return null
                folders.removeLast()
                continue
            }
            val child = children(folders.last())[segment] ?: children(folders.last()).entries.firstOrNull { it.key.equals(segment, ignoreCase = true) }?.value ?: return null
            if (i == segments.lastIndex) id = child else folders.addLast(child)
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id ?: return null)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return null
                }
                out.toByteArray()
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun children(parentId: String): Map<String, String> = listings.getOrPut(parentId) {
        val map = HashMap<String, String>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && map.size < 10_000) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    map.putIfAbsent(name, id)
                }
            }
        } catch (_: RuntimeException) {
            // An unreadable folder lists as empty.
        }
        map
    }
}

/**
 * The page's folder: through the library folder tree it was opened from, or
 * through a folder the user chose for it before. `null` if neither.
 */
internal fun pageFolderOf(context: Context, page: DocumentRef): PageFolder? {
    val app = context.applicationContext
    try {
        val uri = Uri.parse(page.uri)
        if (DocumentsContract.isDocumentUri(app, uri) && uri.pathSegments.firstOrNull() == "tree") {
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            val ancestors = DocumentsContract.findDocumentPath(app.contentResolver, uri)?.path
            if (ancestors != null && ancestors.size >= 2) return PageFolder(app, tree, ancestors.dropLast(1))
        }
    } catch (_: RuntimeException) {
        // Fall back to a chosen folder.
    }
    val chosen = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(page.identity, null) ?: return null
    return try {
        val tree = Uri.parse(chosen)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!granted) null else PageFolder(app, tree, listOf(DocumentsContract.getTreeDocumentId(tree)))
    } catch (_: RuntimeException) {
        null
    }
}

/** Whether the folder [tree] directly holds a file named [name]. */
private fun holds(context: Context, tree: Uri, name: String): Boolean = try {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
        var found = false
        while (!found && cursor.moveToNext()) found = cursor.getString(0) == name
        found
    } ?: false
} catch (_: RuntimeException) {
    false
}

@Composable
actual fun rememberPageFolderAccess(page: DocumentRef): PageFolderAccess {
    val context = LocalContext.current.applicationContext
    val state = remember(page.identity) {
        object : PageFolderAccess {
            override var available by mutableStateOf(pageFolderOf(context, page) != null)
            override var version by mutableIntStateOf(0)
            override var problem by mutableStateOf<String?>(null)
            var launch: () -> Unit = {}

            override fun choose() = launch()

            override fun dismissProblem() {
                problem = null
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        if (!holds(context, tree, page.displayName)) {
            state.problem = "That folder doesn't hold “${page.displayName}”. Choose the folder the page is in."
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // A grant for this session still works.
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(page.identity, tree.toString()).apply()
        state.available = true
        state.version++
    }
    state.launch = { launcher.launch(null) }
    return state
}
