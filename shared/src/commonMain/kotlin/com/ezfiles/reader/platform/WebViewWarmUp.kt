package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/**
 * Starts the system web view while a document that will need one is still
 * being read, so showing it later doesn't wait for its first-time setup.
 * Does nothing after the first time in a process.
 */
@Composable
internal expect fun WarmUpWebView()

/** Whether a file of this name is shown in a web view: e-books, Word pages, web pages and Markdown. */
internal fun opensInWebView(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("epub", "docx", "docm", "dotx", "dotm", "html", "htm", "xhtml", "md", "markdown", "mdown", "mkd", "mdx")
