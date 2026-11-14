package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.platform.PickedDocuments
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSURL
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror

/**
 * Opens picker URLs in place: starts security-scoped access, coordinates the
 * read with other processes (which also downloads iCloud placeholders), and
 * opens a POSIX descriptor inside the coordinated block.
 *
 * The descriptor stays valid after the scope ends because the sandbox checks
 * access at open time. Reads after that are not coordinated: a concurrent
 * writer can change the file mid-session (see processing-pipeline.md).
 */
internal class IosDocumentAccess : DocumentAccess {

    override fun openDescriptor(ref: DocumentRef, limits: ReadLimits): Int {
        val url = PickedDocuments.urlFor(ref.uri)
            ?: throw accessError("The selected file is no longer available. Choose it again.")
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            return coordinatedOpen(url)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun coordinatedOpen(url: NSURL): Int = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        var fd = -1
        var failure: String? = null
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
            url = url,
            options = NSFileCoordinatorReadingWithoutChanges,
            error = error.ptr,
        ) { coordinatedUrl ->
            val path = coordinatedUrl?.path
            if (path == null) {
                failure = "The file has no local path."
            } else {
                fd = open(path, O_RDONLY)
                if (fd < 0) failure = strerror(errno)?.toKString()
            }
        }
        error.value?.let { throw accessError(it.localizedDescription) }
        if (fd < 0) throw accessError("Couldn't open the file: ${failure ?: "unknown error"}")
        fd
    }

    private fun accessError(detail: String) = DocumentException(DocumentError.FileAccess(detail))
}
