package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.presentation.PageFind

/** Not implemented: iOS is parked, so it shows the start of the page's code. */
@Composable
internal actual fun HtmlPage(
    page: String,
    kind: PageKind,
    document: com.ezfiles.reader.domain.DocumentRef,
    folderVersion: Int,
    resource: suspend (String) -> ByteArray?,
    query: String,
    step: Int,
    forward: Boolean,
    onFind: (PageFind) -> Unit,
    onLink: (url: String, refused: Boolean) -> Unit,
    onFailure: (HtmlPageFailure) -> Unit,
    modifier: Modifier,
    darkReading: Boolean,
) {
    Text(page.take(64 * 1024), modifier.verticalScroll(rememberScrollState()).padding(16.dp))
}

/** Not implemented: such a page decodes as UTF-8. */
internal actual fun decodeLegacyHtml(bytes: ByteArray, label: String): String? = null
