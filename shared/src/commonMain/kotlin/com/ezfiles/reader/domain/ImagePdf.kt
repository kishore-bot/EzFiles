package com.ezfiles.reader.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Fit-picture pages are laid out at this many points per pixel, which is 150 dpi. */
private const val POINTS_PER_PIXEL = 72f / 150f

/** How to turn a stored picture upright: mirror left to right, then turn clockwise. */
data class ImageTurn(val mirror: Boolean, val degrees: Int) {
    /** The same picture with a further clockwise turn. */
    fun plus(extraDegrees: Int) = ImageTurn(mirror, normalizeRotation(degrees + extraDegrees))

    companion object {
        /** From the EXIF orientation tag (1..8). Anything else counts as already upright. */
        fun ofExif(orientation: Int): ImageTurn = when (orientation) {
            2 -> ImageTurn(true, 0)
            3 -> ImageTurn(false, 180)
            4 -> ImageTurn(true, 180)
            5 -> ImageTurn(true, 270)
            6 -> ImageTurn(false, 90)
            7 -> ImageTurn(true, 90)
            8 -> ImageTurn(false, 270)
            else -> ImageTurn(false, 0)
        }
    }
}

fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

/** The largest power of two that keeps the decoded picture at least [maxSide] long, and so bounds its memory. */
fun imageSampleSize(width: Int, height: Int, maxSide: Int): Int {
    val floor = maxSide.coerceAtLeast(1)
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= floor) sample *= 2
    return sample
}

/** Where a picture goes on its page, in points from the page's top left. */
data class ImagePlacement(
    val pageWidth: Float,
    val pageHeight: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** Centres a picture inside [marginPt], keeping its shape. Never produces an empty page or picture. */
fun placeImage(pixelWidth: Int, pixelHeight: Int, size: PdfPageSize, marginPt: Float): ImagePlacement {
    val pixelsWide = pixelWidth.coerceAtLeast(1).toFloat()
    val pixelsHigh = pixelHeight.coerceAtLeast(1).toFloat()
    val margin = marginPt.coerceAtLeast(0f)
    val pageWidth: Float
    val pageHeight: Float
    if (size == PdfPageSize.FitImage) {
        pageWidth = (pixelsWide * POINTS_PER_PIXEL + margin * 2).roundToInt().coerceAtLeast(1).toFloat()
        pageHeight = (pixelsHigh * POINTS_PER_PIXEL + margin * 2).roundToInt().coerceAtLeast(1).toFloat()
    } else {
        pageWidth = size.widthPt
        pageHeight = size.heightPt
    }
    val scale = minOf(
        (pageWidth - margin * 2).coerceAtLeast(1f) / pixelsWide,
        (pageHeight - margin * 2).coerceAtLeast(1f) / pixelsHigh,
    )
    val width = pixelsWide * scale
    val height = pixelsHigh * scale
    return ImagePlacement(pageWidth, pageHeight, (pageWidth - width) / 2, (pageHeight - height) / 2, width, height)
}

/** Receives a PDF as it is written. */
fun interface PdfSink {
    fun write(bytes: ByteArray, length: Int)
}

/**
 * Writes a PDF whose pages each hold one JPEG, as it goes. The platform's
 * PdfDocument doesn't suit this job: it keeps every page's pixels until the end
 * and stores them losslessly, so memory grows with the page count and a quality
 * setting could only change the resolution. Here each JPEG is passed through
 * untouched (DCTDecode) and forgotten. Call [finish] once, after the last page.
 */
class JpegPdfWriter(private val sink: PdfSink) {
    private var position = 0L

    /** Index is the object number. 0 is unused and 2, the page tree, is written last. */
    private val offsets = mutableListOf(0L, 0L, 0L)
    private val pageObjects = mutableListOf<Int>()

    init {
        // The version, then a comment of non-ASCII bytes that marks the file as binary.
        emit(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x25, -30, -29, -49, -45, 0x0A))
        offsets[1] = position
        emit("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    }

    /** [jpeg] must be RGB, which is what the platform's encoders produce. */
    fun addPage(place: ImagePlacement, pixelWidth: Int, pixelHeight: Int, jpeg: ByteArray) {
        val page = offsets.size
        val contents = page + 1
        val image = page + 2
        pageObjects += page
        offsets += position
        emit(
            "$page 0 obj\n<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 ${pdfNumber(place.pageWidth)} ${pdfNumber(place.pageHeight)}] " +
                "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $contents 0 R >>\nendobj\n",
        )
        // PDF's y axis runs upwards from the bottom of the page.
        val bottom = place.pageHeight - place.top - place.height
        val drawing = "q ${pdfNumber(place.width)} 0 0 ${pdfNumber(place.height)} " +
            "${pdfNumber(place.left)} ${pdfNumber(bottom)} cm /Im0 Do Q"
        offsets += position
        emit("$contents 0 obj\n<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")
        offsets += position
        emit(
            "$image 0 obj\n<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        emit(jpeg)
        emit("\nendstream\nendobj\n")
    }

    fun finish() {
        offsets[2] = position
        val kids = pageObjects.joinToString(" ") { "$it 0 R" }
        emit("2 0 obj\n<< /Type /Pages /Count ${pageObjects.size} /Kids [$kids] >>\nendobj\n")
        val xref = position
        val table = StringBuilder("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (index in 1 until offsets.size) {
            table.append(offsets[index].toString().padStart(10, '0')).append(" 00000 n \n")
        }
        table.append("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        emit(table.toString())
    }

    private fun emit(text: String) = emit(text.encodeToByteArray())

    private fun emit(bytes: ByteArray) {
        sink.write(bytes, bytes.size)
        position += bytes.size
    }
}

/** Two decimals and never an exponent, which PDF numbers don't allow. */
internal fun pdfNumber(value: Float): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (if (hundredths < 0) -hundredths else hundredths) % 100
    val sign = if (hundredths < 0 && whole == 0L) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(2, '0')}"
}
