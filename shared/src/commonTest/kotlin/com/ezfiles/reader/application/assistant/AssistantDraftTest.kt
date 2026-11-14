package com.ezfiles.reader.application.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssistantDraftTest {
    @Test
    fun draftsAreBoundedAndTitlesCannotBePaths() {
        assertEquals(16_000, AssistantDraft("x".repeat(16_000), "Note.txt", 1).text.length)
        assertFailsWith<IllegalArgumentException> { AssistantDraft("x".repeat(16_001), "Note") }
        assertFailsWith<IllegalArgumentException> { AssistantDraft("text", "x".repeat(81)) }
        for (title in listOf("../Note", "file:///Note", "..", "a\\b", "a\nb")) {
            assertFailsWith<IllegalArgumentException> { AssistantDraft("text", title) }
        }
        assertFailsWith<IllegalArgumentException> { AssistantDraft("text", "Note", -1) }
    }
}
