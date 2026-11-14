package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import com.ezfiles.reader.domain.DocumentRef

/**
 * The system document picker, filtered to PDFs where the platform allows it.
 * [onResult] gets the chosen files in order, and an empty list if dismissed.
 * The filter goes by declared type, so callers still check the content.
 */
@Composable
expect fun rememberPdfPicker(multiple: Boolean = false, onResult: (List<DocumentRef>) -> Unit): DocumentPicker
