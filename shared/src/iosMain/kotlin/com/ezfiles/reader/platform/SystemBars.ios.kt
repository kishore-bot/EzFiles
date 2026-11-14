package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

// iOS is parked: the status bar keeps the system's style.
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean, appDark: Boolean?) = Unit
