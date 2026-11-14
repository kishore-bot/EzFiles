package com.ezfiles.reader.presentation.viewers

import java.nio.charset.Charset

internal actual fun decodeLegacyHtml(bytes: ByteArray, label: String): String? = try {
    String(bytes, Charset.forName(label))
} catch (_: IllegalArgumentException) {
    // A name the page made up, or a charset this device doesn't have.
    null
}
