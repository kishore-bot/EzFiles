package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.ezfiles.reader.domain.DocumentRef
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLContentModificationDateKey
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/**
 * Security-scoped URLs carry their sandbox extension in the `NSURL` object
 * itself; one rebuilt from a string has no access. A picked file is therefore
 * referenced by a bookmark, which [urlFor] resolves back into a scoped URL,
 * also after the app restarts (recents). The latest picked object is kept as
 * well, so the first open needs no resolution.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)
internal object PickedDocuments {
    private const val SCHEME = "ezbookmark:"
    private val latest = AtomicReference<Pair<String, NSURL>?>(null)

    fun register(url: NSURL): DocumentRef {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val bookmark = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
            val uri = bookmark?.let { SCHEME + it.base64EncodedStringWithOptions(0u) } ?: (url.absoluteString ?: "document")
            latest.store(uri to url)
            val values = url.resourceValuesForKeys(listOf(NSURLFileSizeKey, NSURLContentModificationDateKey), error = null)
            return DocumentRef(
                uri = uri,
                displayName = url.lastPathComponent ?: "Document",
                sizeBytes = (values?.get(NSURLFileSizeKey) as? NSNumber)?.longLongValue,
                modifiedAt = (values?.get(NSURLContentModificationDateKey) as? NSDate)
                    ?.let { (it.timeIntervalSince1970 * 1000).toLong() },
                identity = url.path ?: uri,
            )
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    fun urlFor(uri: String): NSURL? {
        latest.load()?.takeIf { it.first == uri }?.let { return it.second }
        if (!uri.startsWith(SCHEME)) return null
        val data = NSData.create(base64EncodedString = uri.removePrefix(SCHEME), options = 0u) ?: return null
        return NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null)
    }

    /** A Siri handoff must not silently act on a file changed since it was opened. */
    fun matchesVersion(ref: DocumentRef): Boolean {
        val url = urlFor(ref.uri) ?: return false
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val values = url.resourceValuesForKeys(listOf(NSURLFileSizeKey, NSURLContentModificationDateKey), error = null)
            val size = (values?.get(NSURLFileSizeKey) as? NSNumber)?.longLongValue
            val modified = (values?.get(NSURLContentModificationDateKey) as? NSDate)
                ?.let { (it.timeIntervalSince1970 * 1000).toLong() }
            return (ref.sizeBytes == null || ref.sizeBytes == size) && (ref.modifiedAt == null || ref.modifiedAt == modified)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }
}

@Composable
actual fun rememberDocumentPicker(onResult: (DocumentRef?) -> Unit): DocumentPicker {
    val currentOnResult by rememberUpdatedState(onResult)
    // UIKit holds the delegate weakly; remembering it keeps it alive.
    val delegate = remember { PickerDelegate { currentOnResult(it) } }
    return remember(delegate) {
        DocumentPicker {
            // asCopy = false opens the file in place instead of duplicating it
            // into the app container; access is handled by IosDocumentAccess.
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = false)
            picker.delegate = delegate
            picker.allowsMultipleSelection = false
            val presenter = topViewController()
            if (presenter == null) {
                currentOnResult(null)
            } else {
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class PickerDelegate(private val onResult: (DocumentRef?) -> Unit) :
    NSObject(),
    UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        onResult(url?.let(PickedDocuments::register))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}

@Suppress("DEPRECATION") // keyWindow is adequate for a single-scene app.
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
