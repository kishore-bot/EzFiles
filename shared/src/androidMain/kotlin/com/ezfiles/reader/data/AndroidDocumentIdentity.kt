package com.ezfiles.reader.data

import android.net.Uri
import android.provider.DocumentsContract

/** Tree and direct document URIs share a provider/document ID; equal names never establish identity. */
internal fun androidDocumentIdentity(uri: Uri): String = try {
    val authority = uri.authority
    if (authority != null && uri.pathSegments.contains("document")) {
        DocumentsContract.buildDocumentUri(authority, DocumentsContract.getDocumentId(uri)).toString()
    } else {
        uri.toString()
    }
} catch (_: IllegalArgumentException) {
    uri.toString()
}
