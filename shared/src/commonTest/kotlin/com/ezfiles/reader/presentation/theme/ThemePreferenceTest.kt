package com.ezfiles.reader.presentation.theme

import com.ezfiles.reader.data.KeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemoryStorage(initial: Map<String, String> = emptyMap()) : KeyValueStorage {
    val values = initial.toMutableMap()
    var writes = 0

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        writes++
        if (value == null) values.remove(key) else values[key] = value
    }
}

class ThemePreferenceTest {
    @Test
    fun systemFollowsTheSystem() {
        assertTrue(ThemePreference.System.isDark(systemDark = true))
        assertFalse(ThemePreference.System.isDark(systemDark = false))
    }

    @Test
    fun lightAndDarkIgnoreTheSystem() {
        assertFalse(ThemePreference.Light.isDark(systemDark = true))
        assertTrue(ThemePreference.Dark.isDark(systemDark = false))
    }

    @Test
    fun storedValuesRoundTrip() {
        for (preference in ThemePreference.entries) {
            assertEquals(preference, ThemePreference.fromStored(preference.stored))
        }
    }

    @Test
    fun missingOrUnknownValuesFallBackToSystem() {
        assertEquals(ThemePreference.System, ThemePreference.fromStored(null))
        assertEquals(ThemePreference.System, ThemePreference.fromStored("sepia"))
        assertEquals(ThemePreference.System, ThemePreference.fromStored(""))
    }

    @Test
    fun settingsStartFromStorageAndPersistChanges() {
        val storage = MemoryStorage(mapOf("app_theme_v1" to "dark"))
        val settings = ThemeSettings(storage)
        assertEquals(ThemePreference.Dark, settings.preference.value)

        settings.set(ThemePreference.Light)
        assertEquals(ThemePreference.Light, settings.preference.value)
        assertEquals("light", storage.values["app_theme_v1"])
        // A later launch reads it back.
        assertEquals(ThemePreference.Light, ThemeSettings(storage).preference.value)
    }

    @Test
    fun settingTheSameValueDoesNotWrite() {
        val storage = MemoryStorage()
        val settings = ThemeSettings(storage)
        settings.set(ThemePreference.System)
        assertEquals(0, storage.writes)
    }

    @Test
    fun glassAlphaScalesBetweenThePaletteBounds() {
        assertEquals(LightPalette.glassAlphaMin, LightPalette.glassAlpha(0f))
        assertEquals(LightPalette.glassAlphaMax, LightPalette.glassAlpha(1f))
        assertEquals(DarkPalette.glassAlphaMax, DarkPalette.glassAlpha(3f))
        assertTrue(DarkPalette.glassAlpha(0.5f) in DarkPalette.glassAlphaMin..DarkPalette.glassAlphaMax)
    }

    @Test
    fun touchFeedbackIsOnByDefaultAndPersistsWhenTurnedOff() {
        val storage = MemoryStorage()
        val settings = ThemeSettings(storage)
        assertTrue(settings.haptics.value)

        settings.setHaptics(false)
        assertEquals(false, settings.haptics.value)
        assertEquals(false, ThemeSettings(storage).haptics.value)

        settings.setHaptics(true)
        assertTrue(ThemeSettings(storage).haptics.value)
    }
}
