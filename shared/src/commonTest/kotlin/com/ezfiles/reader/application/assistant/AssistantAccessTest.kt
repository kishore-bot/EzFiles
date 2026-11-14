package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.data.KeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AssistantMemoryStorage : KeyValueStorage {
    val values = mutableMapOf<String, String>()
    var writes = 0
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) {
        writes++
        if (value == null) values.remove(key) else values[key] = value
    }
}

class AssistantAccessTest {
    @Test
    fun missingOrCorruptPolicyDeniesAccess() {
        for (stored in listOf(null, "true", "true|true|bad", "true|true|-1", "yes|true|3")) {
            val storage = AssistantMemoryStorage()
            stored?.let { storage.values["assistant_access_v1"] = it }
            val access = AssistantAccess(storage)
            assertFalse(access.enabled.value)
            assertFalse(access.contentAccess.value)
            assertFailsWith<AssistantAccessDenied> { access.captureAuthorization() }
            assertFailsWith<AssistantAccessDenied> { access.setContentAccess(true) }
        }
    }

    @Test
    fun offThenOnInvalidatesOldRequestsAcrossRestart() {
        val storage = AssistantMemoryStorage()
        val access = AssistantAccess(storage)
        access.setEnabled(true)
        val token = access.captureAuthorization()
        access.setEnabled(false)
        access.setEnabled(true)
        assertFailsWith<AssistantAccessDenied> { access.recheckAuthorization(token) }
        val restored = AssistantAccess(storage)
        assertTrue(restored.enabled.value)
        assertEquals(access.generation, restored.captureAuthorization())
        assertFailsWith<AssistantAccessDenied> { restored.recheckAuthorization(token) }
    }

    @Test
    fun contentConsentIsSeparateAndRevokedWithMasterSwitch() {
        val access = AssistantAccess(AssistantMemoryStorage())
        access.setEnabled(true)
        assertFalse(access.contentAccess.value)
        val metadataToken = access.captureAuthorization()
        access.setContentAccess(true)
        assertTrue(access.contentAccess.value)
        assertFailsWith<AssistantAccessDenied> { access.recheckAuthorization(metadataToken) }
        access.setEnabled(false)
        assertFalse(access.contentAccess.value)
        access.setEnabled(true)
        assertFalse(access.contentAccess.value)
    }

    @Test
    fun unchangedSettingsDoNotRotateOrWritePolicy() {
        val storage = AssistantMemoryStorage()
        val access = AssistantAccess(storage)
        access.setEnabled(false)
        access.setContentAccess(false)
        assertEquals(0, storage.writes)
        access.setEnabled(true)
        val token = access.captureAuthorization()
        access.setEnabled(true)
        assertEquals(token, access.captureAuthorization())
        assertEquals(1, storage.writes)
    }
}
