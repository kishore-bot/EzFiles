package com.ezfiles.reader.platform

import android.app.Activity
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarAppearance(darkTheme: Boolean, appDark: Boolean?) {
    val view = LocalView.current
    val activity: Activity? = LocalActivity.current
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    LaunchedEffect(appDark) {
        val context = activity?.applicationContext ?: return@LaunchedEffect
        // The system doesn't report these back, so remember what was last set and change only on a new choice.
        val prefs = context.getSharedPreferences("system_ui", Context.MODE_PRIVATE)
        val choice = when (appDark) {
            null -> "auto"
            true -> "dark"
            false -> "light"
        }
        if (prefs.getString("theme_applied", "auto") == choice) return@LaunchedEffect
        prefs.edit().putString("theme_applied", choice).apply()
        LauncherIcons.show(context, choice)
        // Android 12+ keeps a per-app night mode, which also picks the splash screen's light or dark
        // variant. A change reconfigures the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modes = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            modes?.setApplicationNightMode(
                when (appDark) {
                    null -> UiModeManager.MODE_NIGHT_AUTO
                    true -> UiModeManager.MODE_NIGHT_YES
                    false -> UiModeManager.MODE_NIGHT_NO
                },
            )
        }
    }
}

/**
 * The launcher icon follows the theme chosen in EzFiles: one of three launcher
 * aliases (see the manifest) is enabled, the others disabled. Some launchers
 * take a moment to redraw, and may drop a home-screen shortcut to the old one.
 */
private object LauncherIcons {
    private val aliases = mapOf("auto" to "LauncherAuto", "light" to "LauncherLight", "dark" to "LauncherDark")

    fun show(context: Context, choice: String) {
        val packages = context.packageManager
        // Enable the new one first, so there is never a moment without a launcher entry.
        val ordered = aliases.entries.sortedBy { it.key != choice }
        for ((key, alias) in ordered) {
            val component = ComponentName(context.packageName, "com.ezfiles.android.$alias")
            val state = if (key == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packages.getComponentEnabledSetting(component) != state) {
                packages.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
