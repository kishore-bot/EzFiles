package com.ezfiles.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePdfTest {
    @Test
    fun everyExifOrientationMapsToATurn() {
        assertEquals(ImageTurn(false, 0), ImageTurn.ofExif(1))
        assertEquals(ImageTurn(true, 0), ImageTurn.ofExif(2))
        assertEquals(ImageTurn(false, 180), ImageTurn.ofExif(3))
        assertEquals(ImageTurn(true, 180), ImageTurn.ofExif(4))
        assertEquals(ImageTurn(true, 270), ImageTurn.ofExif(5))
        assertEquals(ImageTurn(false, 90), ImageTurn.ofExif(6))
        assertEquals(ImageTurn(true, 90), ImageTurn.ofExif(7))
        assertEquals(ImageTurn(false, 270), ImageTurn.ofExif(8))
    }

    @Test
    fun unknownOrientationCountsAsUpright() {
        assertEquals(ImageTurn(false, 0), ImageTurn.ofExif(0))
        assertEquals(ImageTurn(false, 0), ImageTurn.ofExif(99))
    }

    @Test
    fun theUsersRotationAddsToExif() {
        assertEquals(ImageTurn(false, 0), ImageTurn.ofExif(8).plus(90))
        assertEquals(ImageTurn(true, 180), ImageTurn.ofExif(7).plus(90))
    }

    @Test
    fun rotationNormalizes() {
        assertEquals(270, normalizeRotation(-90))
        assertEquals(0, normalizeRotation(720))
        assertEquals(90, normalizeRotation(450))
    }

    @Test
    fun sampleSizeKeepsAtLeastTheWantedSide() {
        assertEquals(1, imageSampleSize(2000, 1000, 2600))
        assertEquals(1, imageSampleSize(5199, 1000, 2600))
        assertEquals(2, imageSampleSize(5200, 1000, 2600))
        assertEquals(4, imageSampleSize(16320, 12240, 2600))
    }

    @Test
    fun aDecompressionBombStillDecodesSmall() {
        val sample = imageSampleSize(60_000, 60_000, 2600)
        assertTrue(60_000 / sample < 2600 * 2)
    }

    @Test
    fun aPictureIsCentredInsideTheMargin() {
        val place = placeImage(1000, 1000, PdfPageSize.A4, 36f)
        assertEquals(523f, place.width, 0.01f)
        assertEquals(523f, place.height, 0.01f)
        assertEquals(36f, place.left, 0.01f)
        assertEquals((842f - 523f) / 2, place.top, 0.01f)
    }

    @Test
    fun aPictureKeepsItsShape() {
        val place = placeImage(3000, 1000, PdfPageSize.Letter, 0f)
        assertEquals(3f, place.width / place.height, 0.001f)
        assertEquals(612f, place.width, 0.01f)
    }

    @Test
    fun fitPictureWrapsThePageAroundThePicture() {
        val place = placeImage(1500, 750, PdfPageSize.FitImage, 10f)
        assertEquals(740f, place.pageWidth)
        assertEquals(380f, place.pageHeight)
        assertEquals(10f, place.left, 0.01f)
        assertEquals(10f, place.top, 0.01f)
        assertEquals(720f, place.width, 0.01f)
    }

    @Test
    fun aOnePixelStripStillMakesAValidPage() {
        val place = placeImage(1, 1, PdfPageSize.FitImage, 0f)
        assertTrue(place.pageWidth >= 1f && place.pageHeight >= 1f)
        assertTrue(place.width > 0f && place.height > 0f)
    }

    @Test
    fun marginsLargerThanThePageLeaveAVisiblePicture() {
        val place = placeImage(100, 100, PdfPageSize.A4, 5000f)
        assertTrue(place.width > 0f && place.height > 0f)
    }

    @Test
    fun numbersUseTwoDecimalsAndNoExponent() {
        assertEquals("595.00", pdfNumber(595f))
        assertEquals("0.00", pdfNumber(0.00001f))
        assertEquals("-0.50", pdfNumber(-0.5f))
        assertEquals("-1.50", pdfNumber(-1.5f))
        assertEquals("12.25", pdfNumber(12.25f))
    }

    private val fakeJpeg = byteArrayOf(-1, -40, -1, 0, 1, 2, 3, 10, 13, -1, -39)

    private fun writePdf(pageCount: Int): ByteArray {
        val out = mutableListOf<Byte>()
        val writer = JpegPdfWriter { bytes, length -> for (i in 0 until length) out += bytes[i] }
        repeat(pageCount) { writer.addPage(placeImage(40, 20, PdfPageSize.A4, 10f), 40, 20, fakeJpeg) }
        writer.finish()
        return out.toByteArray()
    }

    /** Latin-1 keeps one char per byte, so string indexes are byte offsets. */
    private fun latin1(bytes: ByteArray) = buildString(bytes.size) { bytes.forEach { append((it.toInt() and 0xFF).toChar()) } }

    @Test
    fun theFileHasTheFramingReadersNeed() {
        val text = latin1(writePdf(2))
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue("/Count 2" in text)
        assertTrue("/Kids [3 0 R 6 0 R]" in text)
    }

    @Test
    fun theCrossReferenceTablePointsAtEveryObject() {
        val text = latin1(writePdf(3))
        val xrefStart = text.substringAfter("startxref\n").substringBefore('\n').toInt()
        assertEquals("xref", text.substring(xrefStart, xrefStart + 4))
        val rows = text.substring(xrefStart).split('\n')
        val count = rows[1].split(' ')[1].toInt()
        assertEquals(3 + 3 * 3, count) // The free entry, catalog and page tree, then three objects per page.
        assertTrue("/Size $count " in text)
        assertTrue(rows[2].startsWith("0000000000 65535 f"))
        for (number in 1 until count) {
            val offset = rows[2 + number].substring(0, 10).toInt()
            assertTrue(text.startsWith("$number 0 obj\n", offset), "object $number at $offset")
        }
    }

    @Test
    fun crossReferenceRowsAreTwentyBytes() {
        val text = latin1(writePdf(1))
        val table = text.substringAfter("xref\n").substringAfter('\n').substringBefore("trailer")
        assertEquals(0, table.length % 20)
    }

    @Test
    fun theJpegIsStoredUntouchedWithItsTrueLength() {
        val text = latin1(writePdf(1))
        assertTrue("/Filter /DCTDecode /Length ${fakeJpeg.size} " in text)
        assertTrue("/Width 40 /Height 20 " in text)
        val stream = text.substringAfter("/DCTDecode").substringAfter("stream\n")
        assertEquals(latin1(fakeJpeg), stream.substring(0, fakeJpeg.size))
        assertTrue(stream.startsWith("\nendstream", fakeJpeg.size))
    }

    @Test
    fun theDrawingMeasuresFromThePageBottom() {
        val place = ImagePlacement(pageWidth = 100f, pageHeight = 200f, left = 10f, top = 30f, width = 80f, height = 50f)
        val out = mutableListOf<Byte>()
        val writer = JpegPdfWriter { bytes, length -> for (i in 0 until length) out += bytes[i] }
        writer.addPage(place, 8, 5, fakeJpeg)
        writer.finish()
        assertTrue("q 80.00 0 0 50.00 10.00 120.00 cm /Im0 Do Q" in latin1(out.toByteArray()))
    }

    @Test
    fun aContentStreamLengthMatchesItsText() {
        val text = latin1(writePdf(1))
        val length = text.substringAfter("/Length ").substringBefore(' ').toInt()
        val content = text.substringAfter("/Length $length >>\nstream\n").substringBefore("\nendstream")
        assertEquals(length, content.length)
    }
}
