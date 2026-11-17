package com.ezfiles.reader.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ezfiles.engine.ffi.InternalException
import com.ezfiles.engine.ffi.NativePdfPageEdit
import com.ezfiles.engine.ffi.NativePdfSource
import com.ezfiles.engine.ffi.NativePdfToolListener
import com.ezfiles.engine.ffi.NativePdfToolOp
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.pdfToolInspect
import com.ezfiles.engine.ffi.pdfToolRun
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfFileInfo
import com.ezfiles.reader.domain.PdfImageQuality
import com.ezfiles.reader.domain.PdfPageEdit
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfSource
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.domain.ReadLimits
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The engine rewrites into a private file and checks it; only a finished,
 * verified file is copied to the target. Temporary files are always deleted.
 */
internal class AndroidPdfTools(context: Context) : PdfTools {
    private val app = context.applicationContext
    private val workDir = File(app.cacheDir, "pdf-tools")

    override suspend fun inspect(source: PdfSource): PdfFileInfo = withContext(Dispatchers.IO) {
        val info = engineCall { pdfToolInspect(native(source)) }
        PdfFileInfo(info.pageCount.toInt(), info.encrypted, info.isSigned, info.archival, info.printable)
    }

    override suspend fun merge(sources: List<PdfSource>, target: String, onProgress: (Int, Int) -> Unit) =
        rewrite(target, sources.map(::Input), NativePdfToolOp.Merge, onProgress)

    override suspend fun printCopy(source: PdfSource, pageCount: Int, target: String) =
        rewrite(target, listOf(Input(source)), NativePdfToolOp.PrintCopy, { _, _ -> })

    override suspend fun rebuild(source: PdfSource, pages: List<PdfPageEdit>, target: String, onProgress: (Int, Int) -> Unit) =
        rewrite(
            target,
            listOf(Input(source)),
            NativePdfToolOp.Rebuild(pages.map { NativePdfPageEdit(it.sourcePage.toUInt(), it.rotationDegrees) }),
            onProgress,
        )

    override suspend fun resizePages(source: PdfSource, size: PdfPageSize, target: String, onProgress: (Int, Int) -> Unit) {
        if (size == PdfPageSize.FitImage) throw DocumentException(DocumentError.Internal("Pick a paper size to resize to."))
        rewrite(target, listOf(Input(source)), NativePdfToolOp.Resize(size.widthPt, size.heightPt), onProgress)
    }

    /** The pictures become a PDF of their own first, through the same writer as Images to PDF. */
    override suspend fun insertImages(source: PdfSource, images: List<String>, atPage: Int, target: String, onProgress: (Int, Int) -> Unit) {
        if (images.isEmpty()) throw DocumentException(DocumentError.Malformed("There are no pictures to add."))
        if (atPage < 0) throw DocumentException(DocumentError.Malformed("That page isn't in this PDF."))
        val pictures = withContext(Dispatchers.IO) { newWorkFile() }
        try {
            // Pictures first, then the rewrite: progress is reported over both.
            val total = images.size * 2
            AndroidPdfCreator(app).imagesToPdf(
                pages = images.map { PdfPageImage(it) },
                size = PdfPageSize.FitImage,
                marginPt = 0f,
                target = Uri.fromFile(pictures).toString(),
                quality = PdfImageQuality.High,
                onProgress = { done, _ -> report(onProgress, done, total) },
            )
            rewrite(
                target,
                listOf(Input(source), Input(PdfSource(Uri.fromFile(pictures).toString()), pictures)),
                NativePdfToolOp.InsertPages(atPage.toUInt()),
            ) { done, all -> report(onProgress, images.size + done * images.size / all.coerceAtLeast(1), total) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { pictures.delete() }
        }
    }

    override suspend fun addPassword(source: PdfSource, password: String, target: String, onProgress: (Int, Int) -> Unit) =
        rewrite(target, listOf(Input(source)), NativePdfToolOp.AddPassword(password), onProgress)

    override suspend fun removePassword(source: PdfSource, target: String, onProgress: (Int, Int) -> Unit) =
        rewrite(target, listOf(Input(source)), NativePdfToolOp.RemovePassword, onProgress)

    /** A source to open: a user's document, or one of this class's own private files. */
    private class Input(val source: PdfSource, val privateFile: File? = null)

    private suspend fun rewrite(target: String, inputs: List<Input>, op: NativePdfToolOp, onProgress: (Int, Int) -> Unit) {
        val job = coroutineContext[Job]
        var delivering = false
        try {
            withContext(Dispatchers.IO) {
                val output = newWorkFile()
                try {
                    val listener = object : NativePdfToolListener {
                        override fun onProgress(done: UInt, total: UInt) = report(onProgress, done.toInt(), total.toInt())
                        override fun isCancelled(): Boolean = job?.isActive == false
                    }
                    val descriptors = openAll(inputs)
                    try {
                        engineCall { pdfToolRun(descriptors, op, output.path, listener) }
                    } catch (e: DocumentException) {
                        // A cancel shows up from the engine as an error; it is still a cancel.
                        ensureActive()
                        throw e
                    }
                    ensureActive()
                    delivering = true
                    deliver(output, Uri.parse(target))
                } finally {
                    output.delete()
                }
            }
        } catch (e: Throwable) {
            // Only the save dialog's still-empty file is removed: a file being overwritten stays as it was.
            if (!delivering) withContext(NonCancellable + Dispatchers.IO) { discardIfEmpty(Uri.parse(target)) }
            if (e is CancellationException || e is DocumentException) throw e
            if (e is OutOfMemoryError) throw DocumentException(DocumentError.LimitExceeded("This is too large to process on this device."))
            throw DocumentException(DocumentError.Internal(e.message ?: "The file could not be created."))
        }
    }

    private fun newWorkFile(): File {
        workDir.mkdirs()
        return File.createTempFile("tool", ".pdf", workDir)
    }

    /** Every descriptor goes to the engine, which owns it; if one can't be opened, those already open are closed. */
    private fun openAll(inputs: List<Input>): List<NativePdfSource> {
        val opened = mutableListOf<NativePdfSource>()
        try {
            for (input in inputs) opened += native(input.source, input.privateFile)
        } catch (e: Throwable) {
            opened.forEach { closeQuietly(it.fd) }
            throw e
        }
        return opened
    }

    private fun closeQuietly(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (_: IOException) {
            // Nothing more can be done with it.
        }
    }

    private fun native(source: PdfSource, privateFile: File? = null): NativePdfSource {
        val fd = if (privateFile != null) {
            ParcelFileDescriptor.open(privateFile, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        } else {
            AndroidDocumentAccess.get(app).openDescriptor(DocumentRef(source.uri, displayName = ""), ReadLimits.Default)
        }
        return NativePdfSource(fd = fd, path = null, password = source.password?.takeIf { it.isNotEmpty() })
    }

    /** The caller's callback must not break the engine's thread. */
    private fun report(onProgress: (Int, Int) -> Unit, done: Int, total: Int) {
        try {
            onProgress(done, total)
        } catch (_: Exception) {
            // Progress is advisory.
        }
    }

    private fun deliver(output: File, target: Uri) {
        val sink = try {
            app.contentResolver.openOutputStream(target, "wt")
        } catch (_: SecurityException) {
            throw DocumentException(DocumentError.FileAccess("EzFiles doesn't have permission to save there."))
        } catch (_: IOException) {
            throw DocumentException(DocumentError.FileAccess("The file could not be saved there."))
        } ?: throw DocumentException(DocumentError.FileAccess("The app that provides this location did not accept the file."))
        try {
            sink.use { out -> output.inputStream().use { it.copyTo(out) } }
        } catch (e: IOException) {
            // Truncated already, so a partial file would only look finished.
            discardPartial(target)
            throw DocumentException(DocumentError.FileAccess("The file could not be saved there."))
        }
    }

    private fun discardPartial(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(app.contentResolver, uri)
        } catch (_: Exception) {
            // The provider may not allow it; the user is told the save failed either way.
        }
    }

    private fun discardIfEmpty(uri: Uri) {
        try {
            val size = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            if (size == 0L && DocumentsContract.isDocumentUri(app, uri)) DocumentsContract.deleteDocument(app.contentResolver, uri)
        } catch (_: Exception) {
            // Best effort: an empty file left behind harms nothing.
        }
    }
}

private inline fun <T> engineCall(block: () -> T): T = try {
    block()
} catch (e: ReadException) {
    throw DocumentException(
        when (e) {
            is ReadException.Io -> DocumentError.FileAccess(e.detail)
            is ReadException.UnsupportedFormat -> DocumentError.Unsupported(e.detail)
            is ReadException.Malformed -> DocumentError.Malformed(e.detail)
            is ReadException.LimitExceeded -> DocumentError.LimitExceeded(e.detail)
            is ReadException.Cancelled -> DocumentError.Internal(e.detail)
            is ReadException.Internal -> DocumentError.Internal(e.detail)
            is ReadException.PasswordRequired -> DocumentError.PasswordRequired(e.detail)
            is ReadException.WrongPassword -> DocumentError.WrongPassword(e.detail)
            is ReadException.UnsupportedEncryption -> DocumentError.UnsupportedEncryption(e.detail)
        },
    )
} catch (e: InternalException) {
    // A Rust panic caught by UniFFI.
    throw DocumentException(DocumentError.Internal(e.message ?: "engine failure"))
}
