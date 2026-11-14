package com.ezfiles.reader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CloudCrashSanitizationTest {
    @Test
    fun forwarding_copies_and_scrubs_causes_and_suppressed_without_mutating_original() {
        val original = IllegalStateException("Failed content://provider/document/Private Resume.pdf: denied", IllegalArgumentException("user@example.com"))
        original.addSuppressed(RuntimeException("Document text 'private paragraph'"))
        val forwarded = scrubCrashThrowable(original)
        val text = forwarded.stackTraceToString()
        for (secret in listOf("content://", "Private Resume", "user@example.com", "private paragraph")) assertFalse(secret in text)
        assertNotSame(original, forwarded)
        assertTrue("Private Resume" in original.message.orEmpty())
        assertEquals(original.stackTrace.toList(), forwarded.stackTrace.toList())
    }

    @Test
    fun cyclic_exception_graph_is_bounded() {
        val first = RuntimeException("one")
        val second = RuntimeException("two", first)
        first.initCause(second)
        val forwarded = scrubCrashThrowable(first)
        assertTrue("Further crash details omitted" in forwarded.stackTraceToString())
        assertTrue(forwarded.stackTraceToString().length < 64 * 1024)
    }
}
