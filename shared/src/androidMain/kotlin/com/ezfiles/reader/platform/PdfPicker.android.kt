package com.ezfiles.reader.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ezfiles.reader.domain.DocumentRef

private val PDF_ONLY = arrayOf("application/pdf")

@Composable
actual fun rememberPdfPicker(multiple: Boolean, onResult: (List<DocumentRef>) -> Unit): DocumentPicker {
    val context = LocalContext.current
    return if (multiple) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            onResult(uris.map { documentRef(context, it) })
        }
        remember(launcher) { DocumentPicker { launcher.launch(PDF_ONLY) } }
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            onResult(listOfNotNull(uri?.let { documentRef(context, it) }))
        }
        remember(launcher) { DocumentPicker { launcher.launch(PDF_ONLY) } }
    }
}
