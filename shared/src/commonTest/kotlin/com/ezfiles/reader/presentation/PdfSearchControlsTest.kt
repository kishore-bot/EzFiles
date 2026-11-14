package com.ezfiles.reader.presentation

import com.ezfiles.reader.platform.PdfBox
import com.ezfiles.reader.presentation.viewers.PdfMatchTracker
import com.ezfiles.reader.presentation.viewers.PdfMatchView
import com.ezfiles.reader.presentation.viewers.PdfSearchHit
import com.ezfiles.reader.presentation.viewers.matchInViewport
import com.ezfiles.reader.presentation.viewers.pdfMatchView
import com.ezfiles.reader.presentation.viewers.searchHidesPageControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchHidesPageControlsTest {
    @Test
    fun hidesOnlyWhileSearchingWithTheKeyboardOpen() {
        assertTrue(searchHidesPageControls(searchOpen = true, keyboardOpen = true, annotating = false))
    }

    @Test
    fun keyboardDismissedWithTheSearchBarStillOpenBringsThemBack() {
        assertFalse(searchHidesPageControls(searchOpen = true, keyboardOpen = false, annotating = false))
    }

    @Test
    fun aKeyboardWithoutSearchChangesNothing() {
        assertFalse(searchHidesPageControls(searchOpen = false, keyboardOpen = true, annotating = false))
    }

    @Test
    fun annotatingKeepsItsToolsEvenWhileSearching() {
        assertFalse(searchHidesPageControls(searchOpen = true, keyboardOpen = true, annotating = true))
    }
}

class MatchInViewportTest {
    private val box = PdfBox(0f, 0.5f, 1f, 0.6f)

    @Test
    fun fullyInsideIsVisible() {
        assertTrue(matchInViewport(box, itemOffset = 0, itemSize = 1000, viewportHeight = 600))
    }

    @Test
    fun belowTheBottomEdgeIsNot() {
        assertFalse(matchInViewport(box, itemOffset = 0, itemSize = 1000, viewportHeight = 599))
    }

    @Test
    fun aboveTheTopEdgeIsNot() {
        assertFalse(matchInViewport(box, itemOffset = -501, itemSize = 1000, viewportHeight = 600))
    }
}

class PdfMatchTrackerTest {
    private val hit = PdfSearchHit(0, listOf(PdfBox(0f, 0.5f, 1f, 0.6f)))
    private val other = PdfSearchHit(1, listOf(PdfBox(0f, 0.5f, 1f, 0.6f)))

    private fun showing(height: Int = 2000, target: PdfSearchHit = hit) = PdfMatchView(target, height, visible = true)
    private fun hidden(height: Int = 2000, target: PdfSearchHit = hit) = PdfMatchView(target, height, visible = false)

    @Test
    fun aResizeThatPushesTheMatchOutNeedsAReveal() {
        val tracker = PdfMatchTracker.None.after(showing()).after(hidden(height = 900))
        assertTrue(tracker.resized)
        assertTrue(tracker.needsReveal)
    }

    @Test
    fun aResizeThatLeavesTheMatchShowingDoesNot() {
        val tracker = PdfMatchTracker.None.after(showing()).after(showing(height = 900))
        assertTrue(tracker.resized)
        assertFalse(tracker.needsReveal)
    }

    @Test
    fun followsEveryFrameOfAnAnimatedResize() {
        val tracker = PdfMatchTracker.None
            .after(showing())
            .after(showing(height = 1800))
            .after(hidden(height = 1500))
            .after(hidden(height = 900))
        assertTrue(tracker.needsReveal)
    }

    @Test
    fun aMatchTheUserScrolledAwayFromIsLeftAlone() {
        val tracker = PdfMatchTracker.None
            .after(showing(height = 900))
            .after(hidden(height = 900))
            .after(hidden(height = 2000))
        assertTrue(tracker.resized)
        assertFalse(tracker.needsReveal)
    }

    @Test
    fun scrollingAwayWhileTheLayoutSettlesCancelsTheReveal() {
        val tracker = PdfMatchTracker.None
            .after(showing())
            .after(showing(height = 900))
            .after(hidden(height = 900))
        assertFalse(tracker.needsReveal)
    }

    @Test
    fun aNewMatchStillOnItsWayIsNotRevealedTwice() {
        val tracker = PdfMatchTracker.None.after(showing()).after(hidden(target = other))
        assertFalse(tracker.resized)
        assertFalse(tracker.needsReveal)
    }

    @Test
    fun aResizeDuringANewMatchsScrollStillRevealsIt() {
        val tracker = PdfMatchTracker.None.after(showing()).after(hidden(target = other)).after(hidden(height = 900, target = other))
        assertTrue(tracker.needsReveal)
    }

    @Test
    fun revealsOnlyOncePerResize() {
        val tracker = PdfMatchTracker.None.after(showing()).after(hidden(height = 900)).settled()
        assertFalse(tracker.resized)
        assertFalse(tracker.needsReveal)
    }

    @Test
    fun theRevealsOwnScrollKeepsTheMatchWanted() {
        val tracker = PdfMatchTracker.None
            .after(showing())
            .after(hidden(height = 900))
            .settled()
            .after(showing(height = 900))
            .after(hidden(height = 2000))
        assertTrue(tracker.needsReveal)
    }

    @Test
    fun endingTheSearchForgetsTheMatch() {
        assertEquals(PdfMatchTracker.None, PdfMatchTracker.None.after(showing()).after(null))
    }
}

class PdfMatchViewTest {
    private val hit = PdfSearchHit(0, listOf(PdfBox(0f, 0.5f, 1f, 0.6f)))

    @Test
    fun aPageNotLaidOutIsNotVisible() {
        assertFalse(pdfMatchView(hit, viewportHeight = 900, itemOffset = null, itemSize = 0).visible)
    }

    @Test
    fun readsTheMatchPositionOnThePage() {
        assertTrue(pdfMatchView(hit, viewportHeight = 900, itemOffset = 0, itemSize = 1000).visible)
        assertFalse(pdfMatchView(hit, viewportHeight = 500, itemOffset = 0, itemSize = 1000).visible)
    }

    @Test
    fun aMatchWithNoBoxNeverAsksToScroll() {
        assertTrue(pdfMatchView(PdfSearchHit(0, emptyList()), viewportHeight = 900, itemOffset = null, itemSize = 0).visible)
    }
}
