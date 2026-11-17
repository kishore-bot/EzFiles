package com.ezfiles.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ShapeGeometry
import com.ezfiles.reader.domain.SlideShape
import com.ezfiles.reader.domain.TextSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream

/**
 * Any document the engine reads, laid out as an A4 PDF for printing. The Rust engine parses the file
 * into blocks (headings, paragraphs, lists, tables, code, pictures, slides); Android's text layout
 * draws them, so every script and emoji the phone shows prints the same way. Slides keep their own
 * canvas, one per page. Returns the page count.
 */
internal class AndroidDocumentPdf(private val session: DocumentSession) {
    private val pdf = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var y = 0f
    private var pages = 0

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.BLACK }
    private val mono = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 8.5f }
    private val rule = Paint().apply { color = 0xFFBDBDBD.toInt(); strokeWidth = 0.6f }
    private val tint = Paint().apply { color = 0xFFF2F2F2.toInt() }

    /** Slides: the current one's background, collected until the next slide starts. */
    private var slide: ContentBlock.Slide? = null
    private val slideShapes = ArrayList<SlideShape>()
    private val table = ArrayList<ContentBlock.TableRow>()

    suspend fun write(out: OutputStream): Int {
        try {
            while (true) {
                val batch = session.nextBatch() ?: break
                for (block in batch.blocks) {
                    currentCoroutineContext().ensureActive()
                    add(block)
                    if (pages > MAX_PAGES) error("too many pages to print")
                }
                if (batch.isLast) break
            }
            flushTable()
            flushSlide()
            page?.let(pdf::finishPage)
            page = null
            pdf.writeTo(out)
            return pages
        } finally {
            pdf.close()
        }
    }

    private suspend fun add(block: ContentBlock) {
        if (block !is ContentBlock.TableRow && block !is ContentBlock.CellStyle && block !is ContentBlock.CellRuns &&
            block !is ContentBlock.Merge) flushTable()
        when (block) {
            is ContentBlock.Slide -> { flushSlide(); slide = block }
            is ContentBlock.Shape -> if (slide != null) slideShapes += block.shape
            is ContentBlock.Heading -> text(block.text, block.spans, size = HEADING_SIZES.getOrElse(block.level - 1) { 12f }, bold = true, before = 8f)
            is ContentBlock.Paragraph -> text(block.text, block.spans)
            is ContentBlock.ListItem -> {
                val marker = block.marker ?: "•"
                text(if (marker.isEmpty()) block.text else "$marker  ${block.text}", block.spans, indent = 14f * (block.depth + 1),
                    spanShift = if (marker.isEmpty()) 0 else marker.length + 2)
            }
            is ContentBlock.Quote -> text(block.text, block.spans, indent = 14f, italic = true, bar = true)
            is ContentBlock.Code -> code(block.text)
            is ContentBlock.Divider -> { space(6f); canvas().drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule); y += 6f }
            is ContentBlock.Section -> if (slide == null) text(block.title, emptyList(), size = 14f, bold = true, before = 12f)
            is ContentBlock.TableRow -> table += block
            is ContentBlock.Image -> image(block)
            is ContentBlock.Entry -> text(block.path, emptyList(), size = 9f)
            else -> {}
        }
    }

    // ---------- Flowing pages ----------

    private fun canvas(): Canvas {
        page?.let { return it.canvas }
        pages++
        val p = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pages).create())
        page = p
        y = MARGIN
        return p.canvas
    }

    private fun newPage() {
        page?.let(pdf::finishPage)
        page = null
        canvas()
    }

    /** Room for [height] points, or a new page. */
    private fun space(height: Float) {
        canvas()
        if (y + height > PAGE_H - MARGIN && y > MARGIN) newPage()
    }

    private fun text(
        value: String, spans: List<TextSpan>, size: Float = body.textSize, bold: Boolean = false, italic: Boolean = false,
        indent: Float = 0f, before: Float = 0f, bar: Boolean = false, spanShift: Int = 0,
    ) {
        if (value.isBlank()) { y += 4f; return }
        val paint = TextPaint(body).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        }
        val width = (PAGE_W - 2 * MARGIN - indent).toInt()
        val layout = StaticLayout.Builder.obtain(styled(value, spans, spanShift), 0, value.length, paint, width)
            .setLineSpacing(0f, 1.15f).build()
        y += before
        // Long paragraphs continue on the next page line by line.
        var line = 0
        while (line < layout.lineCount) {
            space(layout.getLineBottom(line).toFloat() - layout.getLineTop(line))
            val c = canvas()
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && y + (layout.getLineBottom(last + 1) - top) <= PAGE_H - MARGIN) last++
            val height = (layout.getLineBottom(last) - top).toFloat()
            c.save()
            c.translate(MARGIN + indent, y - top)
            c.clipRect(0f, top.toFloat(), width.toFloat(), layout.getLineBottom(last).toFloat())
            layout.draw(c)
            c.restore()
            if (bar) c.drawRect(MARGIN + indent - 8f, y, MARGIN + indent - 6f, y + height, rule)
            y += height
            line = last + 1
        }
        y += 4f
    }

    private fun styled(value: String, spans: List<TextSpan>, shift: Int): CharSequence {
        if (spans.isEmpty()) return value
        val text = SpannableString(value)
        for (span in spans) {
            val start = (span.start + shift).coerceIn(0, value.length)
            val end = (span.end + shift).coerceIn(start, value.length)
            if (start == end) continue
            val style = (if (span.bold) Typeface.BOLD else 0) or (if (span.italic) Typeface.ITALIC else 0)
            if (style != 0) text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (span.underline || span.link != null) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (span.strikethrough) text.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (span.code) text.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.color?.let { text.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return text
    }

    private fun code(value: String) {
        y += 2f
        for (raw in value.trimEnd('\n').split('\n')) {
            val layout = StaticLayout.Builder.obtain(raw.ifEmpty { " " }, 0, raw.ifEmpty { " " }.length, mono, (PAGE_W - 2 * MARGIN - 8).toInt()).build()
            space(layout.height.toFloat())
            val c = canvas()
            c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + layout.height, tint)
            c.save()
            c.translate(MARGIN + 4f, y)
            layout.draw(c)
            c.restore()
            y += layout.height
        }
        y += 6f
    }

    private fun flushTable() {
        if (table.isEmpty()) return
        val columns = table.maxOf { it.cells.size }.coerceIn(1, MAX_COLUMNS)
        val cellWidth = (PAGE_W - 2 * MARGIN) / columns
        val paint = TextPaint(body).apply { textSize = if (columns > 5) 8f else 9.5f }
        val header = TextPaint(paint).apply { typeface = Typeface.DEFAULT_BOLD }
        y += 4f
        for (row in table) {
            val layouts = (0 until columns).map { i ->
                val value = row.cells.getOrNull(i).orEmpty()
                StaticLayout.Builder.obtain(value, 0, value.length, if (row.header) header else paint, (cellWidth - 6).toInt().coerceAtLeast(10))
                    .setMaxLines(6).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            }
            val height = layouts.maxOf { it.height } + 6f
            space(height)
            val c = canvas()
            if (row.header) c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, tint)
            layouts.forEachIndexed { i, layout ->
                c.save()
                c.translate(MARGIN + i * cellWidth + 3f, y + 3f)
                layout.draw(c)
                c.restore()
            }
            c.drawLine(MARGIN, y + height, PAGE_W - MARGIN, y + height, rule)
            y += height
        }
        table.clear()
        y += 6f
    }

    private suspend fun image(block: ContentBlock.Image) {
        val bitmap = block.path?.let { session.resource(it) }?.let(::decode) ?: run {
            block.alt?.takeIf(String::isNotBlank)?.let { text("[$it]", emptyList(), italic = true) }
            return
        }
        try {
            val maxW = PAGE_W - 2 * MARGIN
            val maxH = PAGE_H - 2 * MARGIN
            val scale = minOf(maxW / bitmap.width, maxH / bitmap.height, (block.width ?: maxW) / bitmap.width, 1f)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            space(h)
            canvas().drawBitmap(bitmap, null, RectF(MARGIN, y, MARGIN + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 6f
        } finally {
            bitmap.recycle()
        }
    }

    // ---------- Slides: one page each, at the slide's own size ----------

    private suspend fun flushSlide() {
        val current = slide ?: return
        slide = null
        page?.let(pdf::finishPage)
        page = null
        pages++
        // Fit the slide on a landscape A4 sheet, keeping its proportions.
        val scale = minOf(PAGE_H / current.width, PAGE_W / current.height)
        val p = pdf.startPage(PdfDocument.PageInfo.Builder((current.width * scale).toInt(), (current.height * scale).toInt(), pages).create())
        val c = p.canvas
        c.scale(scale, scale)
        c.drawColor(current.background ?: Color.WHITE)
        current.backgroundImage?.let { session.resource(it) }?.let(::decode)?.let { bitmap ->
            c.drawBitmap(bitmap, null, RectF(0f, 0f, current.width, current.height), Paint(Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
        }
        for (shape in slideShapes) drawShape(c, shape)
        slideShapes.clear()
        pdf.finishPage(p)
    }

    private suspend fun drawShape(c: Canvas, shape: SlideShape) {
        val box = RectF(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
        c.save()
        if (shape.rotation != 0f) c.rotate(shape.rotation, box.centerX(), box.centerY())
        shape.fill?.let { fill -> c.drawShapeOf(shape.geometry, box, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }) }
        shape.line?.let { line ->
            c.drawShapeOf(shape.geometry, box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = line; style = Paint.Style.STROKE; strokeWidth = shape.lineWidth.coerceAtLeast(0.5f)
            })
        }
        shape.image?.let { session.resource(it) }?.let(::decode)?.let { bitmap ->
            c.drawBitmap(bitmap, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
        }
        val inset = shape.insets
        val width = (shape.width - inset.getOrElse(0) { 7f } - inset.getOrElse(2) { 7f }).toInt().coerceAtLeast(10)
        var ty = shape.y + inset.getOrElse(1) { 4f }
        val layouts = shape.paragraphs.map { paragraph ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = paragraph.size
                color = paragraph.color ?: Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT,
                    (if (paragraph.bold) Typeface.BOLD else 0) or (if (paragraph.italic) Typeface.ITALIC else 0))
            }
            val value = (paragraph.bullet?.let { "$it " } ?: "") + paragraph.text
            val align = when (paragraph.align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            StaticLayout.Builder.obtain(value, 0, value.length, paint, (width - paragraph.level * 18).coerceAtLeast(10))
                .setAlignment(align).build() to paragraph.level * 18f
        }
        val total = layouts.sumOf { it.first.height }
        if (shape.anchor == 1) ty = shape.y + (shape.height - total) / 2
        if (shape.anchor == 2) ty = shape.y + shape.height - total - inset.getOrElse(3) { 4f }
        for ((layout, indent) in layouts) {
            c.save()
            c.translate(shape.x + inset.getOrElse(0) { 7f } + indent, ty)
            layout.draw(c)
            c.restore()
            ty += layout.height
        }
        if (shape.table.isNotEmpty()) {
            val rows = shape.table.size
            val cols = shape.table.maxOf { it.size }.coerceAtLeast(1)
            val cw = shape.width / cols
            val rh = shape.height / rows
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (rh * 0.4f).coerceIn(6f, 14f) }
            shape.table.forEachIndexed { r, row ->
                row.forEachIndexed { col, cell ->
                    c.drawRect(shape.x + col * cw, shape.y + r * rh, shape.x + (col + 1) * cw, shape.y + (r + 1) * rh,
                        Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY })
                    c.drawText(cell.take(40), shape.x + col * cw + 3f, shape.y + r * rh + rh * 0.65f, paint)
                }
            }
        }
        c.restore()
    }

    private fun Canvas.drawShapeOf(geometry: ShapeGeometry, box: RectF, paint: Paint) = when (geometry) {
        ShapeGeometry.Ellipse -> drawOval(box, paint)
        ShapeGeometry.RoundRect -> drawRoundRect(box, 12f, 12f, paint)
        ShapeGeometry.Line -> drawLine(box.left, box.top, box.right, box.bottom, paint)
        ShapeGeometry.Rect -> drawRect(box, paint)
    }

    /** Pictures are drawn no larger than needed for a print page. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_SIDE || bounds.outHeight / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 48f
        const val MAX_COLUMNS = 12
        const val MAX_PAGES = 2_000
        const val MAX_IMAGE_SIDE = 1_600
        val HEADING_SIZES = listOf(20f, 16f, 13.5f, 12f)
    }
}
