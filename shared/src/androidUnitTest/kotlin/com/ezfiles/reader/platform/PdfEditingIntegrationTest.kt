package com.ezfiles.reader.platform

import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.pdfAppendPath
import com.ezfiles.engine.ffi.pdfInspectPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin model through the generated bindings into the real engine on the host JVM, and back. */
class PdfEditingIntegrationTest {
    private fun pdf(trailerExtra: String = ""): ByteArray {
        val bodies = listOf(
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1>>",
            "<</Type /Page /Parent 2 0 R /MediaBox [0 0 600 800]>>",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = bodies.mapIndexed { index, body ->
            out.length.also { out.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xrefAt = out.length
        out.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<</Size ${bodies.size + 1} /Root 1 0 R $trailerExtra>>\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    private fun tempPdf(bytes: ByteArray): File =
        File.createTempFile("ezfiles", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private val annotations = listOf(
        PdfAnnotation.Highlight(0, 0x66FFEB3B, listOf(PdfBox(0.1f, 0.1f, 0.5f, 0.2f))),
        PdfAnnotation.Ink(0, 0xFFE60023.toInt(), 0.005f, listOf(listOf(PdfPoint(0.25f, 0.25f), PdfPoint(0.5f, 0.75f))), PdfInkStyle.Pen),
        PdfAnnotation.Ink(0, 0xFF102040.toInt(), 0.003f, listOf(listOf(PdfPoint(0.5f, 0.5f))), PdfInkStyle.Signature),
        PdfAnnotation.Note(0, 0xFFFFD54F.toInt(), 0.5f, 0.5f, "Résumé"),
    )

    @Test
    fun annotations_survive_a_save_and_come_back_equal() {
        val original = pdf()
        val source = tempPdf(original)
        val target = File.createTempFile("ezfiles", ".pdf").apply { deleteOnExit() }

        val report = pdfAppendPath(source.path, target.path, annotations.map(PdfAnnotation::toNative))

        assertEquals(original.size.toLong(), report.originalLen.toLong())
        val saved = target.readBytes()
        assertTrue(saved.copyOf(original.size).contentEquals(original), "the original is a prefix")
        val info = pdfInspectPath(target.path).toDomain(rendererPages = 1)
        assertNull(info.saveBlock)
        assertEquals(annotations.size, info.existing.size)
        val note = assertIs<PdfAnnotation.Note>(info.existing[3])
        assertEquals("Résumé", note.text)
        assertEquals(annotations[3].color, note.color)
        val ink = assertIs<PdfAnnotation.Ink>(info.existing[1])
        assertEquals(PdfInkStyle.Pen, ink.style)
        assertEquals(annotations[1].color, ink.color)
        assertTrue(kotlin.math.abs(ink.strokes[0][1].y - 0.75f) < 0.001f)
        assertEquals(PdfInkStyle.Signature, assertIs<PdfAnnotation.Ink>(info.existing[2]).style)
    }

    @Test
    fun an_encrypted_file_is_blocked_with_a_reason() {
        val info = pdfInspectPath(tempPdf(pdf("/Encrypt 9 0 R")).path).toDomain(rendererPages = 1)
        assertEquals(PdfSaveBlock.Encrypted, info.saveBlock)
    }

    @Test
    fun disagreeing_page_counts_block_saving() {
        val info = pdfInspectPath(tempPdf(pdf()).path).toDomain(rendererPages = 5)
        assertEquals(PdfSaveBlock.PageMismatch, info.saveBlock)
    }

    @Test
    fun a_damaged_file_fails_with_a_typed_error() {
        val damaged = tempPdf("%PDF-1.7\nnothing".encodeToByteArray())
        assertFailsWith<ReadException.Malformed> { pdfInspectPath(damaged.path) }
    }
}
