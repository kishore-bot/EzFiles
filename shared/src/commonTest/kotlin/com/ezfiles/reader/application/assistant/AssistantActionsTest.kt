package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.data.BookmarksStore
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.LibraryFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class AssistantDevice(refs: List<DocumentRef>, override val supportsFolders: Boolean = true) : DeviceDocuments {
    override val folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    override val files = MutableStateFlow(refs)
    override val scanning = MutableStateFlow(false)
    override val limitReached = MutableStateFlow(false)
    var refreshes = 0
    var onRefresh: suspend () -> Unit = {}
    override suspend fun refresh() { refreshes++; onRefresh() }
    override suspend fun addFolder(uri: String) = false
    override suspend fun removeFolder(uri: String) = Unit
}

private class AssistantFixture(refs: List<DocumentRef>, supportsFolders: Boolean = true) {
    val device = AssistantDevice(refs, supportsFolders)
    val recents = RecentDocumentsStore(AssistantMemoryStorage()) { 1L }
    val bookmarks = BookmarksStore(AssistantMemoryStorage())
    val access = AssistantAccess(AssistantMemoryStorage())
    var validations = 0
    var textReads = 0
    var readable: suspend (DocumentRef) -> Boolean = { true }
    var text: suspend (DocumentRef, Int) -> String? = { _, _ -> "secret text" }
    val actions = AssistantActions(
        device, recents, access, ::id,
        validateRef = { validations++; readable(it) },
        documentText = object : DocumentText {
            override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? { textReads++; return text(ref, maxChars) }
        },
        bookmarks = bookmarks,
    )
    fun id(ref: DocumentRef): String = "opaque-${ref.uri.hashCode()}"
}

private fun assistantRef(index: Int, name: String = "report$index.txt") =
    DocumentRef("content://test/$index", name, sizeBytes = 10, identity = "same-name-and-size")

class AssistantActionsTest {
    @Test
    fun orderedSelectionsRefreshOnceAndRejectForeignOrRepeatedIds() = runTest {
        val refs = listOf(assistantRef(1), assistantRef(2))
        val f = AssistantFixture(refs)
        f.access.setEnabled(true)
        assertEquals(refs.reversed(), f.actions.resolveDocuments(refs.reversed().map(f::id)))
        assertEquals(1, f.device.refreshes)
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocuments(listOf(f.id(refs[0]), "foreign")) }
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocuments(List(21) { "id$it" }) }
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocuments(List(2) { f.id(refs[0]) }) }
    }

    @Test
    fun selectionsRecheckEarlierMembersAfterLaterProviderCalls() = runTest {
        val refs = listOf(assistantRef(1), assistantRef(2))
        val f = AssistantFixture(refs)
        f.access.setEnabled(true)
        f.readable = { ref -> if (ref == refs[1]) f.device.files.value = listOf(ref); true }
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocuments(refs.map(f::id)) }
    }

    @Test
    fun everyEntryPointDeniesDisabledAccessBeforeAnyProviderWork() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        assertFailsWith<AssistantAccessDenied> { f.actions.searchDocuments() }
        assertFailsWith<AssistantAccessDenied> { f.actions.listRecentDocuments() }
        assertFailsWith<AssistantAccessDenied> { f.actions.getDocumentInfo(f.id(ref)) }
        assertFailsWith<AssistantAccessDenied> { f.actions.resolveDocument(f.id(ref)) }
        assertFailsWith<AssistantAccessDenied> { f.actions.resolveDocuments(listOf(f.id(ref))) }
        assertFailsWith<AssistantAccessDenied> { f.actions.revalidateDocument(ref) }
        assertFailsWith<AssistantAccessDenied> { f.actions.listBookmarks() }
        assertFailsWith<AssistantAccessDenied> { f.actions.resolveBookmark("bookmark") }
        assertFailsWith<AssistantAccessDenied> { f.actions.getDocumentExcerpt(f.id(ref)) }
        assertFailsWith<AssistantAccessDenied> { f.actions.findTextInDocument(f.id(ref), "secret") }
        assertEquals(0, f.device.refreshes)
        assertEquals(0, f.validations)
        assertEquals(0, f.textReads)
    }

    @Test
    fun metadataSearchIsBoundedAndDoesNotReadContent() = runTest {
        val refs = (0 until 1000).map { assistantRef(it, "Annual REPORT $it.txt") }
        val f = AssistantFixture(refs)
        f.access.setEnabled(true)
        val results = f.actions.searchDocuments("annual report", limit = 500)
        assertEquals(20, results.documents.size)
        assertTrue(results.partial)
        assertEquals(21, f.validations)
        assertEquals(0, f.textReads)
        assertEquals(1, f.actions.searchDocuments(limit = -3).documents.size)
        assertFailsWith<IllegalArgumentException> { f.actions.searchDocuments("x".repeat(257)) }
    }

    @Test
    fun sameNameAndSizeDoNotCollapseDifferentDocuments() = runTest {
        val first = assistantRef(1, "report.txt")
        val second = assistantRef(2, "report.txt")
        val f = AssistantFixture(listOf(first, second))
        f.access.setEnabled(true)
        val results = f.actions.searchDocuments().documents
        assertEquals(2, results.size)
        assertNotEquals(results[0].id, results[1].id)
        assertEquals(second, f.actions.resolveDocument(results[1].id))
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocument(second.uri) }
    }

    @Test
    fun aFileNamedInsteadOfItsIdOpensOnlyWhenOneFileFits() = runTest {
        val notes = assistantRef(1, "random_notes.txt")
        val first = assistantRef(2, "report.txt")
        val second = assistantRef(3, "report.txt")
        val f = AssistantFixture(listOf(notes, first, second))
        f.access.setEnabled(true)
        assertEquals(notes, f.actions.resolveDocument("random_notes.txt"))
        assertEquals(notes, f.actions.resolveDocument("Random_Notes"))
        assertEquals(notes, f.actions.resolveDocument("random_note"))
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocument("report.txt") }
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocument("ra") }
    }

    @Test
    fun revokedGrantsAndRemovedScopeBlockResultsAndForegroundHandoffs() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        assertEquals(ref, f.actions.resolveDocument(f.id(ref)))
        f.readable = { false }
        assertTrue(f.actions.searchDocuments().documents.isEmpty())
        assertFailsWith<IllegalArgumentException> { f.actions.revalidateDocument(ref) }
        f.readable = { true }
        f.device.files.value = emptyList()
        assertFailsWith<IllegalArgumentException> { f.actions.revalidateDocument(ref) }
    }

    @Test
    fun revalidatingAKnownChangedVersionRejectsThePendingAction() = runTest {
        val original = assistantRef(1).copy(sizeBytes = 100, modifiedAt = 1000)
        for (changed in listOf(original.copy(sizeBytes = 101), original.copy(modifiedAt = 1001))) {
            val f = AssistantFixture(listOf(changed))
            f.access.setEnabled(true)
            assertFailsWith<IllegalArgumentException> { f.actions.revalidateDocument(original) }
        }
    }

    @Test
    fun revalidatingUnknownMetadataDoesNotInventAVersionMismatch() = runTest {
        val known = assistantRef(1).copy(sizeBytes = 100, modifiedAt = 1000)
        for ((original, current) in listOf(
            known.copy(sizeBytes = null, modifiedAt = null) to known,
            known to known.copy(sizeBytes = null, modifiedAt = null),
        )) {
            val f = AssistantFixture(listOf(current))
            f.access.setEnabled(true)
            assertEquals(current, f.actions.revalidateDocument(original))
        }
    }

    @Test
    fun revalidatingTheSameKnownVersionReturnsTheCurrentReference() = runTest {
        val original = assistantRef(1).copy(sizeBytes = 100, modifiedAt = 1000)
        val current = original.copy(displayName = "renamed-report.txt")
        val f = AssistantFixture(listOf(current))
        f.access.setEnabled(true)
        assertEquals(current, f.actions.revalidateDocument(original))
    }

    @Test
    fun manyRevokedFilesDoNotCauseUnboundedProviderQueries() = runTest {
        val f = AssistantFixture((0 until 1000).map { assistantRef(it) })
        f.access.setEnabled(true)
        f.readable = { false }
        val results = f.actions.searchDocuments()
        assertTrue(results.documents.isEmpty())
        assertTrue(results.partial)
        assertEquals(100, f.validations)
    }

    @Test
    fun slowProviderQueriesReturnValidatedPartialResultsWithinTheTotalBudget() = runTest {
        val f = AssistantFixture((0 until 100).map { assistantRef(it) })
        f.access.setEnabled(true)
        f.readable = { delay(700); true }
        val results = f.actions.searchDocuments()
        assertEquals(4, results.documents.size)
        assertEquals(5, f.validations)
        assertEquals(3000L, testScheduler.currentTime)
        assertTrue(results.partial)
    }

    @Test
    fun removingScopeDuringProviderValidationWithholdsMetadataForFoldersAndPickedRecents() = runTest {
        for (supportsFolders in listOf(true, false)) {
            val ref = assistantRef(1)
            val f = AssistantFixture(listOf(ref), supportsFolders)
            f.recents.record(ref, DocumentFormat.PlainText)
            f.access.setEnabled(true)
            f.readable = {
                delay(1)
                if (supportsFolders) f.device.files.value = emptyList() else f.recents.remove(ref.identity)
                true // Another platform grant still allows access; it is no longer in assistant scope.
            }
            assertTrue(f.actions.searchDocuments().documents.isEmpty())
            assertFailsWith<IllegalArgumentException> { f.actions.revalidateDocument(ref) }
        }
    }

    @Test
    fun anEarlierSearchResultRemovedDuringALaterValidationIsNotReturned() = runTest {
        val first = assistantRef(1)
        val second = assistantRef(2)
        val f = AssistantFixture(listOf(first, second))
        f.access.setEnabled(true)
        f.readable = { ref ->
            if (ref.uri == second.uri) {
                delay(1)
                f.device.files.value = listOf(second)
            }
            true
        }
        val results = f.actions.searchDocuments()
        assertEquals(listOf(f.id(second)), results.documents.map { it.id })
        assertTrue(results.partial)
    }

    @Test
    fun resolvingAFileRemovedDuringValidationRejectsTheHandoff() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        f.readable = {
            delay(1)
            f.device.files.value = emptyList()
            true
        }
        assertFailsWith<IllegalArgumentException> { f.actions.resolveDocument(f.id(ref)) }
    }

    @Test
    fun removingScopeDuringExtractionWithholdsTextEvenWhenOtherGrantsRemain() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        f.access.setContentAccess(true)
        f.text = { _, _ ->
            delay(1)
            f.device.files.value = emptyList()
            "must not be disclosed"
        }
        assertFailsWith<IllegalArgumentException> { f.actions.getDocumentExcerpt(f.id(ref)) }
        assertEquals(1, f.validations)
    }

    @Test
    fun recentsStayWithinFolderScopeAndIosUsesOnlyPreviouslyPickedFiles() = runTest {
        val inside = assistantRef(1)
        val outside = assistantRef(2).copy(identity = "outside")
        val android = AssistantFixture(listOf(inside))
        android.recents.record(outside, DocumentFormat.PlainText)
        android.recents.record(inside, DocumentFormat.PlainText)
        android.access.setEnabled(true)
        assertEquals(listOf(android.id(inside)), android.actions.listRecentDocuments().documents.map { it.id })
        val ios = AssistantFixture(emptyList(), supportsFolders = false)
        ios.recents.record(inside, DocumentFormat.PlainText)
        ios.access.setEnabled(true)
        assertEquals(listOf(ios.id(inside)), ios.actions.searchDocuments().documents.map { it.id })
        assertFailsWith<IllegalArgumentException> { ios.actions.resolveDocument(ios.id(outside)) }
    }

    @Test
    fun excerptsRequireSeparateConsentAndClampDisclosure() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        assertFailsWith<AssistantAccessDenied> { f.actions.getDocumentExcerpt(f.id(ref)) }
        assertEquals(0, f.textReads)
        f.access.setContentAccess(true)
        f.text = { _, maxChars -> assertEquals(4001, maxChars); "a".repeat(maxChars) }
        val excerpt = f.actions.getDocumentExcerpt(f.id(ref), maxChars = 1_000_000)
        assertEquals(4000, excerpt.text.length)
        assertTrue(excerpt.available)
        assertTrue(excerpt.truncated)
        f.text = { _, _ -> null }
        assertFalse(f.actions.getDocumentExcerpt(f.id(ref)).available)
    }

    @Test
    fun textSearchIsBoundedAndReportsIncompleteResults() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        f.access.setContentAccess(true)
        f.text = { _, maxChars -> assertEquals(200_001, maxChars); "Secret ".repeat(40) }
        val matches = f.actions.findTextInDocument(f.id(ref), "secret", limit = 100)
        assertEquals(20, matches.matches.size)
        assertEquals(0, matches.matches.first().offset)
        assertTrue(matches.partial)
        assertFailsWith<IllegalArgumentException> { f.actions.findTextInDocument(f.id(ref), "") }
    }

    @Test
    fun revocationCancelsAnActiveExtractionAndReturnsNoContent() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        f.access.setContentAccess(true)
        val reading = CompletableDeferred<Unit>()
        var cleanedUp = false
        f.text = { _, _ ->
            reading.complete(Unit)
            try { awaitCancellation() } finally { cleanedUp = true }
        }
        supervisorScope {
            val request = async { f.actions.getDocumentExcerpt(f.id(ref)) }
            reading.await()
            f.access.setEnabled(false)
            assertFailsWith<AssistantAccessDenied> { request.await() }
        }
        assertTrue(cleanedUp)
    }

    @Test
    fun grantRevokedDuringExtractionWithholdsTheReadText() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.access.setEnabled(true)
        f.access.setContentAccess(true)
        f.text = { _, _ -> f.readable = { false }; "must not be returned" }
        assertFailsWith<IllegalArgumentException> { f.actions.getDocumentExcerpt(f.id(ref)) }
    }

    @Test
    fun slowRefreshReturnsExplicitlyPartialValidatedMetadata() = runTest {
        val f = AssistantFixture(listOf(assistantRef(1)))
        f.access.setEnabled(true)
        f.device.onRefresh = { delay(6000) }
        val results = f.actions.searchDocuments()
        assertEquals(1, results.documents.size)
        assertTrue(results.partial)
    }

    @Test
    fun bookmarkMetadataOmitsSavedLabelsAndSnippetsAndResolvesCurrentScope() = runTest {
        val ref = assistantRef(1)
        val f = AssistantFixture(listOf(ref))
        f.bookmarks.add(Bookmark("bookmark", ref, DocumentFormat.Pdf, BookmarkLocation.Page(2), "secret label", "secret snippet"))
        f.access.setEnabled(true)
        val bookmark = f.actions.listBookmarks().single()
        assertEquals(3, bookmark.pageNumber)
        assertFalse(bookmark.toString().contains("secret"))
        assertEquals(BookmarkLocation.Page(2), f.actions.resolveBookmark("bookmark").location)
        f.device.files.value = emptyList()
        assertTrue(f.actions.listBookmarks().isEmpty())
        assertFailsWith<IllegalArgumentException> { f.actions.resolveBookmark("bookmark") }
    }
}
