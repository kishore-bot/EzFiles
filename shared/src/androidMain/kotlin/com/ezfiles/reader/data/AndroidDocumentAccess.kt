package com.ezfiles.reader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Resolves `content://` URIs from the Storage Access Framework.
 *
 * Providers usually return a seekable descriptor for a real file, which is
 * handed to the engine directly (zero copy). Streaming providers (some cloud
 * apps) return pipes; those are copied, within the input size limit, into a
 * private cache file that is unlinked as soon as it is opened, so it cannot
 * outlive the session even if the app is killed.
 */
internal class AndroidDocumentAccess private constructor(context: Context) : DocumentAccess {
    private val resolver: ContentResolver = context.contentResolver
    private val importDir = File(context.cacheDir, "document-import").apply {
        // Anything left here is from a process that died mid-copy.
        deleteRecursively()
        mkdirs()
    }

    override fun openDescriptor(ref: DocumentRef, limits: ReadLimits): Int {
        val uri = Uri.parse(ref.uri)
        val descriptor = withAccessErrors { resolver.openFileDescriptor(uri, "r") }
            ?: throw accessError("The app that provides this file did not return it.")
        if (descriptor.isSeekable()) return descriptor.detachFd()
        descriptor.close()
        return copyToPrivateFile(uri, limits.maxInputBytes)
    }

    private fun ParcelFileDescriptor.isSeekable(): Boolean = try {
        Os.lseek(fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (_: ErrnoException) {
        false
    }

    private fun copyToPrivateFile(uri: Uri, maxBytes: Long): Int {
        val file = File.createTempFile("import", ".tmp", importDir)
        try {
            val input = withAccessErrors { resolver.openInputStream(uri) }
                ?: throw accessError("The app that provides this file did not return it.")
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw DocumentException(
                                DocumentError.LimitExceeded("The file is larger than $maxBytes bytes."),
                            )
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        } catch (e: IOException) {
            throw accessError("Reading the file failed: ${e.message}")
        } finally {
            // Unlinking leaves an open descriptor valid; the data is freed
            // when the engine closes it.
            file.delete()
        }
    }

    private inline fun <T> withAccessErrors(block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw accessError("The file is no longer available.")
    } catch (e: SecurityException) {
        throw accessError("Xfiles doesn't have permission to read this file.")
    }

    private fun accessError(detail: String) = DocumentException(DocumentError.FileAccess(detail))

    companion object {
        @Volatile private var instance: AndroidDocumentAccess? = null

        /** One instance per process: creating it clears stale import files. */
        fun get(context: Context): AndroidDocumentAccess =
            instance ?: synchronized(this) {
                instance ?: AndroidDocumentAccess(context.applicationContext).also { instance = it }
            }
    }
}
