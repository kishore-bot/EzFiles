package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ezfiles.reader.domain.DocumentRef

/** iOS is parked. */
@Composable
actual fun rememberPdfPicker(multiple: Boolean, onResult: (List<DocumentRef>) -> Unit): DocumentPicker =
    remember { DocumentPicker { onResult(emptyList()) } }
