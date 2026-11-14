package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativePdfPageEdit
import com.ezfiles.engine.ffi.NativePdfSource
import com.ezfiles.engine.ffi.NativePdfToolListener
import com.ezfiles.engine.ffi.NativePdfToolOp
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.pdfToolInspect
import com.ezfiles.engine.ffi.pdfToolRun
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The PDF tools through the generated bindings into the real engine on the host JVM, callbacks included. */
class PdfToolsIntegrationTest {
    private fun pdf(pages: Int): File {
        val kids = (0 until pages).joinToString(" ") { "${3 + it * 2} 0 R" }
        val bodies = mutableListOf("<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids [$kids] /Count $pages /MediaBox [0 0 600 800]>>")
        repeat(pages) { index ->
            val text = "BT /F1 12 Tf (page $index) Tj ET"
            bodies += "<</Type /Page /Parent 2 0 R /Contents ${4 + index * 2} 0 R>>"
            bodies += "<</Length ${text.length}>>\nstream\n$text\nendstream"
        }
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = bodies.mapIndexed { index, body -> out.length.also { out.append("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xrefAt = out.length
        out.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<</Size ${bodies.size + 1} /Root 1 0 R>>\nstartxref\n$xrefAt\n%%EOF\n")
        return temp().apply { writeBytes(out.toString().encodeToByteArray()) }
    }

    private fun temp() = File.createTempFile("ezfiles-tools", ".pdf").apply { deleteOnExit() }

    private fun source(file: File, password: String? = null) = NativePdfSource(-1, file.path, password)

    private class Listener(val cancel: Boolean = false) : NativePdfToolListener {
        val progress = mutableListOf<Pair<Int, Int>>()
        override fun onProgress(done: UInt, total: UInt) {
            progress += done.toInt() to total.toInt()
        }
        override fun isCancelled() = cancel
    }

    @Test
    fun rebuild_reports_progress_through_the_callback() {
        val input = pdf(3)
        val output = temp()
        val listener = Listener()
        val report = pdfToolRun(listOf(source(input)), NativePdfToolOp.Rebuild(listOf(NativePdfPageEdit(2u, 90), NativePdfPageEdit(0u, 0))), output.path, listener)
        assertEquals(2u, report.pageCount)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), listener.progress)
        assertEquals(2u, pdfToolInspect(source(output)).pageCount)
    }

    @Test
    fun passwords_round_trip_with_typed_errors() {
        val input = pdf(2)
        val locked = temp()
        pdfToolRun(listOf(source(input)), NativePdfToolOp.AddPassword("kotlin-pw"), locked.path, Listener())
        assertFailsWith<ReadException.PasswordRequired> { pdfToolInspect(source(locked)) }
        val opened = pdfToolInspect(source(locked, "kotlin-pw"))
        assertTrue(opened.encrypted)
        assertEquals(2u, opened.pageCount)
        assertFailsWith<ReadException.WrongPassword> { pdfToolInspect(source(locked, "wrong")) }
        val target = temp().apply { delete() }
        assertFailsWith<ReadException.PasswordRequired> { pdfToolRun(listOf(source(locked)), NativePdfToolOp.RemovePassword, target.path, Listener()) }
        assertFalse(target.exists(), "a failed run leaves no file")
        pdfToolRun(listOf(source(locked, "kotlin-pw")), NativePdfToolOp.RemovePassword, target.path, Listener())
        assertFalse(pdfToolInspect(source(target)).encrypted)
    }

    @Test
    fun cancelling_is_typed() {
        val target = temp().apply { delete() }
        assertFailsWith<ReadException.Cancelled> { pdfToolRun(listOf(source(pdf(1))), NativePdfToolOp.Merge, target.path, Listener(cancel = true)) }
    }
}
