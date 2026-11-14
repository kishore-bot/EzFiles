package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import com.ezfiles.reader.domain.DocumentRef

/**
 * Whether the app may read the folder a page (a web page, a Markdown file)
 * sits in, for its styles, scripts and pictures, and a way to ask for it.
 */
interface PageFolderAccess {
    /** The folder can be read: the page came from a library folder, or the user chose its folder. */
    val available: Boolean

    /** Changes each time access is granted, so the page reloads with it. */
    val version: Int

    /** Why the last choice didn't work, if it didn't. */
    val problem: String?

    /** Opens the system folder picker; the chosen folder must hold the page. */
    fun choose()

    fun dismissProblem()
}

@Composable
expect fun rememberPageFolderAccess(page: DocumentRef): PageFolderAccess
