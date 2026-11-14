package com.ezfiles.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.platform.PageImageFormat
import com.ezfiles.reader.platform.PdfConverter
import com.ezfiles.reader.platform.PdfZipWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val JPEG_QUALITY = 90
private const val BUFFER_BYTES = 64 * 1024

internal class AndroidPdfConverter(context: Context) : PdfConverter {
    private val app = context.applicationContext

    override suspend fun encodeAndRelease(image: ImageBitmap, format: PageImageFormat): ByteArray = withContext(Dispatchers.Default) {
        val bitmap = image.asAndroidBitmap()
        try {
            val out = ByteArrayOutputStream(bitmap.byteCount / 8)
            val done = when (format) {
                PageImageFormat.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                PageImageFormat.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!done) throw DocumentException(DocumentError.Internal("The picture could not be encoded."))
            out.toByteArray()
        } catch (_: OutOfMemoryError) {
            throw DocumentException(DocumentError.LimitExceeded("There isn't enough memory for this picture. Try a lower resolution or JPEG."))
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun newZip(): PdfZipWriter = withContext(Dispatchers.IO) {
        val folder = File(app.cacheDir, "pdf-zip")
        accessing("The archive could not be started.") {
            folder.deleteRecursively() // Left behind by a process that died mid-way.
            if (!folder.mkdirs() && !folder.isDirectory) throw IOException("No private folder")
            AndroidPdfZipWriter(app, File.createTempFile("archive", ".zip", folder))
        }
    }
}

private class AndroidPdfZipWriter(private val app: Context, private val file: File) : PdfZipWriter {
    private val zip = ZipOutputStream(file.outputStream().buffered(BUFFER_BYTES))

    override suspend fun addDocument(ref: DocumentRef, entryName: String, onProgress: (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        accessing("The PDF could not be read or the archive could not be written. Check free space.") {
            val input = app.contentResolver.openInputStream(Uri.parse(ref.uri))
                ?: throw IOException("The app that provides this file did not return it.")
            val active = coroutineContext
            input.use {
                zip.addCompressedEntry(entryName, it, ReadLimits.Default.maxInputBytes) { read ->
                    active.ensureActive()
                    onProgress(read)
                }
            }
        }
    }

    override suspend fun addStored(entryName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        accessing("The archive could not be written. Check free space.") { zip.addStoredEntry(entryName, bytes) }
    }

    override suspend fun finish(): Long = withContext(Dispatchers.IO) {
        accessing("The archive could not be finished. Check free space.") {
            zip.close()
            file.length()
        }
    }

    override suspend fun copyTo(targetUri: String): Unit = withContext(Dispatchers.IO) {
        val target = Uri.parse(targetUri)
        try {
            val sink: OutputStream = app.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("The app that provides this location did not accept the file.")
            sink.use { output -> file.inputStream().use { it.copyTo(output, BUFFER_BYTES) } }
        } catch (e: IOException) {
            discardPartial(target)
            throw DocumentException(DocumentError.FileAccess("The ZIP could not be saved there."))
        } catch (e: SecurityException) {
            discardPartial(target)
            throw DocumentException(DocumentError.FileAccess("EzFiles doesn't have permission to save there."))
        }
    }

    private fun discardPartial(target: Uri) {
        try {
            DocumentsContract.deleteDocument(app.contentResolver, target)
        } catch (_: Exception) {
            // The provider may not allow it; the user is told the save failed either way.
        }
    }

    override fun discard() {
        try {
            zip.close()
        } catch (_: IOException) {
            // Half-written or already closed; it's about to be deleted.
        }
        file.delete()
    }
}

private inline fun <T> accessing(message: String, block: () -> T): T = try {
    block()
} catch (e: IOException) {
    throw DocumentException(DocumentError.FileAccess(message))
} catch (e: SecurityException) {
    throw DocumentException(DocumentError.FileAccess("EzFiles no longer has access to this file."))
}

/**
 * Streams [input] into a new compressed entry and returns its size. [onChunk]
 * runs after each chunk and may throw to stop. Refuses input past [maxBytes].
 */
internal fun ZipOutputStream.addCompressedEntry(entryName: String, input: InputStream, maxBytes: Long, onChunk: (Long) -> Unit = {}): Long {
    putNextEntry(ZipEntry(entryName))
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw DocumentException(DocumentError.LimitExceeded("This PDF is larger than ${maxBytes / (1024 * 1024)} MB, the most EzFiles will archive."))
        }
        write(buffer, 0, read)
        onChunk(total)
    }
    closeEntry()
    return total
}

/** A stored entry has to declare its size and checksum up front. */
internal fun ZipOutputStream.addStoredEntry(entryName: String, bytes: ByteArray) {
    val entry = ZipEntry(entryName).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}
