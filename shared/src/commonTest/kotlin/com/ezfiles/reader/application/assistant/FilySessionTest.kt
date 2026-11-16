package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.data.BookmarksStore
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.domain.ContentHit
import com.ezfiles.reader.domain.ContentSearch
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.IndexProgress
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.LibraryFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FilyDevice(refs: List<DocumentRef>) : DeviceDocuments {
    override val supportsFolders = true
    override val folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    override val files = MutableStateFlow(refs)
    override val scanning = MutableStateFlow(false)
    override val limitReached = MutableStateFlow(false)
    override suspend fun refresh() = Unit
    override suspend fun addFolder(uri: String) = false
    override suspend fun removeFolder(uri: String) = Unit
}

private class FilyFixture(refs: List<DocumentRef>) {
    val access = AssistantAccess(AssistantMemoryStorage()).apply { setEnabled(true) }
    val navigation = AssistantNavigation()
    val actions = AssistantActions(
        FilyDevice(refs),
        RecentDocumentsStore(AssistantMemoryStorage()) { 1L },
        access,
        opaqueId = { "id-${it.displayName}" },
        validateRef = { true },
        documentText = object : DocumentText {
            override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? = "Amount due: INR 2812."
        },
        bookmarks = BookmarksStore(AssistantMemoryStorage()),
    )
    val tools = ToolExecutor(actions, navigation)
}

private fun doc(name: String, modified: Long? = null) = DocumentRef("content://t/$name", name, sizeBytes = 10, modifiedAt = modified, identity = name)

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

class FilySessionTest {
    @Test
    fun promptFollowsTheTrainingLayout() {
        val system = FilyPrompt.system(listOf("openDocument"))
        assertTrue(system.startsWith(FilyPrompt.POLICY + "\nTOOLS={\"openDocument\":{"))
        assertTrue(FilyPrompt.render(system, emptyList()).startsWith(FilyPrompt.STABLE_PREFIX))
        val state = FilyPrompt.appState(true, false, "2026-11-16", FilyScreen("d1", pageCount = 12, readerCapabilities = listOf("next_page")))
        assertEquals(
            """APP_STATE={"enabled":true,"contentAllowed":false,"today":"2026-11-16","authorizedIds":["d1"],"selectedDocumentId":"d1","pageCount":12,"readerCapabilities":["next_page"]}""",
            state,
        )
        val prompt = FilyPrompt.render("S", listOf(FilyTurn(FilyRole.User, "hi"), FilyTurn(FilyRole.Tool, "{}")))
        assertEquals("<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>tool\n{}<|im_end|>\n<|im_start|>assistant\n", prompt)
    }

    @Test
    fun selectorOffersFewRelevantTools() {
        val all = FilyPrompt.toolNames
        val library = ToolSelector.select("find my latest CV", FilyScreen(), all)
        assertTrue("searchLibrary" in library)
        assertTrue(library.size in 2..5)
        assertTrue("controlReader" !in ToolSelector.select("next page", FilyScreen(), all))
        assertTrue("controlReader" in ToolSelector.select("next page", FilyScreen("d", readerCapabilities = listOf("next_page")), all))
    }

    @Test
    fun latestResumeUsesCategoryAndModifiedTime() = runTest {
        val f = FilyFixture(listOf(doc("old-resume.pdf", 1L), doc("notes.txt", 9L), doc("Kishore_CV.pdf", 5L)))
        val result = json(f.tools.execute("searchLibrary", json("""{"query":"","category":"resume","sort":"modified_desc"}""")))
        val names = result["documents"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Kishore_CV.pdf", "old-resume.pdf"), names)
    }

    @Test
    fun openingGoesThroughAssistantNavigation() = runTest {
        val f = FilyFixture(listOf(doc("resume.pdf")))
        val result = json(f.tools.execute("openDocument", json("""{"documentId":"id-resume.pdf"}""")))
        assertEquals("opened", result["status"]!!.jsonPrimitive.content)
        assertEquals("resume.pdf", f.navigation.pending.value?.ref?.displayName)
        assertEquals(AssistantAction.Open, f.navigation.pending.value?.action)
    }

    @Test
    fun actionsOnlyOpenReviewScreens() = runTest {
        val f = FilyFixture(listOf(doc("a.pdf"), doc("b.pdf"), doc("notes.txt")))
        assertEquals("chooser_opened", json(f.tools.execute("prepareShareDocument", json("""{"documentId":"id-a.pdf"}""")))["status"]!!.jsonPrimitive.content)
        assertEquals(AssistantAction.Share, f.navigation.pending.value?.action)

        json(f.tools.execute("mergePdfDocuments", json("""{"documentIds":["id-b.pdf","id-a.pdf"]}""")))
        val merge = f.navigation.pending.value!!
        assertEquals("Merge", merge.pdfTool)
        assertEquals(listOf("b.pdf", "a.pdf"), merge.documents.map { it.displayName })

        assertEquals("not_available", json(f.tools.execute("mergePdfDocuments", json("""{"documentIds":["id-notes.txt"]}""")))["reason"]!!.jsonPrimitive.content)
        assertEquals("not_available", json(f.tools.execute("createTextDocument", json("""{"text":"hi","title":"../x"}""")))["reason"]!!.jsonPrimitive.content)

        assertEquals("draft_ready", json(f.tools.execute("createTextDocument", json("""{"text":"Call Asha","title":"Follow-up"}""")))["status"]!!.jsonPrimitive.content)
        assertEquals("Call Asha", f.navigation.pending.value?.draftText)
        assertEquals("ScanToPdf", json(f.tools.execute("openScanner", null)).let { f.navigation.pending.value?.tool })
    }

    @Test
    fun theModelSeesShortIdsThatResolveBack() = runTest {
        val f = FilyFixture(listOf(doc("a-resume.pdf"), doc("b-resume.pdf")))
        val found = json(f.tools.execute("searchLibrary", json("""{"query":"resume","sort":"relevance"}""")))
        val ids = found["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("doc_1", "doc_2"), ids)
        assertEquals("doc_2", json(f.tools.execute("openDocument", json("""{"documentId":"doc_2"}""")))["documentId"]!!.jsonPrimitive.content)
        assertEquals("b-resume.pdf", f.navigation.pending.value?.ref?.displayName)
        assertEquals("not_available", json(f.tools.execute("openDocument", json("""{"documentId":"doc_9"}""")))["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun aQuestionAboutContentFallsBackToTheContentIndex() = runTest {
        val f = FilyFixture(listOf(doc("contract.pdf"), doc("notes.txt")))
        val withIndex = ToolExecutor(
            AssistantActions(
                FilyDevice(listOf(doc("contract.pdf"), doc("notes.txt"))),
                RecentDocumentsStore(AssistantMemoryStorage()) { 1L },
                f.access, opaqueId = { "id-${it.displayName}" }, validateRef = { true },
                documentText = object : DocumentText { override suspend fun plainText(ref: DocumentRef, maxChars: Int): String? = null },
                bookmarks = BookmarksStore(AssistantMemoryStorage()),
                contentSearch = object : ContentSearch {
                    override val progress = MutableStateFlow(IndexProgress(0, 0))
                    override fun setActive(active: Boolean) = Unit
                    override suspend fun search(query: String, limit: Int) =
                        if ("48,000" in query) listOf(ContentHit(doc("contract.pdf"), "INR 48,000", emptyList(), 2)) else emptyList()
                    override suspend fun clear() = Unit
                },
            ),
            AssistantNavigation(),
        )
        val search = json("""{"query":"48,000","sort":"relevance"}""")
        assertEquals("[]", json(withIndex.execute("searchLibrary", search))["documents"].toString())
        f.access.setContentAccess(true)
        val found = json(withIndex.execute("searchLibrary", search))["documents"]!!.jsonArray
        assertEquals("contract.pdf", found.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun failuresComeBackAsJsonNotExceptions() = runTest {
        val f = FilyFixture(listOf(doc("a.pdf")))
        assertEquals("not_available", json(f.tools.execute("openDocument", json("""{"documentId":"foreign"}""")))["reason"]!!.jsonPrimitive.content)
        assertEquals("missing_arguments", json(f.tools.execute("openDocument", null))["reason"]!!.jsonPrimitive.content)
        f.access.setEnabled(false)
        assertEquals("access_off", json(f.tools.execute("searchLibrary", json("""{"query":"a","sort":"relevance"}""")))["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultIsFedBackForAGroundedReply() = runTest {
        val f = FilyFixture(listOf(doc("resume.pdf")))
        val prompts = ArrayList<String>()
        val replies = ArrayDeque(listOf(
            """{"kind":"tool","name":"searchLibrary","arguments":{"query":"resume","sort":"relevance"}}""",
            """{"kind":"reply","text":"I found resume.pdf.","citations":["id-resume.pdf"]}""",
        ))
        val session = FilySession({ prompt, _ -> prompts += prompt; replies.removeFirst() }, f.tools, f.access, today = { "2026-11-16" })
        assertEquals("I found resume.pdf.", session.ask("find my resume"))
        assertTrue(prompts[1].contains("<|im_start|>tool\n{\"documents\":[{\"id\":\"doc_1\",\"name\":\"resume.pdf\"}],\"partial\":false}<|im_end|>"))
        assertTrue(prompts[1].contains("<|im_start|>assistant\n{\"kind\":\"tool\",\"name\":\"searchLibrary\""))
    }

    @Test
    fun theNthFileOnScreenOpensWithoutTheModel() = runTest {
        val f = FilyFixture(listOf(doc("notes.txt"), doc("resume.pdf")))
        val session = FilySession({ _, _ -> error("the model must not be asked") }, f.tools, f.access, today = { "2026-11-16" })
        session.listedFiles = { listOf(ListedFile("id-notes.txt", "notes.txt"), ListedFile("id-resume.pdf", "resume.pdf")) }
        assertEquals("open the first file you see on screen",
            session.bestTranscript(listOf("open the first five UC", "open the first file you see on screen")))
        assertEquals("Opening notes.txt.", session.ask("open the first file UC on screen"))
        assertEquals("notes.txt", f.navigation.pending.value?.ref?.displayName)
        assertEquals("Opening resume.pdf.", session.ask("open the second one"))
        assertEquals("Opening resume.pdf.", session.ask("open the last file"))
        assertEquals("There are only 2 files on screen.", session.ask("open the fifth file"))
    }

    @Test
    fun closeLeavesTheOpenFileWithoutTheModel() = runTest {
        val f = FilyFixture(listOf(doc("notes.txt")))
        var closed = 0
        val session = FilySession({ _, _ -> error("the model must not be asked") }, f.tools, f.access, today = { "2026-11-16" })
        session.closeDocument = { closed++ }
        assertEquals("No file is open.", session.ask("close this document"))
        session.openDocumentId = "id-notes.txt"
        assertEquals("Closed.", session.ask("I mean close this document"))
        assertEquals("Closed.", session.ask("exit"))
        assertEquals("Closed.", session.ask("go back to the library"))
        assertEquals(2 + 1, closed)
        assertFalse(QuickCommands.isClose("close the deal with Aster Labs"))
    }

    @Test
    fun listPositionsAreOnlyForFiles() {
        assertEquals(0, QuickCommands.listPosition("open the first file on screen"))
        assertEquals(0, QuickCommands.listPosition("open the first file UC on screen"))
        assertEquals(1, QuickCommands.listPosition("open the second one"))
        assertEquals(2, QuickCommands.listPosition("open number three"))
        assertEquals(2, QuickCommands.listPosition("open the 3rd file"))
        assertEquals(-1, QuickCommands.listPosition("open the last one"))
        assertEquals(null, QuickCommands.listPosition("open the first page"))
        assertEquals(null, QuickCommands.listPosition("open invoice number 3"))
        assertEquals(null, QuickCommands.listPosition("open the first contract about payments"))
        assertEquals(null, QuickCommands.listPosition("find my latest CV"))
    }

    @Test
    fun toolsTheModelWasNotOfferedAreRefused() = runTest {
        val f = FilyFixture(listOf(doc("resume.pdf")))
        val replies = ArrayDeque(listOf(
            """{"kind":"tool","name":"openBookmark","arguments":{"bookmarkId":"b"}}""",
            """{"kind":"reply","text":"I can't do that here.","citations":[]}""",
        ))
        val session = FilySession({ _, _ -> replies.removeFirst() }, f.tools, f.access, today = { "2026-11-16" })
        assertEquals("I can't do that here.", session.ask("hello there"))
        assertEquals(null, f.navigation.pending.value)
    }

    @Test
    fun theGuessThatNamesAFileWinsOverTheRecognizersFirstChoice() {
        val f = FilyFixture(emptyList())
        val session = FilySession({ _, _ -> "" }, f.tools, f.access, today = { "2026-11-16" },
            fileNames = { listOf("Kishore_K_CV.pdf", "Budget-2026.xlsx") })
        assertEquals("open kishore cv", session.bestTranscript(listOf("open kishore csp", "open kishore cv")))
        assertEquals("hello there", session.bestTranscript(listOf("hello there", "yellow there")))
        assertTrue("Kishore" in session.speechHints() && "Budget" in session.speechHints() && "2026" !in session.speechHints())
        f.access.setEnabled(false)
        assertTrue("Kishore" !in session.speechHints())
    }

    @Test
    fun isoDatesRoundTrip() {
        val millis = parseIsoDate("2026-11-16")!!
        assertEquals("2026-11-16", isoDate(millis))
        assertEquals("1969-12-31", isoDate(-1))
        assertEquals(null, parseIsoDate("16/11/2026"))
    }
}
