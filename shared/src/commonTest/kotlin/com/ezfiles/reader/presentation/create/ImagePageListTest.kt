package com.ezfiles.reader.presentation.create

import kotlin.test.Test
import kotlin.test.assertEquals

class ImagePageListTest {
    private val abc = ImagePageList().added(listOf("a", "b", "c"))

    private fun ImagePageList.uris() = pages.map { it.uri }

    @Test
    fun addingKeepsThePickersOrderAfterWhatIsThere() {
        assertEquals(listOf("a", "b", "c", "d", "e"), abc.added(listOf("d", "e")).uris())
    }

    @Test
    fun theSamePictureTwiceGetsTwoIdentities() {
        val pages = abc.added(listOf("a")).pages
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
    }

    @Test
    fun idsAreNotReusedAfterRemoval() {
        val last = abc.pages.last().id
        val again = abc.removed(last).added(listOf("d"))
        assertEquals(false, again.pages.last().id == last)
    }

    @Test
    fun movingSwapsNeighbours() {
        val b = abc.pages[1].id
        assertEquals(listOf("b", "a", "c"), abc.moved(b, -1).uris())
        assertEquals(listOf("a", "c", "b"), abc.moved(b, 1).uris())
    }

    @Test
    fun movingPastAnEndDoesNothing() {
        assertEquals(abc, abc.moved(abc.pages.first().id, -1))
        assertEquals(abc, abc.moved(abc.pages.last().id, 1))
        assertEquals(abc, abc.moved(999, 1))
    }

    @Test
    fun rotatingCyclesInQuarterTurnsAndTouchesOnlyThatPage() {
        val a = abc.pages[0].id
        var list = abc
        for (expected in listOf(90, 180, 270, 0)) {
            list = list.rotated(a)
            assertEquals(expected, list.pages[0].rotationDegrees)
            assertEquals(0, list.pages[1].rotationDegrees)
        }
    }

    @Test
    fun removingDropsOnlyThatPage() {
        assertEquals(listOf("a", "c"), abc.removed(abc.pages[1].id).uris())
    }

    @Test
    fun savedStateRestoresOrderRotationAndIds() {
        val list = abc.rotated(abc.pages[2].id).moved(abc.pages[2].id, -2).removed(abc.pages[0].id)
        val restored = ImagePageList.fromSaved(list.toSaved())
        assertEquals(list, restored)
        assertEquals(list.added(listOf("x")), restored.added(listOf("x")))
    }

    @Test
    fun anEmptyListSurvivesSaving() {
        assertEquals(ImagePageList(), ImagePageList.fromSaved(ImagePageList().toSaved()))
    }
}
