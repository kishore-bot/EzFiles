package com.ezfiles.reader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickEnglishTest {
    @Test
    fun thePhonesOwnEnglishWinsElseAnyInstalledEnglish() {
        assertEquals("en-IN", pickEnglish("en-IN", listOf("en-US", "en-IN")))
        assertEquals("en-US", pickEnglish("en-IN", listOf("hi-IN", "en-GB", "en-US")))
        assertEquals("en-GB", pickEnglish("ta-IN", listOf("ta-IN", "en-GB")))
        assertNull(pickEnglish("en-IN", listOf("hi-IN", "ta-IN")))
        assertNull(pickEnglish("en-US", emptyList()))
    }
}
