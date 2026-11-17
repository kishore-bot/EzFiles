package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import com.ezfiles.reader.domain.DocumentRef

fun interface DocumentPicker {
    fun launch()
}

/**
 * Remembers a launcher for the system document picker. [onResult] receives the
 * picked document, or `null` if the user dismissed the picker.
 */
@Composable
expect fun rememberDocumentPicker(onResult: (DocumentRef?) -> Unit): DocumentPicker

/** Picks one or more files of any type (model files the user downloaded); their URIs, or empty. */
@androidx.compose.runtime.Composable
expect fun rememberModelFilePicker(onResult: (List<String>) -> Unit): () -> Unit
