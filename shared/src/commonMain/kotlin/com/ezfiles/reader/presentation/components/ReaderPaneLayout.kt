package com.ezfiles.reader.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window breakpoints, independent of a document viewport or a scrollable child's constraints. */
@Composable
fun isWideLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerDpSize
    return size.width.value.isFinite() && size.height.value.isFinite() &&
        size.width >= 600.dp && size.width > size.height
}

/** Keeps top-level chrome short on phone-height windows without reducing touch targets. */
@Composable
fun isShortWindow(): Boolean {
    val height = LocalWindowInfo.current.containerDpSize.height.value
    return height.isFinite() && height > 0f && height < 480f
}

/**
 * Keep the reading pane in one composition slot when controls move from below it to its side.
 * In particular, changing the arrangement must not recreate a WebView or lose its text selection.
 */
@Composable
fun ReaderPaneLayout(
    modifier: Modifier = Modifier,
    landscape: Boolean = isWideLandscape(),
    controlsVisible: Boolean = true,
    sideWidth: Dp = 176.dp,
    controls: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(content = content)
            Box { if (controlsVisible) controls() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val controlWidth = if (landscape && controlsVisible) sideWidth.roundToPx().coerceAtMost(width / 2) else width
        val control = children[1].measure(
            if (!controlsVisible) Constraints.fixed(0, 0)
            else if (landscape) Constraints.fixed(controlWidth, height)
            else Constraints(minWidth = width, maxWidth = width, maxHeight = height / 2),
        )
        val paneWidth = if (landscape) (width - control.width).coerceAtLeast(0) else width
        val paneHeight = if (landscape) height else (height - control.height).coerceAtLeast(0)
        val pane = children[0].measure(Constraints.fixed(paneWidth, paneHeight))
        layout(width, height) {
            pane.placeRelative(0, 0)
            if (landscape) control.placeRelative(paneWidth, 0) else control.placeRelative(0, paneHeight)
        }
    }
}
