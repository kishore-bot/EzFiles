package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import com.ezfiles.reader.application.assistant.ReaderControl
import com.ezfiles.reader.application.assistant.ReaderPosition
import com.ezfiles.reader.application.assistant.ReaderTarget
import kotlinx.coroutines.async

val LocalReaderControl = staticCompositionLocalOf<ReaderControl?> { null }

/**
 * Lets the assistant drive this viewer while it is on screen. [perform] runs in the viewer's own
 * composition scope, so it can animate its list or pager like a tap would, and returns null when
 * done or a short failure reason.
 */
@Composable
fun ReaderControlTarget(
    operations: Set<String>,
    position: ReaderPosition,
    perform: suspend (operation: String, value: String?) -> String?,
) {
    val control = LocalReaderControl.current ?: return
    val scope = rememberCoroutineScope()
    val latestOperations by rememberUpdatedState(operations)
    val latestPosition by rememberUpdatedState(position)
    val latestPerform by rememberUpdatedState(perform)
    val target = remember {
        object : ReaderTarget {
            override val operations: Set<String> get() = latestOperations
            override val position: ReaderPosition get() = latestPosition

            override suspend fun perform(operation: String, value: String?): String? = scope.async {
                val reason = latestPerform(operation, value)
                // Let the viewer recompose, so the reported position is the new one.
                withFrameNanos { }
                withFrameNanos { }
                reason
            }.await()
        }
    }
    DisposableEffect(control, target) {
        val detach = control.attach(target)
        onDispose { detach() }
    }
}

private val SCROLL_OPERATIONS = setOf("scroll_down", "scroll_up", "scroll_top", "scroll_bottom")

/** Voice scrolling for a viewer that is one long list. */
@Composable
fun ListScrollControl(list: LazyListState) {
    ReaderControlTarget(SCROLL_OPERATIONS, ReaderPosition()) { operation, _ ->
        when (operation) {
            "scroll_top" -> list.animateScrollToItem(0)
            "scroll_bottom" -> list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            else -> list.animateScrollBy((if (operation == "scroll_down") 0.8f else -0.8f) * list.layoutInfo.viewportSize.height)
        }
        null
    }
}

/** "five", "5" or "5th" to 5; null when there is no number. */
internal fun spokenNumber(value: String?): Int? {
    val text = value?.trim()?.lowercase() ?: return null
    text.filter(Char::isDigit).takeIf(String::isNotEmpty)?.let { return it.toIntOrNull() }
    return NUMBER_WORDS.indexOf(text).takeIf { it > 0 }
}

private val NUMBER_WORDS = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
)
