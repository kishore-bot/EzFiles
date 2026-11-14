package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits

/**
 * Platform adapter that normalizes a picked document into the one input form
 * the engine accepts: an open, readable, seekable POSIX file descriptor.
 *
 * Android resolves content URIs (copying non-seekable streams to a private
 * file); iOS performs security-scoped, coordinated access.
 */
internal interface DocumentAccess {
    /**
     * Blocking; call off the main thread. Ownership of the returned descriptor
     * passes to the caller, which must hand it to the engine without any
     * suspension point in between (the engine then owns and closes it).
     *
     * @throws com.ezfiles.reader.domain.DocumentException
     */
    fun openDescriptor(ref: DocumentRef, limits: ReadLimits): Int
}
