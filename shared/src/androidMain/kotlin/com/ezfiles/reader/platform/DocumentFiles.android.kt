package com.ezfiles.reader.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidDocumentFiles(context: Context) : DocumentFiles {
    private val app = context.applicationContext

    override suspend fun read(uri: String): ByteArray = withContext(Dispatchers.IO) {
        accessing {
            app.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                ?: throw IOException("The app that provides this file did not return it.")
        }
    }

    override suspend fun write(uri: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        accessing {
            // "wt" truncates: without it a shorter save would leave the old file's tail behind.
            val stream = app.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw IOException("The app that provides this file did not accept it.")
            stream.use { it.write(bytes) }
        }
    }

    override suspend fun canWrite(uri: String): Boolean = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (!DocumentsContract.isDocumentUri(app, parsed)) return@withContext false
        // The provider's flag says nothing about this app: a file from recents may hold only a read grant.
        val grant = app.checkUriPermission(parsed, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (grant != PackageManager.PERMISSION_GRANTED) return@withContext false
        val column = DocumentsContract.Document.COLUMN_FLAGS
        try {
            app.contentResolver.query(parsed, arrayOf(column), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
            } ?: false
        } catch (_: RuntimeException) {
            false
        }
    }

    override suspend fun refOf(uri: String): DocumentRef = withContext(Dispatchers.IO) { documentRef(app, Uri.parse(uri)) }

    private inline fun <T> accessing(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw DocumentException(DocumentError.FileAccess(e.message ?: "The file could not be accessed."))
    } catch (e: SecurityException) {
        throw DocumentException(DocumentError.FileAccess("Xfiles no longer has access to this file."))
    }
}

@Composable
actual fun rememberDocumentSaver(onResult: (String?) -> Unit): DocumentSaver {
    val context = LocalContext.current
    // CreateDocument fixes its MIME type when constructed, so the launcher's intent is adjusted per call.
    val mime = remember { mutableStateOf("application/octet-stream") }
    val contract = remember {
        object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(mime.value)
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        uri?.let { takeGrant(context, it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        onResult(uri?.toString())
    }
    return remember(launcher) {
        DocumentSaver { name, type ->
            mime.value = type
            launcher.launch(name)
        }
    }
}

@Composable
actual fun rememberImagePicker(multiple: Boolean, onResult: (List<String>) -> Unit): ImagePicker {
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return if (multiple) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            onResult(uris.map(Uri::toString))
        }
        remember(launcher) { ImagePicker { launcher.launch(request) } }
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            onResult(listOfNotNull(uri?.toString()))
        }
        remember(launcher) { ImagePicker { launcher.launch(request) } }
    }
}

private fun takeGrant(context: Context, uri: Uri, flags: Int) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
        // The provider does not offer persistable grants; the file stays usable this session.
    }
}
