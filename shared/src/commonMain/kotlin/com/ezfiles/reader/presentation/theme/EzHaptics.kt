package com.ezfiles.reader.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.time.TimeSource

/** The app's "Touch feedback" setting; the phone's own touch-feedback setting applies as well. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * The few moments Xfiles answers with a light touch, all using the system's
 * own crisp, short effects (no custom buzzes, so no vibration permission).
 *
 * One cue marks an intentional activation, changed choice, toggle, threshold
 * or result. Controls that already emit a semantic cue keep that single cue.
 * Scrolling, programmatic restoration and background work remain quiet.
 */
@Immutable
class EzHaptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
    private val nowMillis: () -> Long = monotonicHapticClock(),
) {
    private var lastStepMillis: Long? = null

    /** Explicit, opt-in feedback for intentional navigation or activation. */
    fun activate() = perform(HapticFeedbackType.ContextClick)

    /** A deliberate transition to another screen, distinct from an ordinary control click. */
    fun navigate() = perform(HapticFeedbackType.GestureEnd)

    /** A page or slide has settled after a user turn, a softer cue than a changed setting. */
    fun pageTurn() = perform(HapticFeedbackType.TextHandleMove)

    /** Main-thread event dispatch: nested controls and explicit semantic cues share one request serial. */
    fun activateIfNoFeedback(action: () -> Unit) {
        val before = HapticRequests.serial
        action()
        if (before == HapticRequests.serial) activate()
    }

    /** A command will report its eventual result; suppress the enclosing generic click cue. */
    fun deferFeedback(action: () -> Unit) {
        HapticRequests.serial++
        action()
    }

    internal fun selectIfNoFeedback(action: () -> Unit) {
        val before = HapticRequests.serial
        action()
        if (before == HapticRequests.serial) select()
    }

    /** A choice in a row of options: a tab, a filter chip, a segment. */
    fun select() = perform(HapticFeedbackType.SegmentTick)

    /** A changed drag detent, with at most one tick per 80 ms during continuous manipulation. */
    fun step() = perform(HapticFeedbackType.SegmentFrequentTick)

    /** Something switched on or off: a bookmark, a setting. */
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    fun toggleIfNoFeedback(on: Boolean, action: () -> Unit) {
        val before = HapticRequests.serial
        action()
        if (before == HapticRequests.serial) toggle(on)
    }

    /** A swipe went far enough to act when released. */
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)

    /** A task finished well: saved, exported, created. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** A task couldn't be done: a file that won't open, a failed save. */
    fun reject() = perform(HapticFeedbackType.Reject)

    private fun perform(type: HapticFeedbackType) {
        // A suppressed semantic request still prevents a generic fallback pulse.
        HapticRequests.serial++
        if (!enabled) return
        if (type == HapticFeedbackType.SegmentFrequentTick) {
            val now = nowMillis()
            val previous = lastStepMillis
            if (previous != null && now >= previous && now - previous < 80L) return
            lastStepMillis = now
        }
        feedback.performHapticFeedback(type)
    }
}

/** UI callbacks are synchronous on Main; the serial spans separate remembered controller instances. */
private object HapticRequests { var serial = 0L }

private fun monotonicHapticClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/** Plays [feedback] once when a result appears (and again only if [key] changes). */
@Composable
fun FeedbackOnAppear(key: Any?, feedback: EzHaptics.() -> Unit) {
    val haptics = rememberEzHaptics()
    LaunchedEffect(key) { haptics.feedback() }
}

@Composable
fun rememberEzHaptics(): EzHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { EzHaptics(feedback, enabled) }
}

/** Generic controls activate once unless their action already requested its own semantic feedback. */
@Composable
fun rememberHapticActivation(onClick: () -> Unit): () -> Unit {
    val haptics = rememberEzHaptics()
    return remember(onClick, haptics) { { haptics.activateIfNoFeedback(onClick) } }
}

/** Radio/tab choices answer only when changed, without duplicating a callback's own cue. */
@Composable
fun rememberHapticChoice(selected: Boolean, onClick: () -> Unit): () -> Unit {
    val haptics = rememberEzHaptics()
    return remember(selected, onClick, haptics) {
        { if (selected) onClick() else haptics.selectIfNoFeedback(onClick) }
    }
}
