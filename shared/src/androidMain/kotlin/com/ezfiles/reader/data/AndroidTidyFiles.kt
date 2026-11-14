package com.ezfiles.reader.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.ezfiles.reader.application.GrayImage
import com.ezfiles.reader.application.TidyFiles
import com.ezfiles.reader.domain.DocumentRef
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Files for Tidy up through the Storage Access Framework, with only the
 * folder grants the user already gave. Deletion also requires a persisted
 * write grant and the provider's explicit document-delete capability.
 */
internal class AndroidTidyFiles(context: Context) : TidyFiles {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** A plain descriptor, no private copy: a file a provider can only stream is left out of hashing. */
    override fun openDescriptor(ref: DocumentRef): Int? = try {
        resolver.openFileDescriptor(ref.uri.toUri(), "r")?.detachFd()
    } catch (e: FileNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    override fun grayThumbnail(ref: DocumentRef, side: Int): GrayImage? {
        val uri = ref.uri.toUri()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(uri, bounds) // fills in the size only
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decoders downscale by powers of two while reading (JPEG in the DCT), so a
        // 50-megapixel photo becomes a few hundred pixels without ever being whole.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = decode(uri, options) ?: return null
        // May return [decoded] itself when it is already the right size.
        val scaled = decoded.scale(side, side, true)
        try {
            val argb = IntArray(side * side)
            scaled.getPixels(argb, 0, side, 0, 0, side, side)
            val gray = ByteArray(argb.size) { i ->
                val p = argb[i]
                // ITU-R BT.601 luma, in integers.
                ((((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte()
            }
            return GrayImage(side, side, gray)
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun decode(uri: Uri, options: BitmapFactory.Options): Bitmap? = try {
        resolver.openFileDescriptor(uri, "r")?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
    } catch (e: FileNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        // A broken header claiming a huge size; leave the picture out.
        null
    }

    override fun share(ref: DocumentRef): Boolean {
        val uri = ref.uri.toUri()
        val type = try {
            resolver.getType(uri)
        } catch (e: SecurityException) {
            null
        } ?: "*/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            setType(type)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(ref.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${ref.displayName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    override val canDelete: Boolean = true

    override fun deleteDuplicate(ref: DocumentRef, retained: DocumentRef): Boolean {
        val target = ref.uri.toUri()
        val keeper = retained.uri.toUri()
        if (androidDocumentIdentity(target) == androidDocumentIdentity(keeper) || !mayDelete(target)) return false
        return try {
            val identical = resolver.openInputStream(target)?.use { first ->
                resolver.openInputStream(keeper)?.use { second ->
                    val left = ByteArray(32 * 1024)
                    val right = ByteArray(left.size)
                    var equal = true
                    while (true) {
                        val count = first.readChunk(left)
                        if (count != second.readChunk(right)) { equal = false; break }
                        if (count == 0) break
                        if ((0 until count).any { left[it] != right[it] }) { equal = false; break }
                    }
                    equal
                } == true
            } == true
            identical && delete(ref)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun mayDelete(uri: Uri): Boolean = try {
        if (!DocumentsContract.isDocumentUri(app, uri) || !DocumentsContract.isTreeUri(uri)) false
        else {
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } &&
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    cursor.moveToFirst() && !cursor.isNull(0) &&
                        (cursor.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()) != 0L &&
                        cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR
                } == true
        }
    } catch (_: RuntimeException) {
        false
    }

    override fun delete(ref: DocumentRef): Boolean {
        val uri = ref.uri.toUri()
        if (!mayDelete(uri)) return false
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }
}

/** Fill one comparison chunk even when providers return short reads. Zero means EOF. */
private fun InputStream.readChunk(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val count = read(buffer, filled, buffer.size - filled)
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            buffer[filled++] = next.toByte()
        } else filled += count
    }
    return filled
}
