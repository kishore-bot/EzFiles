package com.ezfiles.reader.presentation.viewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPageScrubberTest {
    @Test
    fun sliderValueRoundsToTheNearestPage() {
        assertEquals(0, pageForSlider(0.4f, 24))
        assertEquals(1, pageForSlider(0.5f, 24))
        assertEquals(23, pageForSlider(23f, 24))
    }

    @Test
    fun sliderValueStaysInsideTheDocument() {
        assertEquals(23, pageForSlider(40f, 24))
        assertEquals(0, pageForSlider(-3f, 24))
        assertEquals(0, pageForSlider(Float.NaN, 24))
        assertEquals(0, pageForSlider(5f, 1))
        assertEquals(0, pageForSlider(5f, 0))
    }

    @Test
    fun sliderRangeIsNeverEmpty() {
        assertEquals(0f..23f, pageSliderRange(24))
        assertEquals(0f..1f, pageSliderRange(1))
        assertEquals(0f..1f, pageSliderRange(0))
    }

    @Test
    fun thumbnailWindowKeepsTheCurrentPageSecond() {
        assertEquals(4..7, thumbnailWindow(current = 5, pageCount = 24, size = 4))
    }

    @Test
    fun thumbnailWindowStopsAtTheEnds() {
        assertEquals(0..3, thumbnailWindow(current = 0, pageCount = 24, size = 4))
        assertEquals(20..23, thumbnailWindow(current = 23, pageCount = 24, size = 4))
        assertEquals(0..1, thumbnailWindow(current = 1, pageCount = 2, size = 4))
        assertTrue(thumbnailWindow(current = 0, pageCount = 0, size = 4).isEmpty())
        assertTrue(thumbnailWindow(current = 3, pageCount = 10, size = 0).isEmpty())
    }

    @Test
    fun narrowScreensShowFewerThumbnails() {
        assertEquals(4, thumbnailsFor(412f))
        assertEquals(3, thumbnailsFor(340f))
        assertEquals(0, thumbnailsFor(300f))
    }
}
