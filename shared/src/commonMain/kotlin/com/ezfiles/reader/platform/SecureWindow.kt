package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/**
 * While composed with [enabled], keeps the app's window out of screenshots,
 * screen recordings and the recent-apps preview (Android `FLAG_SECURE`).
 * Leaving composition, or [enabled] turning false, lifts it again.
 */
@Composable
expect fun SecureWindow(enabled: Boolean)
