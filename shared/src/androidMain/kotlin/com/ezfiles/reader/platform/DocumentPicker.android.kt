package com.ezfiles.reader.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import com.ezfiles.reader.data.androidDocumentIdentity
import com.ezfiles.reader.domain.DocumentRef

@Composable
actual fun rememberDocumentPicker(onResult: (DocumentRef?) -> Unit): DocumentPicker {
    val context = LocalContext.current
    val contract = remember {
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        onResult(uri?.let { documentRef(context, it) })
    }
    // Any MIME type: the engine decides by content, not by declared type.
    return remember(launcher) { DocumentPicker { launcher.launch(arrayOf("*/*")) } }
}

/**
 * The document in a VIEW or SEND intent from another app, or `null` if there
 * is none. Only `content` URIs are accepted: the sender's own grant covers
 * them, while a `file` URI would make this app read a path on the sender's
 * behalf, possibly one of our private files.
 */
fun documentFromIntent(context: Context, intent: Intent?): DocumentRef? {
    val uri = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    } ?: return null
    return if (uri.scheme == ContentResolver.SCHEME_CONTENT) documentRef(context, uri) else null
}

internal fun documentRef(context: Context, uri: Uri): DocumentRef {
    // Keep access across restarts so the file can be reopened from recents, and edited where allowed.
    val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    if (!takePersistable(context, uri, readWrite)) takePersistable(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    var name: String? = null
    var size: Long? = null
    var modified: Long? = null
    val required = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    // Some providers reject columns they don't know, so the optional one is tried separately.
    for (columns in listOf(required + DocumentsContract.Document.COLUMN_LAST_MODIFIED, required)) {
        try {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME)
                    size = cursor.getLongOrNull(OpenableColumns.SIZE)
                    modified = cursor.getLongOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                }
            }
            break
        } catch (_: IllegalArgumentException) {
            // Metadata is optional.
        } catch (_: SecurityException) {
            // A sender can hand over a URI without granting access. Opening then
            // reports that as a file-access error, rather than crashing at launch.
            break
        }
    }
    val displayName = name ?: uri.lastPathSegment ?: "Document"
    val modifiedAt = modified?.takeIf { it > 0 }
    return DocumentRef(
        uri = uri.toString(),
        displayName = displayName,
        sizeBytes = size,
        modifiedAt = modifiedAt,
        identity = androidDocumentIdentity(uri),
    )
}

/** `false` if the provider does not offer this persistable grant; a temporary one still lasts the session. */
private fun takePersistable(context: Context, uri: Uri, flags: Int): Boolean = try {
    context.contentResolver.takePersistableUriPermission(uri, flags)
    true
} catch (_: SecurityException) {
    false
}

private fun android.database.Cursor.getStringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun android.database.Cursor.getLongOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
