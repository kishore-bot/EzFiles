package com.ezfiles.reader.presentation.theme

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RecordingHapticFeedback : HapticFeedback {
    val events = mutableListOf<HapticFeedbackType>()
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
}

class EzHapticsTest {
    @Test
    fun disabledFeedbackSkipsEveryEffectAndDoesNotReadClock() {
        val platform = RecordingHapticFeedback()
        val haptics = EzHaptics(platform, false) { error("Disabled feedback must not read the clock") }
        haptics.activate()
        haptics.select()
        haptics.step()
        haptics.toggle(true)
        haptics.toggle(false)
        haptics.threshold()
        haptics.confirm()
        haptics.reject()
        assertTrue(platform.events.isEmpty())
    }

    @Test
    fun constructionStaysQuietAndActivationRequiresAnExplicitCall() {
        val platform = RecordingHapticFeedback()
        val haptics = EzHaptics(platform, true)
        assertTrue(platform.events.isEmpty())
        haptics.activate()
        assertEquals(listOf(HapticFeedbackType.ContextClick), platform.events)
    }

    @Test
    fun continuousDragCoalescesTicksWithoutDelayingTheFirstOne() {
        val platform = RecordingHapticFeedback()
        var now = 0L
        val haptics = EzHaptics(platform, true) { now }
        for (time in listOf(0L, 1L, 10L, 20L, 79L, 80L, 90L, 159L, 160L)) {
            now = time
            haptics.step()
        }
        assertEquals(List(3) { HapticFeedbackType.SegmentFrequentTick }, platform.events)
    }

    @Test
    fun semanticResultsRemainImmediateBetweenDetentTicks() {
        val platform = RecordingHapticFeedback()
        val haptics = EzHaptics(platform, true) { 0L }
        haptics.step()
        haptics.confirm()
        haptics.reject()
        haptics.toggle(true)
        haptics.toggle(false)
        haptics.select()
        haptics.threshold()
        haptics.step()
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.Confirm,
            HapticFeedbackType.Reject, HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff,
            HapticFeedbackType.SegmentTick, HapticFeedbackType.GestureThresholdActivate), platform.events)
    }

    @Test
    fun aResetClockDoesNotSuppressFeedbackUntilOldTimeCatchesUp() {
        val platform = RecordingHapticFeedback()
        var now = 1000L
        val haptics = EzHaptics(platform, true) { now }
        haptics.step()
        now = 0L
        haptics.step()
        now = 20L
        haptics.step()
        assertEquals(2, platform.events.size)
    }

    @Test
    fun genericActivationRunsActionImmediatelyAndAnswersOnce() {
        val platform = RecordingHapticFeedback()
        val haptics = EzHaptics(platform, true)
        var actions = 0
        haptics.activateIfNoFeedback { actions++; assertTrue(platform.events.isEmpty()) }
        assertEquals(1, actions)
        assertEquals(listOf(HapticFeedbackType.ContextClick), platform.events)
    }

    @Test
    fun nestedControllersAndExplicitToggleProduceOneSemanticCue() {
        val platform = RecordingHapticFeedback()
        val outer = EzHaptics(platform, true)
        val inner = EzHaptics(platform, true)
        val toggle = EzHaptics(platform, true)
        outer.activateIfNoFeedback { inner.activateIfNoFeedback { toggle.toggle(true) } }
        assertEquals(listOf(HapticFeedbackType.ToggleOn), platform.events)
        platform.events.clear()
        outer.activateIfNoFeedback { inner.activateIfNoFeedback {} }
        assertEquals(listOf(HapticFeedbackType.ContextClick), platform.events)
    }

    @Test
    fun aThrottledDetentDoesNotBecomeAnUnthrottledGenericPulse() {
        val platform = RecordingHapticFeedback()
        val button = EzHaptics(platform, true)
        val detents = EzHaptics(platform, true) { 0L }
        button.activateIfNoFeedback { detents.step() }
        button.activateIfNoFeedback { detents.step() }
        assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), platform.events)
    }

    @Test
    fun explicitOffScopeAndFailedActionsSuppressGenericFallback() {
        val platform = RecordingHapticFeedback()
        val outer = EzHaptics(platform, true)
        val quiet = EzHaptics(platform, false)
        outer.activateIfNoFeedback { quiet.select() }
        assertFailsWith<IllegalArgumentException> { outer.activateIfNoFeedback { throw IllegalArgumentException("failed action") } }
        assertTrue(platform.events.isEmpty())
    }
}
