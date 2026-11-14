package com.ezfiles.reader.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? {
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val contract = remember { OpenWritableDocumentTree() }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return remember(launcher) {
        {
            try {
                launcher.launch(null)
            } catch (_: ActivityNotFoundException) {
                currentOnUnavailable()
            }
        }
    }
}

/** Requests write access; library callers persist granted access, export callers use it temporarily. */
private class OpenWritableDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
}

@Composable
actual fun rememberWritableFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? {
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val contract = remember { OpenWritableDocumentTree() }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return remember(launcher) {
        {
            try {
                launcher.launch(null)
            } catch (_: ActivityNotFoundException) {
                currentOnUnavailable()
            }
        }
    }
}
