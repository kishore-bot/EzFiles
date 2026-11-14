package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ezfiles.reader.data.IosDocumentAccess
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIFont
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.UISimpleTextPrintFormatter
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.posix.EINTR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.lseek
import platform.posix.read
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
actual fun rememberDocumentDelivery(): DocumentDelivery = remember { IosDocumentDelivery }

/** UIKit owns recipient/printer selection. Scope leases live until its completion callback. */
@OptIn(ExperimentalForeignApi::class)
private object IosDocumentDelivery : DocumentDelivery {
    private var busy = false
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun canPrint(ref: DocumentRef): Boolean = printKind(ref) != null && UIPrintInteractionController.isPrintingAvailable()

    override suspend fun share(refs: List<DocumentRef>) = visibleOperation {
        if (refs.isEmpty() || refs.size > 20) throw unavailable("Choose between 1 and 20 files to share.")
        val leases = mutableListOf<ScopedDocument>()
        try {
            refs.forEach { leases += scopedDocument(it) }
        } catch (failure: Throwable) {
            leases.forEach(ScopedDocument::close)
            throw failure
        }
        shareItems(leases.map { it.url }) { leases.forEach(ScopedDocument::close) }
    }

    override suspend fun shareText(text: String, title: String) = visibleOperation {
        if (text.length > 128_000) throw unavailable("Select less text to share.")
        shareItems(listOf(text)) {}
    }

    override suspend fun print(ref: DocumentRef, password: String?) = visibleOperation {
        val kind = printKind(ref) ?: throw unavailable("Export this format as a PDF before printing.")
        if (!UIPrintInteractionController.isPrintingAvailable()) throw unavailable("Printing is unavailable on this device.")
        // Never turn an unlocked/encrypted source into an unrestricted temporary PDF.
        if (password != null) throw unavailable("Printing password-protected PDFs is not available on iOS yet.")
        val presenter = presenter()
        val lease = scopedDocument(ref)
        var presented = false
        try {
            val formatter = if (kind == PrintKind.Text) {
                UISimpleTextPrintFormatter(text = readText(ref)).apply {
                    font = UIFont.systemFontOfSize(11.0)
                    perPageContentInsets = UIEdgeInsetsMake(36.0, 36.0, 36.0, 36.0)
                }
            } else {
                if (!UIPrintInteractionController.canPrintURL(lease.url)) {
                    throw unavailable("This file cannot be printed or its PDF permissions disallow printing.")
                }
                null
            }
            val controller = UIPrintInteractionController.sharedPrintController()
            controller.printingItem = null
            controller.printingItems = null
            controller.printFormatter = null
            controller.printPageRenderer = null
            controller.printInfo = UIPrintInfo.printInfo().apply { jobName = ref.displayName }
            controller.showsPageRange = true
            if (formatter == null) controller.printingItem = lease.url else controller.printFormatter = formatter
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    mainScope.launch {
                        controller.dismissAnimated(true)
                    }
                }
                presented = controller.presentFromRect(
                    rect = presenter.view.bounds,
                    inView = presenter.view,
                    animated = true,
                ) { _, _, error ->
                    lease.close()
                    if (continuation.isActive) {
                        if (error == null) continuation.resume(Unit)
                        else continuation.resumeWithException(unavailable("The print job could not finish. Try another printer or destination."))
                    }
                }
                if (!presented) {
                    lease.close()
                    if (continuation.isActive) continuation.resumeWithException(unavailable("Print options could not open. Try again."))
                }
            }
        } finally {
            // Once handed over, UIKit's completion owns the lease even if the caller cancels.
            if (!presented) lease.close()
        }
    }

    private suspend fun shareItems(items: List<Any>, release: () -> Unit) {
        var presented = false
        try {
            val presenter = presenter()
            val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
            controller.popoverPresentationController()?.apply {
                sourceView = presenter.view
                sourceRect = presenter.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
                permittedArrowDirections = 0uL
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                controller.completionWithItemsHandler = { _, _, _, error ->
                    release()
                    if (continuation.isActive) {
                        if (error == null) continuation.resume(Unit)
                        else continuation.resumeWithException(unavailable("Sharing could not finish. Try a different destination."))
                    }
                }
                continuation.invokeOnCancellation {
                    mainScope.launch { controller.dismissViewControllerAnimated(true) { release() } }
                }
                presenter.presentViewController(controller, animated = true, completion = null)
                presented = true
            }
        } catch (failure: Throwable) {
            if (!presented) release()
            throw failure
        }
    }

    private suspend fun visibleOperation(operation: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        if (busy) throw unavailable("Finish the current share or print operation first.")
        busy = true
        try {
            operation()
        } finally {
            busy = false
        }
    }

    @Suppress("DEPRECATION") // Same single-window host as the document picker.
    private fun presenter(): UIViewController {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: throw unavailable("Open EzFiles before sharing or printing.")
        while (controller.presentedViewController != null) controller = checkNotNull(controller.presentedViewController)
        return controller
    }
}

private class ScopedDocument(val url: NSURL, private var scoped: Boolean) {
    fun close() {
        if (scoped) {
            scoped = false
            url.stopAccessingSecurityScopedResource()
        }
    }
}

private fun scopedDocument(ref: DocumentRef): ScopedDocument {
    val url = PickedDocuments.urlFor(ref.uri) ?: throw unavailable("This file is unavailable. Choose it again.")
    return ScopedDocument(url, url.startAccessingSecurityScopedResource())
}

/** Read a bounded UTF-8 source on a worker; never silently print a truncated file. */
@OptIn(ExperimentalForeignApi::class)
private suspend fun readText(ref: DocumentRef): String = withContext(Dispatchers.IO) {
    val limit = 2L * 1024 * 1024
    val fd = IosDocumentAccess().openDescriptor(ref, ReadLimits(limit, limit, limit))
    try {
        val size = lseek(fd, 0, SEEK_END)
        if (size < 0 || lseek(fd, 0, SEEK_SET) < 0) throw unavailable("This file cannot be read for printing.")
        if (size > limit) throw unavailable("Text printing supports files up to 2 MB. Export a PDF for larger documents.")
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = bytes.usePinned { read(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
            if (count < 0) {
                if (errno == EINTR) continue
                throw unavailable("This text file cannot be read for printing.")
            }
            if (count == 0L) break
            offset += count.toInt()
        }
        val extra = ByteArray(1)
        if (extra.usePinned { read(fd, it.addressOf(0), 1u) } != 0L) {
            throw unavailable("This file changed while it was read. Try printing it again.")
        }
        try {
            bytes.decodeToString(endIndex = offset, throwOnInvalidSequence = true).removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw unavailable("This text encoding cannot be printed directly. Export a PDF first.")
        }
    } finally {
        close(fd)
    }
}

private fun unavailable(detail: String) = DocumentException(DocumentError.FileAccess(detail))
