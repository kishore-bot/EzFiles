package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfFileInfo
import com.ezfiles.reader.domain.PdfSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfToolRulesTest {
    private fun pdf(id: Long, pages: Int = 2, signed: Boolean = false, archival: Boolean = false, encrypted: Boolean = false, password: String? = null) =
        PickedPdf(id, DocumentRef("test://$id", "f$id.pdf"), PdfFileInfo(pages, encrypted, signed, archival), password)

    @Test
    fun names_outputs_after_the_source() {
        assertEquals("report (merged).pdf", suggestedPdfName("report.pdf", "merged"))
        assertEquals("report (merged).pdf", suggestedPdfName("report.PDF", "merged"))
        assertEquals("report (merged).pdf", suggestedPdfName("report", "merged"))
        assertEquals("a.b (merged).pdf", suggestedPdfName("a.b.pdf", "merged"))
        assertEquals("x (merged).pdf", suggestedPdfName("primary:Download/x.pdf", "merged"))
        assertEquals("Document (merged).pdf", suggestedPdfName(".pdf", "merged"))
        assertEquals("Document (merged).pdf", suggestedPdfName("  ", "merged"))
        assertTrue(suggestedPdfName("n".repeat(500) + ".pdf", "merged").length < 140)
    }

    @Test
    fun does_not_cut_a_character_in_half() {
        val name = suggestedPdfName("n".repeat(119) + "😀.pdf", "x")
        assertEquals("n".repeat(119) + " (x).pdf", name)
    }

    @Test
    fun inserting_maps_to_the_page_before_which_pictures_go() {
        assertEquals(0, insertIndex(InsertPosition.Start, 5))
        assertEquals(5, insertIndex(InsertPosition.End, 5))
        assertEquals(1, insertIndex(InsertPosition.After(1), 5))
        assertEquals(5, insertIndex(InsertPosition.After(5), 5))
        assertNull(insertIndex(InsertPosition.After(0), 5))
        assertNull(insertIndex(InsertPosition.After(6), 5))
    }

    @Test
    fun describes_page_shapes() {
        assertEquals("A4 proportions", describePageShape(595f / 842f))
        assertEquals("Letter proportions", describePageShape(612f / 792f))
        assertEquals("A4 proportions, landscape", describePageShape(842f / 595f))
        assertEquals("other proportions", describePageShape(1f))
        assertEquals("not known", describePageShape(0f))
        assertEquals("not known", describePageShape(Float.NaN))
    }

    @Test
    fun thumbnails_stay_small_whatever_shape_a_page_claims() {
        assertEquals(360, thumbnailWidthPx(2f, 360))
        assertEquals(180, thumbnailWidthPx(0.5f, 360))
        assertEquals(1, thumbnailWidthPx(1f / 14400f, 360))
        assertEquals(360, thumbnailWidthPx(Float.NaN, 360))
        assertEquals(360, thumbnailWidthPx(0f, 360))
    }

    @Test
    fun merge_list_adds_moves_and_removes() {
        var list = PdfSourceList().added(pdf(1)).added(pdf(2, pages = 3)).added(pdf(3))
        assertEquals(listOf(1L, 2L, 3L), list.items.map { it.id })
        assertEquals(7, list.totalPages)
        assertTrue(list.canMerge)

        list = list.moved(3, -1)
        assertEquals(listOf(1L, 3L, 2L), list.items.map { it.id })
        list = list.moved(1, -1).moved(2, 1)
        assertEquals(listOf(1L, 3L, 2L), list.items.map { it.id })
        assertEquals(list, list.moved(99, 1))

        list = list.removed(3)
        assertEquals(listOf(1L, 2L), list.items.map { it.id })
        assertTrue(list.canMerge)
        list = list.removed(2)
        assertFalse(list.canMerge)
    }

    @Test
    fun warns_only_about_what_applies() {
        assertNull(changeWarning(listOf(pdf(1), pdf(2))))
        val signed = changeWarning(listOf(pdf(1, signed = true))).orEmpty()
        assertTrue("signature becomes invalid" in signed && "f1.pdf" in signed)
        assertFalse("PDF/A" in signed)
        val archival = changeWarning(listOf(pdf(2, archival = true))).orEmpty()
        assertTrue("may no longer conform" in archival && "signature" !in archival)
        val both = changeWarning(listOf(pdf(1, signed = true), pdf(2, archival = true))).orEmpty()
        assertTrue("signature" in both && "PDF/A" in both)
    }

    @Test
    fun says_when_a_file_is_not_a_pdf() {
        val notPdf = pickFailureMessage("notes.docx", DocumentError.Unsupported("Not a format"))
        assertEquals("\"notes.docx\" isn't a PDF. Choose a PDF file.", notPdf)
        assertEquals("\"notes.docx\" isn't a PDF. Choose a PDF file.", pickFailureMessage("notes.docx", DocumentError.Malformed("x")))
        // A .pdf name proves nothing either, but then the engine's words say more.
        assertEquals("This tool isn't ready yet.", pickFailureMessage("a.pdf", DocumentError.Unsupported("This tool isn't ready yet.")))
        assertTrue("damaged" in pickFailureMessage("a.pdf", DocumentError.Malformed("damaged")))
        assertEquals("too big", pickFailureMessage("a.docx", DocumentError.LimitExceeded("too big")))
    }

    @Test
    fun remove_password_steps() {
        assertEquals(RemovePasswordStep.NotProtected, removePasswordStep(pdf(1)))
        assertEquals(RemovePasswordStep.NeedsOwnerPassword, removePasswordStep(pdf(1, encrypted = true)))
        assertEquals(RemovePasswordStep.Ready, removePasswordStep(pdf(1, encrypted = true, password = "pw")))
    }

    @Test
    fun password_form_rules() {
        assertFalse(PasswordForm().canSubmit)
        assertFalse(PasswordForm().mismatch)
        assertFalse(PasswordForm("abc", "").canSubmit)
        assertFalse(PasswordForm("abc", "").mismatch)
        assertFalse(PasswordForm("abc", "abd").canSubmit)
        assertTrue(PasswordForm("abc", "abd").mismatch)
        assertTrue(PasswordForm("", "abd").mismatch)
        assertTrue(PasswordForm("a", "a").canSubmit)
    }

    @Test
    fun short_passwords_get_a_note_not_a_refusal() {
        assertTrue(PasswordForm("abc", "abc").isShort)
        assertTrue(PasswordForm("abc", "abc").canSubmit)
        assertFalse(PasswordForm("abcdef", "abcdef").isShort)
        assertFalse(PasswordForm().isShort)
    }

    @Test
    fun passwords_do_not_print() {
        assertFalse("hunter2" in PasswordForm("hunter2", "hunter2").toString())
        assertFalse("hunter2" in pdf(1, password = "hunter2").toString())
        assertFalse("hunter2" in PdfSource("test://a", "hunter2").toString())
        assertFalse("hunter2" in PdfSourceList(listOf(pdf(1, password = "hunter2"))).toString())
        assertFalse("hunter2" in PdfFlowState(sources = PdfSourceList(listOf(pdf(1, password = "hunter2")))).toString())
    }
}
