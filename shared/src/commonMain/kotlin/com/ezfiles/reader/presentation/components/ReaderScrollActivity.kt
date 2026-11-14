package com.ezfiles.reader.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** Native and Compose viewers can report scrolling without changing their gesture handling. */
val LocalReaderScrollActivity = staticCompositionLocalOf<() -> Unit> { {} }

/** Only visibility changes recompose; scroll frames update a plain monotonic timestamp. */
internal class ReaderControlVisibility {
    private val clock = TimeSource.Monotonic.markNow()
    private var lastScrollMillis = 0L
    var visible by mutableStateOf(true)
        private set

    fun scrolled() {
        lastScrollMillis = clock.elapsedNow().inWholeMilliseconds
        visible = false
    }

    fun tapped() {
        visible = true
    }

    suspend fun revealAfterIdle() {
        while (!visible) {
            val remaining = 1_100L - (clock.elapsedNow().inWholeMilliseconds - lastScrollMillis)
            if (remaining > 0) delay(remaining) else visible = true
        }
    }
}
