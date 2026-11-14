package com.ezfiles.reader.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.ezfiles.reader.domain.ImagePlacement
import com.ezfiles.reader.domain.JpegPdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visual print copies: rasterize one bounded page, then stream and discard its JPEG. */
internal class AndroidDocumentPrintAdapter(
    private val source: File,
    private val pageCount: Int,
    private val name: String,
    private val cleanup: () -> Unit,
) : PrintDocumentAdapter() {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)
    private var writeJob: Job? = null
    private var attributes: PrintAttributes? = null

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        writeJob?.cancel()
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = PrintAttributes.Builder()
            .setMediaSize(newAttributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4)
            .setResolution(newAttributes.resolution ?: PrintAttributes.Resolution("delivery", "Document", 300, 300))
            .setMinMargins(newAttributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(if (newAttributes.colorMode == 0) PrintAttributes.COLOR_MODE_COLOR else newAttributes.colorMode)
            .build()
        callback.onLayoutFinished(PrintDocumentInfo.Builder(name.take(120))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build(), oldAttributes != newAttributes)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        val settings = attributes
        if (settings == null) {
            callback.onWriteFailed("Choose print settings first.")
            return
        }
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }
        val ranges = selectedPrintRanges(pageCount, pages.map { it.start..it.end })
        if (ranges.isEmpty()) {
            callback.onWriteFinished(emptyArray())
            return
        }
        if (ranges.sumOf { it.last.toLong() - it.first + 1L } > 500L) {
            callback.onWriteFailed("Print up to 500 pages at once. Choose a smaller page range.")
            return
        }
        val previous = writeJob
        previous?.cancel()
        writeJob = scope.launch {
            try {
                previous?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        // Own a duplicate only: the framework owns destination and closes its descriptor.
                        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(destination.fileDescriptor)).buffered().use { output ->
                            var written = 0L
                            val writer = JpegPdfWriter { bytes, length ->
                                coroutineContext.ensureActive()
                                written += length
                                if (written > 128L * 1024 * 1024) throw java.io.IOException("Print output exceeds 128 MiB. Choose fewer pages.")
                                output.write(bytes, 0, length)
                            }
                            val media = settings.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
                            val margins = settings.minMargins ?: PrintAttributes.Margins.NO_MARGINS
                            val pageWidth = media.widthMils * 72f / 1000f
                            val pageHeight = media.heightMils * 72f / 1000f
                            val leftMargin = margins.leftMils * 72f / 1000f
                            val topMargin = margins.topMils * 72f / 1000f
                            val contentWidth = (pageWidth - leftMargin - margins.rightMils * 72f / 1000f).coerceAtLeast(1f)
                            val contentHeight = (pageHeight - topMargin - margins.bottomMils * 72f / 1000f).coerceAtLeast(1f)
                            for (range in ranges) for (index in range) {
                                coroutineContext.ensureActive()
                                renderer.openPage(index).use { original ->
                                    val scale = minOf(300f / 72f, sqrt(4_000_000f / (original.width.toFloat() * original.height)), 4096f / maxOf(original.width, original.height))
                                    val bitmap = Bitmap.createBitmap(maxOf(1, (original.width * scale).roundToInt()), maxOf(1, (original.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                                    try {
                                        bitmap.eraseColor(Color.WHITE)
                                        original.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                        coroutineContext.ensureActive()
                                        val fit = minOf(contentWidth / bitmap.width, contentHeight / bitmap.height)
                                        val width = bitmap.width * fit
                                        val height = bitmap.height * fit
                                        val jpeg = ByteArrayOutputStream()
                                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpeg)) throw java.io.IOException("The page could not be encoded.")
                                        writer.addPage(ImagePlacement(pageWidth, pageHeight,
                                            leftMargin + (contentWidth - width) / 2f,
                                            topMargin + (contentHeight - height) / 2f, width, height), bitmap.width, bitmap.height, jpeg.toByteArray())
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                            coroutineContext.ensureActive()
                            writer.finish()
                            coroutineContext.ensureActive()
                        }
                    }
                    }
                }
                callback.onWriteFinished(ranges.map { PageRange(it.first, it.last) }.toTypedArray())
            } catch (_: CancellationException) {
                callback.onWriteCancelled()
            } catch (error: java.io.IOException) {
                callback.onWriteFailed(error.message ?: "The print output could not be written.")
            } catch (_: Exception) {
                callback.onWriteFailed("This document could not be printed. Check device storage and try again.")
            } finally {
                cancellationSignal.setOnCancelListener(null)
            }
        }.also { job -> cancellationSignal.setOnCancelListener { job.cancel() } }
    }

    override fun onFinish() {
        val running = supervisor.children.toList()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            running.forEach { it.join() }
            cleanup()
        }
    }
}
