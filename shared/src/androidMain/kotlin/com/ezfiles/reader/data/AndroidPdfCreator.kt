package com.ezfiles.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.ImageTurn
import com.ezfiles.reader.domain.JpegPdfWriter
import com.ezfiles.reader.domain.MAX_TEXT_PDF_CHARS
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfImageQuality
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfTextLine
import com.ezfiles.reader.domain.TextLineBox
import com.ezfiles.reader.domain.TextPageBox
import com.ezfiles.reader.domain.TextPagePreview
import com.ezfiles.reader.domain.TextPageSlice
import com.ezfiles.reader.domain.TextPaginator
import com.ezfiles.reader.domain.TextPdfAlignment
import com.ezfiles.reader.domain.TextPdfDecoration
import com.ezfiles.reader.domain.TextPdfFont
import com.ezfiles.reader.domain.TextPdfFormatter
import com.ezfiles.reader.domain.TextPdfRunKind
import com.ezfiles.reader.domain.TextPdfStyle
import com.ezfiles.reader.domain.imageSampleSize
import com.ezfiles.reader.domain.placeImage
import com.ezfiles.reader.presentation.scan.lumaOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How many pixels per point the PNG export has. */
private const val PNG_SCALE = 2f

/** Bounds a preview bitmap whatever the caller asks for. */
private const val MAX_PREVIEW_WIDTH_PX = 1600

/**
 * Pictures without a text layer go through [JpegPdfWriter]. Text uses the
 * platform's PDF writer, which embeds fonts and shapes complex scripts.
 */
internal class AndroidPdfCreator(context: Context) : PdfCreator {
    private val app = context.applicationContext

    /** Paging through a preview and then exporting shouldn't redo the layout. */
    private val layoutLock = Mutex()
    private var lastLayout: LaidOutText? = null

    override suspend fun imagesToPdf(
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        marginPt: Float,
        target: String,
        textLayers: List<List<PdfTextLine>>,
        quality: PdfImageQuality,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = creating(target) {
        if (pages.isEmpty()) throw DocumentException(DocumentError.Malformed("There are no pictures to save."))
        throughTemp(target, ".pdf") { out ->
            if (textLayers.any(List<PdfTextLine>::isNotEmpty)) {
                writeWithTextLayers(out, pages, size, marginPt, textLayers, quality, onProgress)
            } else {
                writeJpegPages(out, pages, size, marginPt, quality, onProgress)
            }
        }
    }

    override suspend fun imageThumbnail(uri: String, maxSidePx: Int): ByteArray = translatingErrors {
        val bitmap = decodeUpright(PdfPageImage(uri), 0, maxSidePx)
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun textToPdf(
        text: String,
        style: TextPdfStyle,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = creating(target) {
        val laidOut = layoutOf(text, style)
        throughTemp(target, ".pdf") { out ->
            val document = PdfDocument()
            try {
                laidOut.pages.indices.forEach { index ->
                    ensureActive()
                    val info = PdfDocument.PageInfo.Builder(laidOut.box.pageWidth, laidOut.box.pageHeight, index + 1).create()
                    val page = document.startPage(info)
                    laidOut.draw(page.canvas, index)
                    document.finishPage(page)
                    onProgress(index + 1, laidOut.pages.size)
                }
                ensureActive()
                document.writeTo(out)
            } finally {
                document.close()
            }
        }
        laidOut.pages.size
    }

    override suspend fun textToPng(text: String, style: TextPdfStyle, target: String): Int = creating(target) {
        val laidOut = layoutOf(text, style)
        val bitmap = laidOut.render(0, (laidOut.box.pageWidth * PNG_SCALE).roundToInt())
        try {
            throughTemp(target, ".png") { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("The picture could not be encoded.")
            }
        } finally {
            bitmap.recycle()
        }
        laidOut.pages.size
    }

    override suspend fun textPreview(text: String, style: TextPdfStyle, pageIndex: Int, widthPx: Int): TextPagePreview =
        translatingErrors {
            val laidOut = layoutOf(text, style)
            val index = pageIndex.coerceIn(0, laidOut.pages.lastIndex)
            val bitmap = laidOut.render(index, widthPx.coerceIn(1, MAX_PREVIEW_WIDTH_PX))
            try {
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                TextPagePreview(png, index, laidOut.pages.size, laidOut.box.pageWidth, laidOut.box.pageHeight)
            } finally {
                bitmap.recycle()
            }
        }

    /** One decoded picture at a time: its pixels are gone before the next page starts. */
    private fun CoroutineScope.writeJpegPages(
        out: OutputStream,
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        margin: Float,
        quality: PdfImageQuality,
        onProgress: (Int, Int) -> Unit,
    ) {
        val writer = JpegPdfWriter { bytes, length -> out.write(bytes, 0, length) }
        pages.forEachIndexed { index, image ->
            ensureActive()
            val bitmap = opaque(decodeUpright(image, index, quality.maxSidePx))
            try {
                val jpeg = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, lowestGoodQuality(bitmap, quality), jpeg)) {
                    throw DocumentException(DocumentError.Malformed("Picture ${index + 1} could not be encoded."))
                }
                writer.addPage(placeImage(bitmap.width, bitmap.height, size, margin), bitmap.width, bitmap.height, jpeg.toByteArray())
            } finally {
                bitmap.recycle()
            }
            onProgress(index + 1, pages.size)
        }
        writer.finish()
    }

    /**
     * Invisible text needs embedded fonts, which only the platform's writer has.
     * It holds every page's pixels until [PdfDocument.writeTo], so this path's
     * memory grows with the page count.
     */
    private fun CoroutineScope.writeWithTextLayers(
        out: OutputStream,
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        margin: Float,
        textLayers: List<List<PdfTextLine>>,
        quality: PdfImageQuality,
        onProgress: (Int, Int) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, image ->
                ensureActive()
                addPage(document, index, image, size, margin, quality, textLayers.getOrNull(index).orEmpty())
                onProgress(index + 1, pages.size)
            }
            ensureActive()
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private fun addPage(
        document: PdfDocument,
        index: Int,
        image: PdfPageImage,
        size: PdfPageSize,
        margin: Float,
        quality: PdfImageQuality,
        lines: List<PdfTextLine>,
    ) {
        val bitmap = decodeUpright(image, index, quality.maxSidePx)
        try {
            val place = placeImage(bitmap.width, bitmap.height, size, margin)
            val info = PdfDocument.PageInfo.Builder(place.pageWidth.toInt(), place.pageHeight.toInt(), index + 1).create()
            val page = document.startPage(info)
            val rect = RectF(place.left, place.top, place.left + place.width, place.top + place.height)
            page.canvas.drawBitmap(bitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            drawTextLayer(page.canvas, lines, place.left, place.top, place.width, place.height)
            document.finishPage(page)
        } finally {
            bitmap.recycle()
        }
    }

    /** Text you can't see over the picture, sized to each OCR line, so find and copy reach it. */
    private fun drawTextLayer(
        canvas: Canvas,
        lines: List<PdfTextLine>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        // Alpha 1 of 255: unseen, and not skipped as a no-op, as a fully transparent draw could be.
        val paint = Paint().apply { color = Color.argb(1, 0, 0, 0) }
        for (line in lines) {
            if (line.text.isBlank()) continue
            paint.textSize = ((line.bottom - line.top) * height).coerceAtLeast(1f)
            paint.textScaleX = 1f
            val measured = paint.measureText(line.text)
            val target = (line.right - line.left) * width
            if (measured > 0f && target > 0f) paint.textScaleX = target / measured
            canvas.drawText(line.text, left + line.left * width, top + line.bottom * height, paint)
        }
    }

    /** Decodes within [maxSide], then applies EXIF orientation and the page's own rotation. */
    private fun decodeUpright(image: PdfPageImage, index: Int, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(image).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable(index)
        val options = BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            // Wide-gamut photos would otherwise keep their own colours, which a PDF's DeviceRGB misreads.
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = open(image).use { BitmapFactory.decodeStream(it, null, options) } ?: throw unreadable(index)
        return turned(shrunk(decoded, maxSide), ImageTurn.ofExif(exifOrientation(image)).plus(image.rotationDegrees))
    }

    private fun unreadable(index: Int) = DocumentException(DocumentError.Malformed("Picture ${index + 1} can't be read."))

    private fun open(image: PdfPageImage): InputStream {
        image.encoded?.let { return ByteArrayInputStream(it) }
        return app.contentResolver.openInputStream(Uri.parse(image.uri))
            ?: throw IOException("The app that provides this picture did not return it.")
    }

    private fun exifOrientation(image: PdfPageImage): Int = try {
        val encoded = image.encoded
        val exif = if (encoded != null) ExifInterface(ByteArrayInputStream(encoded)) else exifFromUri(image)
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: RuntimeException) {
        // A malformed EXIF block costs the orientation, not the page.
        ExifInterface.ORIENTATION_NORMAL
    }

    /** A seekable descriptor reads more formats' metadata than a stream, but not every provider gives one. */
    private fun exifFromUri(image: PdfPageImage): ExifInterface = try {
        app.contentResolver.openFileDescriptor(Uri.parse(image.uri), "r")?.use { ExifInterface(it.fileDescriptor) }
            ?: throw IOException("No descriptor for this picture.")
    } catch (_: IOException) {
        open(image).use { ExifInterface(it) }
    }

    private fun shrunk(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun turned(bitmap: Bitmap, turn: ImageTurn): Bitmap {
        if (!turn.mirror && turn.degrees == 0) return bitmap
        val matrix = Matrix()
        if (turn.mirror) matrix.postScale(-1f, 1f)
        matrix.postRotate(turn.degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /** JPEG has no transparency; without this, transparent pixels would turn black. */
    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return flat
    }

    private suspend fun layoutOf(text: String, style: TextPdfStyle): LaidOutText {
        if (text.length > MAX_TEXT_PDF_CHARS) {
            throw DocumentException(DocumentError.LimitExceeded("This text is too long to lay out."))
        }
        return layoutLock.withLock {
            lastLayout?.takeIf { it.text == text && it.style == style }
                ?: LaidOutText(text, style).also { lastLayout = it }
        }
    }

    /** Runs [block] off the main thread, turning platform failures into typed errors. */
    private suspend fun <T> translatingErrors(block: suspend CoroutineScope.() -> T): T = try {
        withContext(Dispatchers.IO, block)
    } catch (e: IOException) {
        throw DocumentException(DocumentError.FileAccess(e.message ?: "The file could not be accessed."))
    } catch (_: SecurityException) {
        throw DocumentException(DocumentError.FileAccess("EzFiles no longer has access to this file."))
    } catch (_: OutOfMemoryError) {
        throw DocumentException(DocumentError.LimitExceeded("This is too large to process on this device."))
    }

    /** As [translatingErrors], and on failure or cancel removes the save dialog's new file if nothing reached it. */
    private suspend fun <T> creating(target: String, block: suspend CoroutineScope.() -> T): T = try {
        translatingErrors(block)
    } catch (e: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { discardIfEmpty(Uri.parse(target)) }
        if (e is CancellationException || e is DocumentException) throw e
        throw DocumentException(DocumentError.Internal(e.message ?: "The file could not be created."))
    }

    /** Writes to a private file first, so the target stays untouched until the output is complete. */
    private inline fun <T> throughTemp(target: String, suffix: String, write: (OutputStream) -> T): T {
        val temp = File.createTempFile("create", suffix, app.cacheDir)
        try {
            val result = temp.outputStream().buffered().use { write(it) }
            val stream = app.contentResolver.openOutputStream(Uri.parse(target), "wt")
                ?: throw IOException("The app that provides this file did not accept it.")
            stream.use { output -> temp.inputStream().use { it.copyTo(output) } }
            return result
        } finally {
            temp.delete()
        }
    }

    /** Only an empty file is ever removed, so choosing to overwrite a real one can't lose it. */
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

/**
 * Text flowed for one style. The PDF, the PNG and the preview all draw through
 * [draw], at different canvas scales, so they can't disagree. Units are points.
 */
private class LaidOutText(val text: String, val style: TextPdfStyle) {
    val box: TextPageBox = style.contentBox()
    private val template = style.template
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = this@LaidOutText.style.textSizePt.coerceIn(TextPdfStyle.MIN_TEXT_SIZE, TextPdfStyle.MAX_TEXT_SIZE)
        color = this@LaidOutText.style.textArgb
        isSubpixelText = true
        typeface = Typeface.create(
            when (this@LaidOutText.style.font) {
                TextPdfFont.Serif -> Typeface.SERIF
                TextPdfFont.Sans -> Typeface.SANS_SERIF
                TextPdfFont.Mono -> Typeface.MONOSPACE
            },
            if (template.boldBody) Typeface.BOLD else Typeface.NORMAL,
        )
    }
    private val alignment = when (style.alignment) {
        TextPdfAlignment.Start -> Layout.Alignment.ALIGN_NORMAL
        TextPdfAlignment.Center -> Layout.Alignment.ALIGN_CENTER
        TextPdfAlignment.End -> Layout.Alignment.ALIGN_OPPOSITE
    }
    private val formatted = TextPdfFormatter.format(text, template)
    private val styled = SpannableString(formatted.text).apply {
        for (run in formatted.runs) {
            val scale = if (run.kind == TextPdfRunKind.Title) 1.7f else 1.2f
            setSpan(RelativeSizeSpan(scale), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(style.accentArgb), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    private val layout = lines(0, styled.length, box.width, null)

    /** Which lines hold the end of a heading, for the rule under it. */
    private val headingEnds = BooleanArray(layout.lineCount)
    val pages: List<TextPageSlice>

    init {
        val keep = BooleanArray(layout.lineCount)
        for (run in formatted.runs) {
            val lastLine = layout.getLineForOffset(run.end - 1)
            for (line in layout.getLineForOffset(run.start)..lastLine) keep[line] = true
            headingEnds[lastLine] = true
        }
        val boxes = List(layout.lineCount) { line ->
            val end = layout.getLineEnd(line)
            TextLineBox(
                height = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat(),
                endsParagraph = end > 0 && styled[end - 1] == '\n',
                blank = isBlankLine(line),
                keepWithNext = keep[line],
            )
        }
        pages = TextPaginator.paginate(boxes, box.height.toFloat(), paint.textSize * template.paragraphGap)
    }

    private fun lines(start: Int, end: Int, width: Int, direction: Int?): StaticLayout {
        val builder = StaticLayout.Builder.obtain(styled, start, end, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, template.lineSpacing)
            // A hyphen added by the line breaker isn't in the text, so drawLine would lose it.
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        if (direction != null) {
            builder.setTextDirection(if (direction == Layout.DIR_RIGHT_TO_LEFT) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
        }
        return builder.build()
    }

    private fun isBlankLine(line: Int): Boolean {
        for (index in layout.getLineStart(line) until layout.getLineEnd(line)) {
            if (!styled[index].isWhitespace()) return false
        }
        return true
    }

    fun render(pageIndex: Int, widthPx: Int): Bitmap {
        val scale = widthPx.toFloat() / box.pageWidth
        val bitmap = Bitmap.createBitmap(widthPx, ceil(box.pageHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas, pageIndex)
        return bitmap
    }

    fun draw(canvas: Canvas, pageIndex: Int) {
        val page = pages[pageIndex]
        val textTop = box.top + TextPaginator.verticalOffset(page.usedHeight, box.height, template.centerVertically)
        val shapes = Paint(Paint.ANTI_ALIAS_FLAG)
        shapes.color = style.backgroundArgb
        canvas.drawRect(0f, 0f, box.pageWidth.toFloat(), box.pageHeight.toFloat(), shapes)
        drawDecoration(canvas, shapes, page, textTop)
        for (line in page.first until page.end) {
            val top = textTop + page.tops[line - page.first]
            if (template.ruleUnderHeadings && headingEnds[line]) {
                val bottom = top + layout.getLineBottom(line) - layout.getLineTop(line)
                shapes.color = style.accentArgb
                canvas.drawRect(box.left.toFloat(), bottom - 1f, (box.left + box.width).toFloat(), bottom, shapes)
            }
            if (!isBlankLine(line)) drawLine(canvas, line, top)
        }
    }

    /**
     * Each line is drawn on its own. The usual way, clipping one big layout to
     * the page, also sends neighbouring lines to the canvas, because Layout picks
     * its lines from rounded-out clip bounds. In a PDF those lines would be
     * invisible yet still found by search and copied.
     */
    private fun drawLine(canvas: Canvas, line: Int, top: Float) {
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line)
        if (end > start && styled[end - 1] == '\n') end--
        val direction = layout.getParagraphDirection(line)
        var single = lines(start, end, box.width, direction)
        var nudge = 0f
        if (single.lineCount > 1) {
            // Shaped alone, the line came out a hair wider. Allow for that and keep it where it belongs.
            single = lines(start, end, box.width + WRAP_SLACK, direction)
            nudge = when {
                alignment == Layout.Alignment.ALIGN_CENTER -> -WRAP_SLACK / 2f
                (alignment == Layout.Alignment.ALIGN_OPPOSITE) == (direction == Layout.DIR_LEFT_TO_RIGHT) -> -WRAP_SLACK.toFloat()
                else -> 0f
            }
        }
        val baseline = top + layout.getLineBaseline(line) - layout.getLineTop(line)
        canvas.save()
        canvas.translate(box.left + nudge, baseline - single.getLineBaseline(0))
        single.draw(canvas)
        canvas.restore()
    }

    private fun drawDecoration(canvas: Canvas, shapes: Paint, page: TextPageSlice, textTop: Float) {
        val pageWidth = box.pageWidth.toFloat()
        val pageHeight = box.pageHeight.toFloat()
        val inset = style.marginPt.coerceAtLeast(0f) / 2
        shapes.color = style.accentArgb
        when (template.decoration) {
            TextPdfDecoration.None -> Unit
            TextPdfDecoration.AccentBars -> {
                val barLeft = (pageWidth - ACCENT_BAR_WIDTH) / 2
                val above = textTop - 20f
                val below = textTop + page.usedHeight + 16f
                canvas.drawRect(barLeft, above, barLeft + ACCENT_BAR_WIDTH, above + 4f, shapes)
                canvas.drawRect(barLeft, below, barLeft + ACCENT_BAR_WIDTH, below + 4f, shapes)
            }
            TextPdfDecoration.MarginRule -> {
                val x = box.left - TextPdfStyle.MARGIN_RULE_INSET.toFloat()
                canvas.drawRect(x, 0f, x + 2f, pageHeight, shapes)
            }
            TextPdfDecoration.RuledLines -> drawRuledLines(canvas, shapes, page, textTop)
            TextPdfDecoration.TopBand -> canvas.drawRect(0f, 0f, pageWidth, TextPdfStyle.TOP_BAND_HEIGHT.toFloat(), shapes)
            TextPdfDecoration.Frame -> {
                shapes.style = Paint.Style.STROKE
                shapes.strokeWidth = 3f
                canvas.drawRect(inset, inset, pageWidth - inset, pageHeight - inset, shapes)
                shapes.style = Paint.Style.FILL
            }
            TextPdfDecoration.Panel -> {
                shapes.color = 0xB3FFFFFF.toInt()
                canvas.drawRoundRect(RectF(inset, inset, pageWidth - inset, pageHeight - inset), 24f, 24f, shapes)
            }
        }
    }

    /** A rule under each line, then on down the page at the body text's spacing, like a notebook. */
    private fun drawRuledLines(canvas: Canvas, shapes: Paint, page: TextPageSlice, textTop: Float) {
        shapes.color = (style.accentArgb and 0x00FFFFFF) or 0x59000000
        val left = box.left.toFloat()
        val right = left + box.width
        var y = textTop
        for (line in page.first until page.end) {
            y = textTop + page.tops[line - page.first] + layout.getLineBottom(line) - layout.getLineTop(line)
            canvas.drawRect(left, y - 1f, right, y, shapes)
        }
        val spacing = paint.getFontMetrics(null) * template.lineSpacing
        y += spacing
        while (y <= box.top + box.height) {
            canvas.drawRect(left, y - 1f, right, y, shapes)
            y += spacing
        }
    }

    private companion object {
        const val WRAP_SLACK = 8
        const val ACCENT_BAR_WIDTH = 64f
    }
}

/**
 * The lowest JPEG quality, from [MIN_JPEG_QUALITY] up to the preset's, at
 * which a full-size crop from the middle of the page still scores the preset's
 * SSIM against the original: a binary search of about five tries. A crop, not
 * a reduced copy, so compression is judged at the size it will be seen.
 */
internal fun lowestGoodQuality(bitmap: Bitmap, quality: PdfImageQuality): Int {
    val most = quality.jpegQuality
    if (most <= MIN_JPEG_QUALITY) return most
    val cw = minOf(bitmap.width, QUALITY_PROBE_SIDE)
    val ch = minOf(bitmap.height, QUALITY_PROBE_SIDE)
    if (cw < 16 || ch < 16) return most
    val crop = Bitmap.createBitmap(bitmap, (bitmap.width - cw) / 2, (bitmap.height - ch) / 2, cw, ch)
    return try {
        val original = lumaOfBitmap(crop)
        var low = MIN_JPEG_QUALITY
        var high = most
        while (low < high) {
            val mid = (low + high) / 2
            val bytes = ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, mid, it) }.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return most
            val score = try {
                RustImaging.similarity(original, lumaOfBitmap(decoded), cw, ch)
            } finally {
                decoded.recycle()
            }
            if (score != null && score >= quality.similarity) high = mid else low = mid + 1
        }
        high
    } catch (_: OutOfMemoryError) {
        most
    } finally {
        if (crop !== bitmap) crop.recycle()
    }
}

private fun lumaOfBitmap(b: Bitmap): ByteArray {
    val pixels = IntArray(b.width * b.height)
    b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
    return ByteArray(pixels.size) { lumaOf(pixels[it]).toByte() }
}

/** Below this, JPEG blocks show on any page. */
private const val MIN_JPEG_QUALITY = 40
private const val QUALITY_PROBE_SIDE = 768
