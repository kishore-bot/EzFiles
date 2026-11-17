package com.ezfiles.reader.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ezfiles.reader.data.AndroidPdfCreator
import com.ezfiles.reader.data.AndroidPdfTools
import com.ezfiles.reader.data.AndroidDocumentAccess
import com.ezfiles.reader.data.AndroidDocumentPdf
import com.ezfiles.reader.data.RustDocumentReader
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfPageEdit
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfSource
import com.ezfiles.reader.domain.TextPdfStyle
import com.ezfiles.reader.domain.TextPdfTemplate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A concrete provider avoids the device-specific failures documented for using FileProvider directly. */
class DeliveryFileProvider : FileProvider()

@Composable
actual fun rememberDocumentDelivery(): DocumentDelivery {
    val context = LocalContext.current
    return remember(context) { AndroidDocumentDelivery(context) }
}

private class AndroidDocumentDelivery(private val context: Context) : DocumentDelivery {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val pdf = AndroidPdfTools(app)
    private val creator = AndroidPdfCreator(app)

    override fun canPrint(ref: DocumentRef): Boolean = printKind(ref) != null

    override suspend fun share(refs: List<DocumentRef>) = deliveryErrors {
        if (refs.isEmpty() || refs.size > 32) fail("Choose between 1 and 32 files to share.")
        foreground()
        var prepared = prepareShare(refs, copyAll = false)
        var handedOff = false
        try {
            try {
                launchShare(prepared.uris, prepared.types) { handedOff = true }
            } catch (_: SecurityException) {
                // Providers can refuse delegation even when they advertised URI grants.
                withContext(Dispatchers.IO) { DeliveryFiles.remove(prepared.directory) }
                prepared = prepareShare(refs, copyAll = true)
                launchShare(prepared.uris, prepared.types) { handedOff = true }
            }
        } catch (error: Exception) {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { DeliveryFiles.remove(prepared.directory) }
            throw error
        }
        // A receiving app may read much later. Keep staging until age-based cleanup, not chooser dismissal.
    }

    private data class SharedFiles(val directory: File, val uris: List<Uri>, val types: List<String>)

    private suspend fun prepareShare(refs: List<DocumentRef>, copyAll: Boolean): SharedFiles =
        staged { directory ->
            val uris = mutableListOf<Uri>()
            val types = mutableListOf<String>()
            for ((index, ref) in refs.withIndex()) {
                coroutineContext.ensureActive()
                val source = contentUri(ref)
                types += mimeType(source, ref.displayName)
                if (!copyAll && canDelegate(source)) {
                    resolver.openAssetFileDescriptor(source, "r")?.use { }
                        ?: fail("The app providing ${ref.displayName} did not return the file.")
                    uris += source
                } else {
                    DeliveryFiles.checkRoom(app, DeliveryFiles.MAX_FILE_BYTES)
                    val folder = File(directory, index.toString()).apply { mkdirs() }
                    val target = File(folder, safeName(ref.displayName))
                    copyBounded(source, target, DeliveryFiles.MAX_FILE_BYTES)
                    uris += deliveryUri(app, target)
                }
            }
            SharedFiles(directory, uris, types)
        }

    private fun canDelegate(uri: Uri): Boolean {
        if (app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) return false
        val provider = app.packageManager.resolveContentProvider(uri.authority ?: return false, 0) ?: return false
        return provider.grantUriPermissions || provider.uriPermissionPatterns?.any { it.match(uri.path) } == true
    }

    private suspend fun launchShare(uris: List<Uri>, types: List<String>, onHandoff: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        val activity = foreground()
        val mime = when {
            types.distinct().size == 1 -> types.first()
            types.map { it.substringBefore('/') }.distinct().size == 1 -> "${types.first().substringBefore('/')}/*"
            else -> "*/*"
        }
        val clip = ClipData(ClipDescription("Shared documents", types.distinct().toTypedArray()), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        coroutineContext.ensureActive()
        activity.startActivity(Intent.createChooser(send, "Share original").apply {
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        onHandoff()
    }

    override suspend fun shareText(text: String, title: String) = deliveryErrors {
        if (text.isBlank()) fail("There is no text to share.")
        if (text.length > 100_000) throw DocumentException(DocumentError.LimitExceeded("Share at most 100,000 characters at once."))
        withContext(Dispatchers.Main.immediate) {
            coroutineContext.ensureActive()
            foreground().startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title.take(120))
            }, "Share text"))
        }
    }

    override suspend fun print(ref: DocumentRef, password: String?) = deliveryErrors {
        foreground()
        val kind = printKind(ref) ?: throw DocumentException(DocumentError.Unsupported("Sound and video files can't be printed."))
        val prepared = staged { directory ->
            // A protected input and its permitted printable copy can coexist briefly.
            DeliveryFiles.checkRoom(app, DeliveryFiles.MAX_FILE_BYTES * 2)
            val source = contentUri(ref)
            val target = File(directory, "print.pdf")
            val targetUri = deliveryUri(app, target).toString()
            val count = when (kind) {
                PrintKind.Pdf -> {
                    val snapshot = File(directory, "input.pdf")
                    copyBounded(source, snapshot, DeliveryFiles.MAX_FILE_BYTES)
                    try {
                        val input = PdfSource(deliveryUri(app, snapshot).toString(), password)
                        val info = pdf.inspect(input)
                        if (info.encrypted) {
                            // A plain private copy for the spooler. It needs only the print permission, so a
                            // watermarked PDF that opens for anyone doesn't ask for its owner password.
                            pdf.printCopy(input, info.pageCount, targetUri)
                        } else if (!snapshot.renameTo(target)) {
                            throw IOException("The private print copy could not be prepared.")
                        }
                        info.pageCount
                    } finally {
                        snapshot.delete()
                    }
                }
                PrintKind.Image -> {
                    creator.imagesToPdf(listOf(PdfPageImage(source.toString())), PdfPageSize.FitImage, 0f, targetUri)
                    1
                }
                PrintKind.Text -> {
                    val bytes = readTextBytes(source)
                    creator.textToPdf(decodePrintText(bytes), TextPdfStyle.of(TextPdfTemplate.MinimalLetter), targetUri)
                }
                // Any other format: the engine reads it, and it is laid out as a PDF to print.
                PrintKind.Document -> RustDocumentReader(AndroidDocumentAccess.get(app)).open(ref, ReadLimits.Default, password = password).use { session ->
                    target.outputStream().use { AndroidDocumentPdf(session).write(it) }
                }
            }
            if (target.length() > DeliveryFiles.MAX_FILE_BYTES) throw DocumentException(DocumentError.LimitExceeded("This print document exceeds the 64 MiB delivery limit."))
            if (count <= 0) throw DocumentException(DocumentError.Malformed("This document has no printable pages."))
            DeliveryFiles.pin(directory)
            Triple(directory, target, count)
        }
        var handedOff = false
        try {
            withContext(Dispatchers.Main.immediate) {
                val activity = foreground()
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: fail("Printing is unavailable on this device.")
                coroutineContext.ensureActive()
                manager.print(ref.displayName.take(120), AndroidDocumentPrintAdapter(prepared.second, prepared.third, ref.displayName) {
                    DeliveryFiles.remove(prepared.first)
                }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                handedOff = true
                Unit
            }
        } catch (error: Exception) {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { DeliveryFiles.remove(prepared.first) }
            throw error
        }
    }

    /** Own the directory outside withContext too: cancellation can happen while its result is dispatched. */
    private suspend fun <T> staged(block: suspend (File) -> T): T {
        var directory: File? = null
        try {
            return withContext(Dispatchers.IO) {
                DeliveryFiles.mutex.withLock {
                    val created = DeliveryFiles.create(app)
                    directory = created
                    block(created)
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { directory?.let(DeliveryFiles::remove) }
            throw error
        }
    }

    private fun decodePrintText(bytes: ByteArray): String {
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) {
            throw DocumentException(DocumentError.Unsupported("This text encoding cannot be printed safely. Export a UTF-8 copy first."))
        }
        if ('\u0000' in text) throw DocumentException(DocumentError.Unsupported("This file contains binary data and cannot be printed as text."))
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun contentUri(ref: DocumentRef): Uri = Uri.parse(ref.uri).also {
        if (it.scheme != "content") fail("Open this file through the system picker before sharing or printing it.")
    }

    private fun foreground(): Activity {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        val activity = current as? Activity ?: fail("Return to EzFiles to share or print.")
        if (activity.isFinishing || activity.isDestroyed || (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false) {
            fail("Return to EzFiles to share or print.")
        }
        return activity
    }

    private fun mimeType(uri: Uri, name: String): String =
        (resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream").lowercase(Locale.ROOT)

    private suspend fun copyBounded(uri: Uri, target: File, limit: Long) {
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw DocumentException(DocumentError.LimitExceeded("This file is too large for a private delivery copy (64 MiB)."))
                    output.write(buffer, 0, count)
                }
            }
        } ?: fail("The app providing this file did not return it.")
    }

    private suspend fun readTextBytes(uri: Uri): ByteArray {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (output.size() + count > 256 * 1024) throw DocumentException(DocumentError.LimitExceeded("Print plain-text files up to 256 KiB; export larger text to PDF first."))
                output.write(buffer, 0, count)
            }
        } ?: fail("The app providing this text did not return it.")
    }
}

internal fun deliveryUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.delivery", file)

private fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").take(120).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "document"

private fun fail(message: String): Nothing = throw DocumentException(DocumentError.FileAccess(message))

private suspend fun <T> deliveryErrors(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: DocumentException) {
    throw error
} catch (_: SecurityException) {
    fail("EzFiles no longer has access to this file. Open it again through the system picker.")
} catch (_: IOException) {
    fail("The file could not be prepared. Check available storage and try again.")
} catch (_: android.content.ActivityNotFoundException) {
    fail("No app is available to handle this action.")
}

private object DeliveryFiles {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val active = ConcurrentHashMap.newKeySet<File>()
    val mutex = Mutex()

    fun create(context: Context): File {
        val root = File(context.cacheDir, "delivery").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        root.listFiles()?.filter { it !in active && it.lastModified() < cutoff }?.forEach(::remove)
        return File(root, UUID.randomUUID().toString()).apply { if (!mkdir()) fail("There is no space to prepare this file.") }
    }

    fun checkRoom(context: Context, additional: Long) {
        val used = File(context.cacheDir, "delivery").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (used + additional > MAX_CACHE_BYTES || context.cacheDir.usableSpace < additional + 8L * 1024 * 1024) {
            throw DocumentException(DocumentError.LimitExceeded("Delivery storage is full. Existing shared copies expire after 24 hours; free some device storage and try again."))
        }
    }

    fun remove(directory: File) {
        directory.walkBottomUp().forEach { it.delete() }
        active.remove(directory)
    }

    fun pin(directory: File) { active.add(directory) }
}
