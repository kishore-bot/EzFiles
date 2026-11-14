package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.DocumentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PhotoGallerySessionTest {
    private fun image(uri: String, name: String = "same.jpg") = DocumentRef(uri, name, 10, identity = "same-name-size")

    @Test
    fun anExternalFileCannotAcquireNeighborsFromAnUnrelatedList() {
        assertNull(PhotoGallerySession.create(image("external"), listOf(image("library"))))
        assertNull(PhotoGallerySession.create(image("only"), emptyList()))
    }

    @Test
    fun frozenSequenceRetainsAuthorizedOrderAndDistinctSameNamedFiles() {
        val first = image("first")
        val second = image("second")
        val source = mutableListOf(second, first, second, image("document", "notes.txt"))
        val session = requireNotNull(PhotoGallerySession.create(first, source))
        source.clear()
        assertEquals(listOf(second, first), session.refs)
        assertEquals(first, session.selected.value)
        session.select(0)
        assertEquals(second, session.selected.value)
        assertEquals(1, session.indexOf(first.uri))
        assertFailsWith<IllegalArgumentException> { session.select(-1) }
        assertFailsWith<IllegalArgumentException> { session.select(2) }
        assertEquals(second, session.selected.value)
    }

    @Test
    fun rotationUsesTheFittedPictureRatherThanTheWholeViewportForPanBounds() {
        val fitted = photoBounds(1000f, 500f, 100, 200, 1f, 1)
        assertEquals(2f, fitted.fitScale)
        assertEquals(0f, fitted.panX)
        assertEquals(0f, fitted.panY)
        val zoomed = photoBounds(1000f, 1000f, 4000, 1000, 2f, 1)
        assertEquals(0f, zoomed.panX)
        assertEquals(500f, zoomed.panY)
        assertEquals(zoomed, photoBounds(1000f, 1000f, 4000, 1000, 2f, 5))
    }
}
