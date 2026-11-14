package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.data.SignatureStore
import com.ezfiles.reader.platform.DrawnSignature
import com.ezfiles.reader.platform.MAX_SAVED_SIGNATURES
import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStorage(initial: String? = null) : KeyValueStorage {
    val values = mutableMapOf<String, String>().apply { if (initial != null) put(KEY, initial) }

    override fun get(key: String) = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    companion object {
        const val KEY = "saved_signatures_v1"
    }
}

private fun signature(aspect: Float = 3f, x: Float = 0.5f) = DrawnSignature(
    id = 0,
    strokes = listOf(listOf(PdfPoint(0f, 0f), PdfPoint(x, 0.25f), PdfPoint(1f, 1f))),
    aspect = aspect,
    color = 0xFF111111.toInt(),
    penWidth = 0.02f,
)

private fun close(a: Float, b: Float, tolerance: Float = 0.001f) = abs(a - b) <= tolerance

class SignatureStoreTest {
    @Test
    fun savedSignaturesComeBackFromStorage() {
        val storage = MemoryStorage()
        val stored = assertNotNull(SignatureStore(storage).save(signature()))
        val restored = SignatureStore(storage).signatures.value.single()
        assertEquals(stored.id, restored.id)
        assertEquals(stored.aspect, restored.aspect)
        assertEquals(stored.color, restored.color)
        assertEquals(stored.penWidth, restored.penWidth)
        assertEquals(3, restored.strokes.single().size)
        assertTrue(close(restored.strokes.single()[1].x, 0.5f) && close(restored.strokes.single()[1].y, 0.25f))
    }

    @Test
    fun deleteRemovesItFromStorageToo() {
        val storage = MemoryStorage()
        val store = SignatureStore(storage)
        val first = assertNotNull(store.save(signature()))
        val second = assertNotNull(store.save(signature()))
        store.delete(first.id)
        assertEquals(listOf(second.id), store.signatures.value.map { it.id })
        assertEquals(listOf(second.id), SignatureStore(storage).signatures.value.map { it.id })
    }

    @Test
    fun deletingAnUnknownIdChangesNothing() {
        val store = SignatureStore(MemoryStorage())
        store.save(signature())
        store.delete(999)
        assertEquals(1, store.signatures.value.size)
    }

    @Test
    fun aFullStoreRefusesRatherThanDroppingOne() {
        val store = SignatureStore(MemoryStorage())
        repeat(MAX_SAVED_SIGNATURES) { assertNotNull(store.save(signature())) }
        val before = store.signatures.value
        assertNull(store.save(signature()))
        assertEquals(before, store.signatures.value)
    }

    @Test
    fun idsAreUniqueAfterADelete() {
        val store = SignatureStore(MemoryStorage())
        val first = assertNotNull(store.save(signature()))
        val second = assertNotNull(store.save(signature()))
        store.delete(first.id)
        val third = assertNotNull(store.save(signature()))
        assertTrue(third.id != second.id)
    }

    @Test
    fun corruptDataIsAnEmptyListAndCanBeReplaced() {
        for (bad in listOf("", "not json", "{}", "[{\"id\":1}]", "[{\"id\":\"x\",\"aspect\":1,\"color\":0,\"pen\":1,\"strokes\":[]}]")) {
            val storage = MemoryStorage(bad)
            val store = SignatureStore(storage)
            assertEquals(emptyList(), store.signatures.value, "for: $bad")
            assertNotNull(store.save(signature()))
            assertEquals(1, SignatureStore(storage).signatures.value.size)
        }
    }

    @Test
    fun entriesThatDontDescribeASignatureAreDropped() {
        val good = "{\"id\":1,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[[0,0,10000,10000]]}"
        val badEntries = listOf(
            "{\"id\":2,\"aspect\":-1.0,\"color\":0,\"pen\":0.01,\"strokes\":[[0,0]]}", // Negative aspect.
            "{\"id\":3,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[]}", // No strokes.
            "{\"id\":4,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[[1,2,3]]}", // Half a point.
            "{\"id\":5,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[[0,20000]]}", // Off the box.
            "{\"id\":1,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[[0,0]]}", // A repeated id.
        )
        val store = SignatureStore(MemoryStorage("[$good,${badEntries.joinToString(",")}]"))
        assertEquals(listOf(1L), store.signatures.value.map { it.id })
    }

    @Test
    fun tooManyStoredEntriesAreTrimmed() {
        val entries = (1..20).joinToString(",") { "{\"id\":$it,\"aspect\":2.0,\"color\":0,\"pen\":0.01,\"strokes\":[[0,0]]}" }
        assertEquals(MAX_SAVED_SIGNATURES, SignatureStore(MemoryStorage("[$entries]")).signatures.value.size)
    }

    @Test
    fun absurdlyLargeDataIsIgnored() {
        assertEquals(emptyList(), SignatureStore(MemoryStorage("[" + " ".repeat(2_000_000) + "]")).signatures.value)
    }
}

class DrawnSignatureTest {
    private val black = 0xFF000000.toInt()

    @Test
    fun nothingDrawnIsNoSignature() {
        assertNull(drawnSignature(emptyList(), 3f, black, 0.01f))
        assertNull(drawnSignature(listOf(emptyList()), 3f, black, 0.01f))
    }

    @Test
    fun itIsCroppedToTheInkWithoutDistortion() {
        // Half the pad wide (1.5 height units) and half its height (0.5).
        val drawn = assertNotNull(drawnSignature(listOf(listOf(PdfPoint(0.25f, 0.25f), PdfPoint(0.75f, 0.75f))), 3f, black, 0.01f))
        assertTrue(close(drawn.aspect, 3f))
        assertEquals(PdfPoint(0f, 0f), drawn.strokes.single().first())
        assertEquals(PdfPoint(1f, 1f), drawn.strokes.single().last())
    }

    @Test
    fun thePenKeepsItsThicknessRelativeToTheInk() {
        val drawn = assertNotNull(drawnSignature(listOf(listOf(PdfPoint(0.25f, 0.25f), PdfPoint(0.75f, 0.75f))), 3f, black, 0.01f))
        // The box is half the pad's width, so the same pen is twice the fraction.
        assertTrue(close(drawn.penWidth, 0.02f))
    }

    @Test
    fun aSingleDotAndAStraightLineStayPlaceable() {
        val dot = assertNotNull(drawnSignature(listOf(listOf(PdfPoint(0.5f, 0.5f))), 3f, black, 0.01f))
        assertTrue(dot.aspect.isFinite() && dot.aspect > 0f)
        assertTrue(dot.strokes.flatten().all { it.x in 0f..1f && it.y in 0f..1f })
        val line = assertNotNull(drawnSignature(listOf(listOf(PdfPoint(0f, 0.5f), PdfPoint(1f, 0.5f))), 3f, black, 0.01f))
        assertTrue(line.aspect <= 12.001f)
        assertTrue(close(line.strokes.single().first().y, 0.5f), "a flat line stays centred, not stretched")
    }

    @Test
    fun longDrawingsAreBounded() {
        val wiggle = List(40) { stroke ->
            List(500) { index -> PdfPoint(index / 500f, (stroke + (index % 2)) / 41f) }
        }
        val drawn = assertNotNull(drawnSignature(wiggle, 3f, black, 0.01f))
        assertTrue(drawn.strokes.sumOf { it.size } <= MAX_SIGNATURE_POINTS)
    }

    @Test
    fun strokesPastTheLimitAreLeftOut() {
        val many = List(MAX_SIGNATURE_STROKES + 10) { listOf(PdfPoint(0f, 0f), PdfPoint(1f, 1f)) }
        assertEquals(MAX_SIGNATURE_STROKES, assertNotNull(drawnSignature(many, 3f, black, 0.01f)).strokes.size)
    }
}

class SignaturePlacementTest {
    private val portrait = 1f / 1.414f

    private fun placed(x: Float = 0.5f, y: Float = 0.5f, width: Float = 0.35f, aspect: Float = 3f, pageAspect: Float = portrait) =
        SignaturePlacement.at(signature(aspect), 2, PdfPoint(x, y), pageAspect, width)

    private fun assertWholeOnPage(placement: SignaturePlacement) {
        assertTrue(placement.left >= -0.0001f, "left ${placement.left}")
        assertTrue(placement.top >= -0.0001f, "top ${placement.top}")
        assertTrue(placement.left + placement.widthFraction <= 1.0001f)
        assertTrue(placement.top + placement.heightFraction <= 1.0001f)
    }

    @Test
    fun aTapCentresIt() {
        val placement = placed(0.5f, 0.4f)
        assertEquals(0.5f, placement.centerX)
        assertEquals(0.4f, placement.centerY)
        assertEquals(0.35f, placement.widthFraction)
    }

    @Test
    fun itsHeightKeepsItsShapeOnThePage() {
        // Square on the page means equal physical size: height fraction = width fraction * page aspect.
        val placement = placed(aspect = 1f)
        assertTrue(close(placement.heightFraction, 0.35f * portrait))
    }

    @Test
    fun aTapAtAnEdgeOrCornerKeepsItOnThePage() {
        listOf(0f to 0f, 1f to 1f, 0f to 1f, 1f to 0f).forEach { (x, y) -> assertWholeOnPage(placed(x, y)) }
    }

    @Test
    fun draggingStopsAtThePageEdge() {
        var placement = placed()
        repeat(30) { placement = placement.movedBy(0.1f, 0.1f) }
        assertWholeOnPage(placement)
        assertTrue(close(placement.left + placement.widthFraction, 1f))
    }

    @Test
    fun resizingStaysWithinLimits() {
        assertTrue(placed().resizedTo(0.001f).widthFraction >= 0.08f)
        assertEquals(1f, placed().resizedTo(5f).widthFraction)
        assertWholeOnPage(placed(0.9f, 0.9f).resizedTo(5f))
    }

    @Test
    fun aTallSignatureOnAWidePageIsLimitedByHeight() {
        val placement = placed(aspect = 0.5f, pageAspect = 2f, width = 0.9f)
        assertTrue(placement.heightFraction <= 1.0001f)
        assertWholeOnPage(placement)
    }

    @Test
    fun theHandleGrowsAndShrinksAlongTheDiagonal() {
        val start = placed()
        assertTrue(start.handleDraggedBy(0.05f, 0.05f).widthFraction > start.widthFraction)
        assertTrue(start.handleDraggedBy(-0.05f, -0.05f).widthFraction < start.widthFraction)
        // Dragging across the diagonal hardly changes the size.
        val across = start.handleDraggedBy(0.01f, -0.03f * portrait)
        assertTrue(abs(across.widthFraction - start.widthFraction) < 0.02f)
    }

    @Test
    fun containsMatchesTheBox() {
        val placement = placed()
        assertTrue(placement.contains(PdfPoint(0.5f, 0.5f)))
        assertFalse(placement.contains(PdfPoint(0.05f, 0.5f)))
        assertFalse(placement.contains(PdfPoint(0.5f, 0.95f)))
    }

    @Test
    fun itsInkIsASignatureAnnotationScaledToTheBox() {
        val placement = placed(width = 0.5f)
        val ink = placement.ink()
        assertEquals(2, ink.page)
        assertEquals(PdfInkStyle.Signature, ink.style)
        assertEquals(0xFF111111.toInt(), ink.color)
        assertTrue(close(ink.width, 0.02f * 0.5f))
        val points = ink.strokes.single()
        assertTrue(close(points.first().x, placement.left) && close(points.first().y, placement.top))
        assertTrue(close(points.last().x, placement.left + 0.5f))
    }

    @Test
    fun movingDoesNotWearDownTheStrokes() {
        var placement = placed()
        repeat(200) { placement = placement.movedBy(0.001f, -0.001f).movedBy(-0.001f, 0.001f) }
        val original = placed().ink().strokes.single()
        val after = placement.ink().strokes.single()
        original.zip(after).forEach { (a, b) -> assertTrue(close(a.x, b.x, 0.01f) && close(a.y, b.y, 0.01f)) }
    }
}

class SignaturePlacerTest {
    private val aspect = 0.7f

    @Test
    fun aTapPlacesThePendingSignatureAndLeavesItMovable() {
        val placer = SignaturePlacer()
        placer.choose(signature())
        val history = placer.placeAt(PdfAnnotationHistory.Empty, 1, PdfPoint(0.5f, 0.5f), aspect)
        assertNull(placer.pending)
        assertEquals(1, assertNotNull(placer.active).page)
        assertIsSignature(history.items.single())
        assertTrue(history.canUndo)
    }

    @Test
    fun withNothingPendingATapDoesNothing() {
        val placer = SignaturePlacer()
        assertEquals(PdfAnnotationHistory.Empty, placer.placeAt(PdfAnnotationHistory.Empty, 0, PdfPoint(0f, 0f), aspect))
    }

    @Test
    fun adjustingChangesTheSameItemAndOneUndoRemovesIt() {
        val placer = SignaturePlacer()
        placer.choose(signature())
        var history = placer.placeAt(PdfAnnotationHistory.Empty, 0, PdfPoint(0.5f, 0.5f), aspect)
        val before = history.items.single()
        repeat(5) { history = placer.adjust(history, assertNotNull(placer.active).movedBy(0.01f, 0.01f)) }
        assertEquals(1, history.items.size)
        assertTrue(history.items.single() != before)
        history = placer.undo(history)
        assertEquals(emptyList(), history.items)
        assertNull(placer.active)
    }

    @Test
    fun aFinishedSignatureStaysAndAnotherCanFollow() {
        val placer = SignaturePlacer()
        placer.choose(signature())
        var history = placer.placeAt(PdfAnnotationHistory.Empty, 0, PdfPoint(0.2f, 0.2f), aspect)
        val first = history.items.single()
        placer.finish()
        assertNull(placer.active)
        history = placer.adjust(history, SignaturePlacement.at(signature(), 0, PdfPoint(0.9f, 0.9f), aspect))
        assertEquals(first, history.items.single(), "a finished signature can't be moved")
        placer.placeAgain()
        history = placer.placeAt(history, 3, PdfPoint(0.8f, 0.8f), aspect)
        assertEquals(listOf(0, 3), history.items.map { it.page })
        assertEquals(first, history.items.first())
    }

    @Test
    fun aSavedHistoryIsDirtyAgainAfterAMove() {
        val placer = SignaturePlacer()
        placer.choose(signature())
        var history = placer.placeAt(PdfAnnotationHistory.Empty, 0, PdfPoint(0.5f, 0.5f), aspect).markSaved()
        assertFalse(history.isDirty)
        history = placer.adjust(history, assertNotNull(placer.active).movedBy(0.1f, 0f))
        assertTrue(history.isDirty)
    }

    @Test
    fun replacingInAnEmptyHistoryIsHarmless() {
        val note = PdfAnnotation.Note(0, 0, 0f, 0f, "n")
        assertEquals(emptyList(), PdfAnnotationHistory.Empty.replaceLast(note).items)
    }

    @Test
    fun cancellingTheChooserLeavesNothingPending() {
        val placer = SignaturePlacer()
        placer.startPicking()
        assertTrue(placer.picking)
        placer.cancelPicking()
        assertFalse(placer.picking)
        assertNull(placer.pending)
    }

    @Test
    fun hintsFollowTheStep() {
        val placer = SignaturePlacer()
        assertNull(placer.hint)
        placer.choose(signature())
        assertNotNull(placer.hint)
        placer.placeAt(PdfAnnotationHistory.Empty, 0, PdfPoint(0.5f, 0.5f), aspect)
        assertTrue(assertNotNull(placer.hint).contains("resize"))
    }

    @Test
    fun aSignedCopyIsNamedPlainly() {
        assertEquals("report (signed).pdf", signedFileName("report.pdf"))
        assertEquals("document (signed).pdf", signedFileName(".pdf"))
    }

    private fun assertIsSignature(annotation: PdfAnnotation) {
        assertEquals(PdfInkStyle.Signature, (annotation as PdfAnnotation.Ink).style)
    }
}
