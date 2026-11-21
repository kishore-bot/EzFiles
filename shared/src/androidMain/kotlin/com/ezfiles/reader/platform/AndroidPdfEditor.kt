package com.ezfiles.reader.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.ezfiles.engine.ffi.InternalException
import com.ezfiles.engine.ffi.NativePdfAnnotation
import com.ezfiles.engine.ffi.NativePdfBox
import com.ezfiles.engine.ffi.NativePdfEditInfo
import com.ezfiles.engine.ffi.NativePdfInkStyle
import com.ezfiles.engine.ffi.NativePdfStroke
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.pdfAppendFd
import com.ezfiles.engine.ffi.pdfInspectFd
import com.ezfiles.reader.data.AndroidDocumentAccess
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Every save is first written to a private file and verified by the engine, so
 * a failure can't reach the user's storage half-done.
 */
internal class AndroidPdfEditor(context: Context, private val ref: DocumentRef, private val pageCount: Int) : PdfEditor {
    private val app = context.applicationContext
    private val saveDir = File(app.cacheDir, "pdf-save")

    private fun openSource(): Int = AndroidDocumentAccess.get(app).openDescriptor(ref, ReadLimits.Default)

    override suspend fun inspect(): PdfEditInfo = withContext(Dispatchers.IO) {
        try {
            engineCall { pdfInspectFd(openSource()) }.toDomain(pageCount)
        } catch (e: DocumentException) {
            PdfEditInfo(saveBlock = PdfSaveBlock.Unreadable(e.error.detail))
        }
    }

    override suspend fun canOverwrite(): Boolean = withContext(Dispatchers.IO) {
        val granted = app.checkUriPermission(
            Uri.parse(ref.uri),
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        granted && AndroidDocumentFiles(app).canWrite(ref.uri)
    }

    override suspend fun saveCopy(annotations: List<PdfAnnotation>, targetUri: String) = withContext(Dispatchers.IO) {
        withSavedFile(annotations) { saved, _ ->
            val target = Uri.parse(targetUri)
            try {
                // "wt" truncates whatever the save dialog created there.
                val sink = app.contentResolver.openOutputStream(target, "wt")
                    ?: throw IOException("The app that provides this location did not accept the file.")
                sink.use { output -> saved.inputStream().use { it.copyTo(output) } }
            } catch (e: IOException) {
                discardPartial(target)
                throw DocumentException(DocumentError.FileAccess("The copy could not be saved there."))
            } catch (e: SecurityException) {
                discardPartial(target)
                throw DocumentException(DocumentError.FileAccess("Xfiles doesn't have permission to save there."))
            }
        }
    }

    /** Don't leave a partial PDF that looks like a finished one. */
    private fun discardPartial(target: Uri) {
        try {
            DocumentsContract.deleteDocument(app.contentResolver, target)
        } catch (_: Exception) {
            // The provider may not allow it; the user is told the save failed either way.
        }
    }

    override suspend fun overwrite(annotations: List<PdfAnnotation>) = withContext(Dispatchers.IO) {
        withSavedFile(annotations) { saved, originalLength -> appendInPlace(saved, originalLength) }
    }

    /**
     * Writes only what comes after the original's bytes, without ever
     * truncating them, so a failure leaves the original readable. A location
     * that can't seek can't do this, and is refused.
     */
    private fun appendInPlace(saved: File, originalLength: Long) {
        val notInPlace = DocumentError.FileAccess("This location can't be updated in place. Use Save a copy instead.")
        val descriptor = try {
            app.contentResolver.openFileDescriptor(Uri.parse(ref.uri), "rw")
        } catch (e: IOException) {
            throw DocumentException(notInPlace)
        } catch (e: SecurityException) {
            throw DocumentException(notInPlace)
        } ?: throw DocumentException(notInPlace)
        descriptor.use { open ->
            if (open.statSize != originalLength) {
                throw DocumentException(DocumentError.FileAccess("The file changed while saving, so nothing was written. Use Save a copy instead."))
            }
            val output = FileOutputStream(open.fileDescriptor).channel
            try {
                output.position(originalLength)
            } catch (e: IOException) {
                throw DocumentException(notInPlace)
            }
            try {
                FileInputStream(saved).use { input ->
                    val source = input.channel
                    var position = originalLength
                    val end = saved.length()
                    while (position < end) {
                        val moved = source.transferTo(position, end - position, output)
                        if (moved <= 0) throw IOException("The location stopped accepting data.")
                        position += moved
                    }
                }
                open.fileDescriptor.sync()
            } catch (e: IOException) {
                restoreLength(output, originalLength)
                throw DocumentException(DocumentError.FileAccess("The update could not be written. The original content was not changed."))
            }
        }
    }

    private fun restoreLength(output: FileChannel, length: Long) {
        try {
            output.truncate(length)
        } catch (_: IOException) {
            // Every original byte is still there; readers find the old section by searching back.
        }
    }

    private inline fun withSavedFile(annotations: List<PdfAnnotation>, deliver: (File, Long) -> Unit) {
        saveDir.deleteRecursively() // Left behind by a process that died mid-save.
        saveDir.mkdirs()
        val saved = File.createTempFile("save", ".pdf", saveDir)
        try {
            val report = engineCall { pdfAppendFd(openSource(), saved.path, annotations.map(PdfAnnotation::toNative)) }
            deliver(saved, report.originalLen.toLong())
        } finally {
            saved.delete()
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

internal fun NativePdfEditInfo.toDomain(rendererPages: Int): PdfEditInfo = when {
    encrypted -> PdfEditInfo(saveBlock = PdfSaveBlock.Encrypted)
    pageCount.toInt() != rendererPages -> PdfEditInfo(saveBlock = PdfSaveBlock.PageMismatch)
    else -> PdfEditInfo(
        saveBlock = null,
        signed = isSigned,
        archival = archival,
        existing = annotations.map(NativePdfAnnotation::toDomain),
    )
}

internal fun PdfAnnotation.toNative(): NativePdfAnnotation = when (this) {
    is PdfAnnotation.Highlight -> NativePdfAnnotation.Highlight(
        page = page.toUInt(),
        color = color.toUInt(),
        boxes = boxes.map { NativePdfBox(it.left, it.top, it.right, it.bottom) },
    )
    is PdfAnnotation.Ink -> NativePdfAnnotation.Ink(
        page = page.toUInt(),
        color = color.toUInt(),
        width = width,
        strokes = strokes.map { stroke -> NativePdfStroke(stroke.flatMap { listOf(it.x, it.y) }) },
        style = when (style) {
            PdfInkStyle.Pen -> NativePdfInkStyle.PEN
            PdfInkStyle.Marker -> NativePdfInkStyle.MARKER
            PdfInkStyle.Signature -> NativePdfInkStyle.SIGNATURE
        },
    )
    is PdfAnnotation.Note -> NativePdfAnnotation.Note(page.toUInt(), color.toUInt(), x, y, text)
}

internal fun NativePdfAnnotation.toDomain(): PdfAnnotation = when (this) {
    is NativePdfAnnotation.Highlight -> PdfAnnotation.Highlight(
        page = page.toInt(),
        color = color.toInt(),
        boxes = boxes.map { PdfBox(it.left, it.top, it.right, it.bottom) },
    )
    is NativePdfAnnotation.Ink -> PdfAnnotation.Ink(
        page = page.toInt(),
        color = color.toInt(),
        width = width,
        strokes = strokes.map { stroke -> stroke.points.chunked(2).filter { it.size == 2 }.map { PdfPoint(it[0], it[1]) } },
        style = when (style) {
            NativePdfInkStyle.PEN -> PdfInkStyle.Pen
            NativePdfInkStyle.MARKER -> PdfInkStyle.Marker
            NativePdfInkStyle.SIGNATURE -> PdfInkStyle.Signature
        },
    )
    is NativePdfAnnotation.Note -> PdfAnnotation.Note(page.toInt(), color.toInt(), x, y, text)
}
