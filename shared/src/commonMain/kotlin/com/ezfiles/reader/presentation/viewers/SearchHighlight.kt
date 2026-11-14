package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.ezfiles.reader.presentation.SearchMatch

/**
 * The match the viewer wants scrolled into view, taken once by the text that
 * shows it. Being one-shot, scrolling back to that text later does not yank
 * the list to the match again.
 */
internal class MatchReveal {
    var pending by mutableStateOf<SearchMatch?>(null)
        private set

    fun request(match: SearchMatch) {
        pending = match
    }

    /** Only for [match], because a quick "next" may already have asked for another. */
    fun clear(match: SearchMatch) {
        if (pending == match) pending = null
    }
}

internal val LocalMatchReveal = compositionLocalOf<MatchReveal?> { null }

/**
 * Text that can hold the [current] match. A list item can be thousands of
 * lines tall and code scrolls sideways, so scrolling to the item is not enough:
 * this brings the match itself into view, through every scrolling ancestor.
 */
@Composable
internal fun MatchText(
    text: AnnotatedString,
    current: SearchMatch?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    if (current == null) {
        Text(text, modifier, style = style, softWrap = softWrap)
        return
    }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reveal = LocalMatchReveal.current
    val wanted = layout != null && reveal?.pending == current
    LaunchedEffect(wanted) {
        val result = layout
        if (!wanted || result == null) return@LaunchedEffect
        try {
            requester.bringIntoView(result.boundsOf(current))
        } finally {
            reveal.clear(current)
        }
    }
    Text(
        text,
        modifier.bringIntoViewRequester(requester),
        style = style,
        softWrap = softWrap,
        onTextLayout = { layout = it },
    )
}

/** The whole match if it fits on one line, otherwise its first character. */
private fun TextLayoutResult.boundsOf(match: SearchMatch): Rect {
    val last = layoutInput.text.length - 1
    if (last < 0) return Rect.Zero
    val first = getBoundingBox(match.start.coerceIn(0, last))
    val end = getBoundingBox((match.end - 1).coerceIn(0, last))
    return if (first.top == end.top) first.copy(right = end.right) else first
}
