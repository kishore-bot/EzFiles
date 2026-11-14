package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.presentation.PageFind

/** Not implemented: iOS is parked; its books read in the Scroll view. */
@Composable
internal actual fun BookPage(
    page: String,
    resource: suspend (String) -> ByteArray?,
    textZoom: Int,
    background: Int,
    commands: BookCommands,
    query: String,
    step: Int,
    forward: Boolean,
    onFind: (PageFind) -> Unit,
    onMessage: (String) -> Unit,
    onFailure: (HtmlPageFailure) -> Unit,
    modifier: Modifier,
) {
    Text("Book pages aren't available here yet. Choose Scroll in the Aa menu.", modifier.padding(24.dp))
}

@Composable
internal actual fun rememberBookStore(): BookStore = NoBookStore
