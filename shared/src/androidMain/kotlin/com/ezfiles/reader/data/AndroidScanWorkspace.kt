package com.ezfiles.reader.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.platform.LumaImage
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanFilter
import com.ezfiles.reader.presentation.scan.applyScanFilter
import com.ezfiles.reader.presentation.scan.lumaOf
import com.ezfiles.reader.presentation.scan.pageOutputSize
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Under `cacheDir`, so it is never in shared storage and the system may reclaim it. */
internal const val SCAN_CACHE_FOLDER = "scan"

/**
 * Longest side kept of any picture, which bounds a page to about 27 MB
 * uncompressed. Matches what the PDF writer keeps, so nothing is decoded only
 * to be thrown away there.
 */
internal const val MAX_PICTURE_SIDE = 2600

/** Longer side of the small render that text tilt is measured on. */
private const val TILT_SIDE = 900

private const val SOURCE_QUALITY = 90
private const val OUTPUT_QUALITY = 82

// A4 in points.
private const val TEXT_PAGE_WIDTH = 595
private const val TEXT_PAGE_HEIGHT = 842
private const val TEXT_PAGE_MARGIN = 56
private const val TEXT_SIZE = 12f

/** Each folder under [SCAN_CACHE_FOLDER] is one tool's session, removed by [clear] or the next launch. */
internal class AndroidScanWorkspace(context: Context, folder: String) : ScanWorkspace {
    private val app = context.applicationContext
    private val dir = File(File(app.cacheDir, SCAN_CACHE_FOLDER), folder)

    private fun source(id: String) = File(dir, "$id.source.jpg")
    private fun output(id: String) = File(dir, "$id.output")
    private fun capture(id: String) = File(dir, "$id.capture.jpg")
    private val stateFile get() = File(dir, "state.json")

    override fun captureTarget(id: String): String {
        dir.mkdirs()
        return Uri.fromFile(capture(id)).toString()
    }

    override suspend fun importSource(id: String, uri: String) = working {
        dir.mkdirs()
        val bitmap = decodeUpright(app, Uri.parse(uri), MAX_PICTURE_SIDE)
        try {
            ensureActive()
            writeAtomically(source(id)) { bitmap.compress(Bitmap.CompressFormat.JPEG, SOURCE_QUALITY, it) }
        } finally {
            bitmap.recycle()
            capture(id).delete()
        }
    }

    override suspend fun sourceLuma(id: String, maxSide: Int): LumaImage = working {
        val decoded = decodeSampled(source(id), maxSide * 2)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val luma = LumaImage(ByteArray(pixels.size) { lumaOf(pixels[it]).toByte() }, small.width, small.height)
        if (small !== decoded) small.recycle()
        decoded.recycle()
        luma
    }

    override suspend fun render(id: String, quad: PageQuad, filter: ScanFilter) = working {
        val tilt = textTilt(id, quad)
        ensureActive()
        val picture = BitmapFactory.decodeFile(source(id).path) ?: throw missingPicture()
        val page = try {
            straighten(picture, quad, MAX_PICTURE_SIDE, tilt)
        } finally {
            picture.recycle()
        }
        try {
            ensureActive()
            if (filter != ScanFilter.Original) {
                val pixels = IntArray(page.width * page.height)
                page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
                applyScanFilter(filter, pixels, page.width, page.height)
                page.setPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
            }
            ensureActive()
            // Pure black and white compresses far better, and with no halos around letters, as PNG.
            val format = if (filter == ScanFilter.BlackAndWhite) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            writeAtomically(output(id)) { page.compress(format, OUTPUT_QUALITY, it) }
        } finally {
            page.recycle()
        }
    }

    /**
     * How far the text on the straightened page slopes, measured on a small
     * render so it costs a fraction of the real one; 0 when there is no clear text.
     */
    private fun textTilt(id: String, quad: PageQuad): Float {
        val small = decodeSampled(source(id), TILT_SIDE)
        val page = try {
            straighten(small, quad, TILT_SIDE, 0f)
        } finally {
            small.recycle()
        }
        return try {
            val pixels = IntArray(page.width * page.height)
            page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
            val luma = LumaImage(ByteArray(pixels.size) { lumaOf(pixels[it]).toByte() }, page.width, page.height)
            RustImaging.textAngle(luma) ?: 0f
        } finally {
            page.recycle()
        }
    }

    /**
     * The perspective correction: the quad's corners become the corners of a
     * new bitmap, turned back by [tilt] degrees in the same draw so tilted text
     * comes out level without a second copy of the page.
     */
    private fun straighten(picture: Bitmap, quad: PageQuad, maxSide: Int, tilt: Float): Bitmap {
        val (width, height) = pageOutputSize(quad, picture.width, picture.height, maxSide)
        val from = FloatArray(8)
        quad.corners.forEachIndexed { index, corner ->
            from[index * 2] = corner.x * picture.width
            from[index * 2 + 1] = corner.y * picture.height
        }
        val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) {
            throw DocumentException(DocumentError.Malformed("These corners don't make a page. Drag them onto the page's edges."))
        }
        // The corners this uncovers are paper-white, like the page's margins.
        if (tilt != 0f) matrix.postRotate(-tilt, width / 2f, height / 2f)
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(picture, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return page
    }

    override suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) { source(id).exists() && output(id).exists() }

    override suspend fun preview(id: String, kind: ScanPictureKind, maxSide: Int): ImageBitmap = working {
        decodeSampled(file(id, kind), maxSide).asImageBitmap()
    }

    override fun pictureUri(id: String, kind: ScanPictureKind): String = Uri.fromFile(file(id, kind)).toString()

    private fun file(id: String, kind: ScanPictureKind) = when (kind) {
        ScanPictureKind.Source -> source(id)
        ScanPictureKind.Output -> output(id)
    }

    override suspend fun readOutputs(ids: List<String>): List<ByteArray> = working {
        ids.map {
            ensureActive()
            output(it).readBytes()
        }
    }

    override suspend fun deletePage(id: String) = withContext(Dispatchers.IO) {
        source(id).delete()
        output(id).delete()
        capture(id).delete()
        Unit
    }

    override suspend fun readState(): String? = withContext(Dispatchers.IO) {
        try {
            stateFile.takeIf(File::exists)?.readText()
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun writeState(state: String) = working {
        dir.mkdirs()
        writeAtomically(stateFile) { it.write(state.encodeToByteArray()) }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }

    override suspend fun writeTextPdf(text: String, target: String) = working {
        val document = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TEXT_SIZE
                color = Color.BLACK
            }
            val contentWidth = TEXT_PAGE_WIDTH - TEXT_PAGE_MARGIN * 2
            val contentHeight = TEXT_PAGE_HEIGHT - TEXT_PAGE_MARGIN * 2
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            var firstLine = 0
            var pageNumber = 1
            do {
                ensureActive()
                val top = layout.getLineTop(firstLine)
                var endLine = firstLine
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= contentHeight) endLine++
                // A single line taller than a page still has to go somewhere.
                if (endLine == firstLine) endLine = firstLine + 1
                val page = document.startPage(PdfDocument.PageInfo.Builder(TEXT_PAGE_WIDTH, TEXT_PAGE_HEIGHT, pageNumber++).create())
                val canvas = page.canvas
                canvas.translate(TEXT_PAGE_MARGIN.toFloat(), (TEXT_PAGE_MARGIN - top).toFloat())
                canvas.clipRect(0, top, contentWidth, layout.getLineBottom(endLine - 1))
                layout.draw(canvas)
                document.finishPage(page)
                firstLine = endLine
            } while (firstLine < layout.lineCount)
            val stream = app.contentResolver.openOutputStream(Uri.parse(target), "wt")
                ?: throw IOException("The app that provides this file did not accept it.")
            stream.use(document::writeTo)
        } finally {
            document.close()
        }
    }

    override suspend fun deleteDocument(target: String) {
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(target))
            } catch (_: Exception) {
                // Best effort: the provider may not allow deleting. An empty file is left at worst.
            }
        }
    }

    /** A crash or a full disk midway never replaces a good file with a broken one. */
    private inline fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) {
        val partial = File(file.path + ".partial")
        try {
            partial.outputStream().use(write)
            if (!partial.renameTo(file)) throw IOException("The scan's working file couldn't be saved.")
        } finally {
            partial.delete()
        }
    }

    private fun missingPicture() = DocumentException(DocumentError.FileAccess("This page's picture is gone. Please retake it."))

    private fun decodeSampled(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw missingPicture()
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(file.path, options) ?: throw missingPicture()
    }

    /** All picture work is off the main thread and fails with a typed error, never a crash. */
    private suspend inline fun <T> working(crossinline block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { guarded { block() } }
}

/** Translates what can go wrong with a picture into the app's typed errors. */
internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: OutOfMemoryError) {
    throw DocumentException(DocumentError.LimitExceeded("There isn't enough memory for this picture. Close other apps or remove some pages."))
} catch (e: IOException) {
    throw DocumentException(DocumentError.FileAccess(e.message ?: "The picture couldn't be read or saved."))
} catch (_: SecurityException) {
    throw DocumentException(DocumentError.FileAccess("Xfiles no longer has access to this picture."))
}

/** The smallest power-of-two reduction that fits [maxSide], which is what the decoder supports. */
internal fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > maxSide) sample *= 2
    return sample
}

/**
 * Decodes an untrusted picture: bounded to [maxSide], whatever size it claims,
 * and turned upright by its EXIF orientation. Throws [DocumentException].
 */
internal fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw DocumentException(DocumentError.Malformed("This picture couldn't be read."))
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide) }
    val bitmap = open(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw DocumentException(DocumentError.Malformed("This picture couldn't be read."))
    val orientation = try {
        open(resolver, uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (upright !== bitmap) bitmap.recycle()
    return upright
}

private fun open(resolver: ContentResolver, uri: Uri) =
    resolver.openInputStream(uri) ?: throw IOException("The app that provides this picture did not return it.")
