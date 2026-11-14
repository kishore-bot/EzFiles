package com.ezfiles.reader.presentation.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Apply after the full-screen background so it still draws edge to edge.
 * Screens retain their vertical bar padding; consumed cutout/side insets are not added twice.
 */
@Composable
fun Modifier.appSafeArea(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .displayCutoutPadding()
