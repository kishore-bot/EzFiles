package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/**
 * Makes the status and navigation bar icons readable on the app's theme: dark icons on light, light on dark.
 * [appDark] is the user's own theme choice (null: follow the system); where the platform supports it,
 * it's handed to the system too, so the launch screen matches the app next time.
 */
@Composable
expect fun SystemBarAppearance(darkTheme: Boolean, appDark: Boolean?)
