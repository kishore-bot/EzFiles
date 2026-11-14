package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.IdenticalGroup
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.TidyReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantTidyActionsTest {
    private class Device(refs: List<DocumentRef>) : DeviceDocuments {
        override val supportsFolders = true
        override val files = MutableStateFlow(refs)
        override val folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
        override val scanning = MutableStateFlow(false)
        override val limitReached = MutableStateFlow(false)
        override suspend fun refresh() = Unit
        override suspend fun addFolder(uri: String) = false
        override suspend fun removeFolder(uri: String) = Unit
    }
    private val a = DocumentRef("content://test/a", "a.txt", 10, 1)
    private val b = DocumentRef("content://test/b", "b.txt", 10, 1)
    private fun report() = TidyReport(42, 2, listOf(IdenticalGroup(listOf(a, b), 10)), emptyList(), emptyList(), emptyList())

    @Test
    fun reportsRequireBothPermissionsBeforeLoading() = runTest {
        val access = AssistantAccess(AssistantMemoryStorage())
        var loads = 0
        val actions = AssistantTidyActions(access, Device(listOf(a, b)), { loads++; report() }, { true })
        assertFailsWith<AssistantAccessDenied> { actions.getReport() }
        access.setEnabled(true)
        assertFailsWith<AssistantAccessDenied> { actions.getReport() }
        assertEquals(0, loads)
        access.setContentAccess(true)
        val result = actions.getReport()
        assertEquals(2, result.validatedFileCount)
        assertEquals(1, result.duplicateGroups)
        assertEquals(10L, result.reclaimableBytes)
        assertFalse(result.partial)
    }

    @Test
    fun removedMembersAreWithheldEvenIfAnotherGrantRemainsReadable() = runTest {
        val access = AssistantAccess(AssistantMemoryStorage()).apply { setEnabled(true); setContentAccess(true) }
        val device = Device(listOf(a, b))
        val actions = AssistantTidyActions(access, device, { report() }, { ref ->
            if (ref == b) device.files.value = listOf(a)
            true
        })
        val result = actions.getReport()
        assertEquals(1, result.validatedFileCount)
        assertEquals(0, result.duplicateGroups)
        assertEquals(0L, result.reclaimableBytes)
        assertTrue(result.partial)
    }

    @Test
    fun missingReportsAndChangedVersionsCannotInventSavings() = runTest {
        val access = AssistantAccess(AssistantMemoryStorage()).apply { setEnabled(true); setContentAccess(true) }
        assertFalse(AssistantTidyActions(access, Device(listOf(a)), { null }, { true }).getReport().available)
        val result = AssistantTidyActions(access, Device(listOf(a.copy(modifiedAt = 2), b)), { report() }, { true }).getReport()
        assertEquals(0, result.duplicateGroups)
        assertEquals(0L, result.reclaimableBytes)
        assertTrue(result.partial)
    }

    @Test
    fun revocationDuringReportLoadingWithholdsTheSummary() = runTest {
        val access = AssistantAccess(AssistantMemoryStorage()).apply { setEnabled(true); setContentAccess(true) }
        val actions = AssistantTidyActions(access, Device(listOf(a, b)), { access.setEnabled(false); report() }, { true })
        assertFailsWith<AssistantAccessDenied> { actions.getReport() }
    }
}
