package com.ezfiles.reader.scan

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfImageQuality
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfTextLine
import com.ezfiles.reader.domain.RecognizedLine
import com.ezfiles.reader.domain.RecognizedText
import com.ezfiles.reader.domain.TextPagePreview
import com.ezfiles.reader.domain.TextPdfStyle
import com.ezfiles.reader.domain.TextRecognizer
import com.ezfiles.reader.platform.DocumentFiles
import com.ezfiles.reader.platform.LumaImage
import com.ezfiles.reader.platform.ScanPictureKind
import com.ezfiles.reader.platform.ScanWorkspace
import com.ezfiles.reader.presentation.scan.ImageToTextAction
import com.ezfiles.reader.presentation.scan.ImageToTextStep
import com.ezfiles.reader.presentation.scan.ImageToTextViewModel
import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanAction
import com.ezfiles.reader.presentation.scan.ScanFilter
import com.ezfiles.reader.presentation.scan.ScanStep
import com.ezfiles.reader.presentation.scan.ScanViewModel
import com.ezfiles.reader.presentation.scan.TextSaveFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeWorkspace : ScanWorkspace {
    val sources = HashSet<String>()
    val rendered = ArrayList<Triple<String, PageQuad, ScanFilter>>()
    val deletedDocuments = ArrayList<String>()
    var state: String? = null
    var clears = 0
    var unreadable = setOf<String>()
    var pdfText: String? = null

    override fun captureTarget(id: String) = "capture:$id"

    override suspend fun importSource(id: String, uri: String) {
        if (uri in unreadable) throw DocumentException(DocumentError.Malformed("Unreadable"))
        sources += id
    }

    override suspend fun sourceLuma(id: String, maxSide: Int) = LumaImage(ByteArray(32 * 32) { 100 }, 32, 32)

    override suspend fun render(id: String, quad: PageQuad, filter: ScanFilter) {
        rendered += Triple(id, quad, filter)
    }

    override suspend fun exists(id: String) = id in sources

    override suspend fun preview(id: String, kind: ScanPictureKind, maxSide: Int): ImageBitmap = error("Not used")

    override fun pictureUri(id: String, kind: ScanPictureKind) = "file:$id"

    override suspend fun readOutputs(ids: List<String>) = ids.map { it.encodeToByteArray() }

    override suspend fun deletePage(id: String) {
        sources -= id
    }

    override suspend fun readState() = state

    override suspend fun writeState(state: String) {
        this.state = state
    }

    override suspend fun clear() {
        clears++
        sources.clear()
        state = null
    }

    override suspend fun writeTextPdf(text: String, target: String) {
        pdfText = text
    }

    override suspend fun deleteDocument(target: String) {
        deletedDocuments += target
    }
}

private class FakeRecognizer(var result: RecognizedText = RecognizedText("", emptyList())) : TextRecognizer {
    var failing = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun recognize(imageUri: String): RecognizedText {
        gate?.await()
        if (failing) throw DocumentException(DocumentError.Internal("Broken"))
        return result
    }
}

private class FakePdf : PdfCreator {
    var pages: List<String> = emptyList()
    var layers: List<List<PdfTextLine>> = emptyList()
    var failing = false

    override suspend fun imagesToPdf(
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        marginPt: Float,
        target: String,
        textLayers: List<List<PdfTextLine>>,
        quality: PdfImageQuality,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        if (failing) throw DocumentException(DocumentError.FileAccess("Disk full"))
        this.pages = pages.map { checkNotNull(it.encoded).decodeToString() }
        layers = textLayers
    }

    override suspend fun imageThumbnail(uri: String, maxSidePx: Int): ByteArray = error("not used")

    override suspend fun textToPdf(
        text: String,
        style: TextPdfStyle,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = error("not used")

    override suspend fun textToPng(text: String, style: TextPdfStyle, target: String): Int = error("not used")

    override suspend fun textPreview(text: String, style: TextPdfStyle, pageIndex: Int, widthPx: Int): TextPagePreview =
        error("not used")
}

private class FakeFiles : DocumentFiles {
    val written = HashMap<String, String>()

    override suspend fun read(uri: String) = ByteArray(0)

    override suspend fun write(uri: String, bytes: ByteArray) {
        written[uri] = bytes.decodeToString()
    }

    override suspend fun canWrite(uri: String) = true

    override suspend fun refOf(uri: String) = DocumentRef(uri, uri)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val workspace = FakeWorkspace()
    private val recognizer = FakeRecognizer()
    private val pdf = FakePdf()

    private fun scanner(): ScanViewModel {
        var next = 0
        return ScanViewModel(workspace, recognizer, pdf, dispatcher, newId = { "p${next++}" })
    }

    private fun ids(viewModel: ScanViewModel) = viewModel.state.value.pages.map { it.id }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_picture_with_no_outline_opens_the_corner_editor_on_the_whole_frame() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        val page = viewModel.state.value.pages.single()
        assertFalse(page.detected)
        assertEquals(PageQuad.FullFrame, page.quad)
        assertEquals(ScanStep.Adjust(page.id), viewModel.state.value.step)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun taking_a_picture_adds_a_page_from_the_cameras_file() {
        val viewModel = scanner()
        var target: String? = null
        viewModel.onAction(ScanAction.TakePicture { target = it })
        assertEquals("capture:p0", target)
        assertEquals(listOf("p0"), ids(viewModel))
        assertEquals(ScanStep.Adjust("p0"), viewModel.state.value.step)
    }

    @Test
    fun a_failed_capture_adds_nothing_and_explains() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.TakePicture { throw DocumentException(DocumentError.FileAccess("No picture")) })
        assertTrue(viewModel.state.value.pages.isEmpty())
        assertEquals("No picture", viewModel.state.value.message)
    }

    @Test
    fun several_pictures_go_to_the_page_list_and_bad_ones_are_counted() {
        workspace.unreadable = setOf("bad")
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "bad", "b")))
        assertEquals(2, viewModel.state.value.pages.size)
        assertEquals(ScanStep.Pages, viewModel.state.value.step)
        assertEquals("1 picture couldn't be read.", viewModel.state.value.message)
    }

    @Test
    fun pages_can_be_reordered_and_stay_in_bounds() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "b", "c")))
        viewModel.onAction(ScanAction.Move("p2", -1))
        assertEquals(listOf("p0", "p2", "p1"), ids(viewModel))
        viewModel.onAction(ScanAction.Move("p0", -1))
        assertEquals(listOf("p0", "p2", "p1"), ids(viewModel))
        viewModel.onAction(ScanAction.Move("p1", 1))
        assertEquals(listOf("p0", "p2", "p1"), ids(viewModel))
    }

    @Test
    fun retaking_replaces_the_page_in_place_and_deletes_the_old_files() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "b", "c")))
        viewModel.onAction(ScanAction.Retake("p1"))
        viewModel.onAction(ScanAction.TakePicture { })
        assertEquals(listOf("p0", "p3", "p2"), ids(viewModel))
        assertFalse("p1" in workspace.sources)
    }

    @Test
    fun deleting_the_last_page_returns_to_the_camera() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.Delete("p0"))
        assertTrue(viewModel.state.value.pages.isEmpty())
        assertIs<ScanStep.Capture>(viewModel.state.value.step)
        assertTrue(workspace.sources.isEmpty())
    }

    @Test
    fun changing_a_filter_redraws_the_page_and_bumps_its_version() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.SetFilter("p0", ScanFilter.BlackAndWhite))
        val page = viewModel.state.value.pages.single()
        assertEquals(ScanFilter.BlackAndWhite, page.filter)
        assertEquals(1, page.version)
        assertEquals(ScanFilter.BlackAndWhite, workspace.rendered.last().third)
        assertTrue(viewModel.state.value.rendering.isEmpty())
    }

    @Test
    fun a_filter_can_go_on_every_page() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "b")))
        viewModel.onAction(ScanAction.SetFilterOnAll(ScanFilter.Grayscale))
        assertTrue(viewModel.state.value.pages.all { it.filter == ScanFilter.Grayscale })
    }

    @Test
    fun rotating_redraws_with_shifted_corners() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.Rotate("p0"))
        assertEquals(PageQuad.FullFrame.rotatedClockwise(), workspace.rendered.last().second)
    }

    @Test
    fun saving_writes_pages_in_order_and_then_deletes_the_scan() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "b")))
        viewModel.onAction(ScanAction.Move("p1", -1))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertEquals(listOf("p1", "p0"), pdf.pages)
        assertTrue(pdf.layers.isEmpty())
        assertEquals(ScanStep.Saved, viewModel.state.value.step)
        assertEquals("target", viewModel.state.value.saved?.uri)
        assertTrue(workspace.sources.isEmpty())
        assertEquals(1, workspace.clears)
    }

    @Test
    fun a_failed_save_keeps_the_pages_and_removes_the_empty_file() {
        pdf.failing = true
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertEquals(listOf("p0"), ids(viewModel))
        assertEquals("Disk full", viewModel.state.value.message)
        assertEquals(listOf("target"), workspace.deletedDocuments)
        assertEquals(0, workspace.clears)
    }

    @Test
    fun searchable_passes_one_text_layer_per_page() {
        recognizer.result = RecognizedText("Hi", listOf(RecognizedLine("Hi", 0f, 0f, 0.5f, 0.1f)))
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a", "b")))
        viewModel.onAction(ScanAction.SetSearchable(true))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertEquals(2, pdf.layers.size)
        assertEquals(PdfTextLine("Hi", 0f, 0f, 0.5f, 0.1f), pdf.layers[0].single())
        assertNull(viewModel.state.value.saved?.note)
    }

    @Test
    fun a_recognizer_failure_still_saves_and_says_so() {
        recognizer.failing = true
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.SetSearchable(true))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertEquals(listOf("p0"), pdf.pages)
        assertTrue(assertNotNull(viewModel.state.value.saved?.note).contains("searchable"))
    }

    @Test
    fun finding_no_text_is_reported() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.SetSearchable(true))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertEquals("No text was found, so this PDF isn't searchable.", viewModel.state.value.saved?.note)
    }

    @Test
    fun cancelling_a_save_keeps_the_pages_and_removes_the_file() {
        val gate = CompletableDeferred<Unit>()
        recognizer.gate = gate
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.SetSearchable(true))
        viewModel.onAction(ScanAction.SaveTo("target"))
        assertNotNull(viewModel.state.value.busy)
        viewModel.onAction(ScanAction.CancelWork)
        assertNull(viewModel.state.value.busy)
        assertEquals(listOf("p0"), ids(viewModel))
        assertEquals(listOf("target"), workspace.deletedDocuments)
        assertTrue(pdf.pages.isEmpty())
    }

    @Test
    fun discarding_deletes_everything_and_starts_over() {
        val viewModel = scanner()
        viewModel.onAction(ScanAction.ImportPictures(listOf("a")))
        viewModel.onAction(ScanAction.Discard)
        assertTrue(viewModel.state.value.pages.isEmpty())
        assertIs<ScanStep.Capture>(viewModel.state.value.step)
        assertTrue(workspace.sources.isEmpty())
        assertNull(workspace.state)
    }

    @Test
    fun a_killed_session_comes_back_from_the_workspace() {
        val first = scanner()
        first.onAction(ScanAction.ImportPictures(listOf("a", "b")))
        first.onAction(ScanAction.SetFilter("p1", ScanFilter.Grayscale))
        first.onAction(ScanAction.SetSearchable(true))

        val restored = scanner()
        assertEquals(listOf("p0", "p1"), ids(restored))
        assertEquals(ScanFilter.Grayscale, restored.state.value.pages[1].filter)
        assertTrue(restored.state.value.searchable)
        assertEquals(ScanStep.Pages, restored.state.value.step)
    }

    @Test
    fun a_restored_session_skips_pages_the_system_cleared() {
        val first = scanner()
        first.onAction(ScanAction.ImportPictures(listOf("a", "b")))
        workspace.sources -= "p0"
        assertEquals(listOf("p1"), ids(scanner()))
    }

    @Test
    fun a_damaged_saved_state_is_dropped_not_fatal() {
        workspace.state = "{ not json"
        val viewModel = scanner()
        assertTrue(viewModel.state.value.pages.isEmpty())
        assertEquals(1, workspace.clears)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImageToTextViewModelTest {
    private val workspace = FakeWorkspace()
    private val recognizer = FakeRecognizer(RecognizedText("Hello\nWorld", emptyList()))
    private val files = FakeFiles()

    private fun viewModel() = ImageToTextViewModel(workspace, recognizer, files)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_picked_picture_is_read_for_review() {
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        assertEquals(ImageToTextStep.Review, viewModel.state.value.step)
        assertEquals("Hello\nWorld", viewModel.text)
        assertFalse(viewModel.state.value.noText)
    }

    @Test
    fun no_text_is_shown_as_such() {
        recognizer.result = RecognizedText("  ", emptyList())
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        assertTrue(viewModel.state.value.noText)
    }

    @Test
    fun an_unreadable_picture_explains_and_stays_on_the_source_step() {
        workspace.unreadable = setOf("a")
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        assertEquals(ImageToTextStep.Source, viewModel.state.value.step)
        assertEquals("Unreadable", viewModel.state.value.message)
    }

    @Test
    fun saving_text_writes_the_edited_text_and_deletes_the_photo() {
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        viewModel.text = "Edited é"
        viewModel.onAction(ImageToTextAction.PrepareSave(TextSaveFormat.Text))
        viewModel.onAction(ImageToTextAction.SaveTo("target"))
        assertEquals("Edited é", files.written["target"])
        assertEquals(ImageToTextStep.Saved, viewModel.state.value.step)
        assertTrue(workspace.sources.isEmpty())
    }

    @Test
    fun saving_a_pdf_uses_the_edited_text() {
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        viewModel.text = "Edited"
        viewModel.onAction(ImageToTextAction.PrepareSave(TextSaveFormat.Pdf))
        viewModel.onAction(ImageToTextAction.SaveTo("target"))
        assertEquals("Edited", workspace.pdfText)
        assertTrue(files.written.isEmpty())
    }

    @Test
    fun cancelling_reading_deletes_the_photo_and_shows_no_error() {
        val gate = CompletableDeferred<Unit>()
        recognizer.gate = gate
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        viewModel.onAction(ImageToTextAction.CancelWork)
        assertNull(viewModel.state.value.busy)
        assertNull(viewModel.state.value.message)
        assertEquals(ImageToTextStep.Source, viewModel.state.value.step)
        assertTrue(workspace.sources.isEmpty())
    }

    @Test
    fun edited_text_and_the_chosen_format_survive_the_process_being_killed() {
        val first = viewModel()
        first.onAction(ImageToTextAction.Picked("a"))
        first.text = "Edited"
        first.onAction(ImageToTextAction.PrepareSave(TextSaveFormat.Pdf))

        val restored = viewModel()
        assertEquals("Edited", restored.text)
        assertEquals(ImageToTextStep.Review, restored.state.value.step)
        restored.onAction(ImageToTextAction.SaveTo("target"))
        assertEquals("Edited", workspace.pdfText)
    }

    @Test
    fun discarding_deletes_the_photo_and_text() {
        val viewModel = viewModel()
        viewModel.onAction(ImageToTextAction.Picked("a"))
        viewModel.onAction(ImageToTextAction.Discard)
        assertEquals("", viewModel.text)
        assertEquals(ImageToTextStep.Source, viewModel.state.value.step)
        assertTrue(workspace.sources.isEmpty())
    }
}
