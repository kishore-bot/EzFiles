package com.ezfiles.reader.presentation.theme

import com.ezfiles.reader.data.KeyValueStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's theme setting: follow the system, or always light or dark. */
enum class ThemePreference(val label: String, internal val stored: String) {
    System("System", "system"),
    Light("Light", "light"),
    Dark("Dark", "dark"),
    ;

    /** Whether the dark palette applies, given whether the system is dark now. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** Unknown or missing values (an older or newer app wrote them) fall back to System. */
        fun fromStored(value: String?): ThemePreference = entries.firstOrNull { it.stored == value } ?: System
    }
}

/** The theme setting, persisted in the app's small key-value storage. */
internal class ThemeSettings(private val storage: KeyValueStorage) {
    private val state = MutableStateFlow(ThemePreference.fromStored(storage.get(KEY)))
    val preference: StateFlow<ThemePreference> = state.asStateFlow()

    fun set(value: ThemePreference) {
        if (value == state.value) return
        state.value = value
        storage.put(KEY, value.stored)
    }

    private val hapticsState = MutableStateFlow(storage.get(HAPTICS_KEY) != "off")

    /** Light touch feedback on choices, toggles and results; on unless the user turns it off. */
    val haptics: StateFlow<Boolean> = hapticsState.asStateFlow()

    fun setHaptics(on: Boolean) {
        if (on == hapticsState.value) return
        hapticsState.value = on
        storage.put(HAPTICS_KEY, if (on) "on" else "off")
    }

    private companion object {
        const val KEY = "app_theme_v1"
        const val HAPTICS_KEY = "app_haptics_v1"
    }
}
