package com.ezfiles.reader.application.assistant

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A file listed on screen: its assistant ID and the name shown. */
data class ListedFile(val documentId: String, val name: String)

/**
 * One spoken conversation with Fily: the model decides, [ToolExecutor] acts, and the tool's JSON
 * goes back to the model for a grounded reply. Exact screen commands skip the model. Not
 * thread-safe; callers ask one question at a time.
 */
class FilySession(
    /** Continues a rendered prompt, constrained by a GBNF grammar; [ModelManager.complete] in the app. */
    private val complete: suspend (prompt: String, grammar: String) -> String,
    private val tools: ToolExecutor,
    private val access: AssistantAccess,
    private val today: () -> String,
    private val reader: ReaderControl = ReaderControl(),
    /** Names of the library's files; only used, on the device, to help speech recognition. */
    private val fileNames: () -> List<String> = { emptyList() },
) {
    /** Completed exchanges, oldest first; each starts with the user's turn. */
    private val history = ArrayList<List<FilyTurn>>()

    /** The assistant ID of the document on screen, set by the UI; null in the library. */
    var openDocumentId: String? = null

    /** The files listed on the library screen, top first, set by the UI: for "open the second file". */
    var listedFiles: () -> List<ListedFile> = { emptyList() }

    /** Leaves the open file as the back button does (unsaved-changes prompt included); set by the UI. */
    var closeDocument: (() -> Unit)? = null

    /** What is on screen, reported to the model as APP_STATE. */
    val screen: FilyScreen
        get() {
            val document = openDocumentId?.let(tools::alias)
            return FilyScreen(
                selectedDocumentId = document,
                pageCount = reader.target.value?.position?.pageCount.takeIf { document != null },
                readerCapabilities = if (document != null) reader.operations else emptyList(),
            )
        }

    /** Returns the text to show and speak. */
    suspend fun ask(request: String): String {
        val screen = screen
        QuickCommands.parse(request, screen.readerCapabilities, screen.pageCount)?.let { return quick(request, it) }
        val close = closeDocument
        if (close != null && QuickCommands.isClose(request)) {
            if (screen.selectedDocumentId == null) return "No file is open."
            close()
            remember(listOf(
                FilyTurn(FilyRole.User, request),
                FilyTurn(FilyRole.Assistant, buildJsonObject { put("kind", "reply"); put("text", "Closed."); put("citations", buildJsonArray { }) }.toString()),
            ))
            return "Closed."
        }
        if (screen.selectedDocumentId == null) QuickCommands.listPosition(request)?.let { position ->
            val listed = listedFiles()
            if (listed.isNotEmpty()) return openListed(request, listed, position)
        }
        val toolNames = ToolSelector.select(request, screen, tools.supported)
        val system = FilyPrompt.system(toolNames)
        val grammar = FilyGrammar.forTools(toolNames)
        tools.question = request
        val state = FilyTurn(FilyRole.User, FilyPrompt.appState(access.enabled.value, access.contentAccess.value, today(), screen))
        val exchange = mutableListOf(FilyTurn(FilyRole.User, request))
        repeat(MAX_TOOL_STEPS + 1) { step ->
            val earlier = history.takeLast(MAX_HISTORY_EXCHANGES).flatten()
            val raw = complete(FilyPrompt.render(system, listOf(state) + earlier + exchange), grammar).trim()
            val response = parseModelResponse(raw)
            exchange += FilyTurn(FilyRole.Assistant, raw)
            val toolName = response.toolName
            if (response.kind != "tool" || toolName == null || step == MAX_TOOL_STEPS) {
                remember(exchange)
                return if (response.kind == "tool") "I couldn't finish that. Try asking another way." else spokenTextFor(response)
            }
            val result = if (toolName in toolNames) tools.execute(toolName, response.toolArgs) else UNOFFERED_TOOL
            exchange += FilyTurn(FilyRole.Tool, result)
        }
        error("unreachable")
    }

    fun clear() = history.clear()

    /**
     * Words the recognizer should expect: Fily's commands, and the words in file names when the
     * user has let the assistant see the library.
     */
    fun speechHints(): List<String> {
        val words = if (access.enabled.value) fileWords() else emptyList()
        return (COMMAND_HINTS + words).distinct()
    }

    /**
     * Of the recognizer's guesses, the one that is a command for this screen, else the one that
     * names the most file-name words, else its own first choice.
     */
    fun bestTranscript(alternatives: List<String>): String {
        val first = alternatives.firstOrNull().orEmpty()
        val screen = screen
        alternatives.firstOrNull { QuickCommands.parse(it, screen.readerCapabilities, screen.pageCount) != null }?.let { return it }
        if (screen.selectedDocumentId == null) alternatives.firstOrNull { QuickCommands.listPosition(it) != null }?.let { return it }
        if (screen.selectedDocumentId != null) alternatives.firstOrNull(QuickCommands::isClose)?.let { return it }
        val known = if (access.enabled.value) fileWords().map(String::lowercase).toSet() else return first
        val scored = alternatives.map { text -> text to text.lowercase().split(Regex("[^a-z0-9]+")).count { it in known } }
        val best = scored.maxByOrNull { it.second } ?: return first
        return if (best.second > (scored.firstOrNull()?.second ?: 0)) best.first else first
    }

    private fun fileWords(): List<String> = fileNames().asSequence()
        .flatMap { it.substringBeforeLast('.').split(Regex("[^A-Za-z0-9]+")).asSequence() }
        // Two letters keep abbreviations such as "CV".
        .filter { it.length >= 2 && !it.all(Char::isDigit) }
        .distinctBy(String::lowercase)
        .take(MAX_FILE_HINTS)
        .toList()

    /** Runs a recognised screen command directly, recorded as the model would have done it. */
    private suspend fun quick(request: String, command: QuickCommand): String {
        val result = reader.run(command.operation, command.value)
        val position = result.position
        val reply = QuickCommands.reply(command, position.page, position.pageCount, position.sheet, position.cell, result.failure)
        val call = buildJsonObject {
            put("kind", "tool")
            put("name", "controlReader")
            put("arguments", buildJsonObject {
                put("operation", command.operation)
                command.value?.let { put("value", it) }
            })
        }
        val outcome = if (result.failure != null) {
            buildJsonObject { put("status", "failed"); put("reason", result.failure) }
        } else {
            buildJsonObject { put("status", "done"); position.page?.let { put("page", it) }; position.pageCount?.let { put("pageCount", it) } }
        }
        val answer = buildJsonObject {
            put("kind", "reply")
            put("text", reply)
            put("citations", buildJsonArray { })
        }
        remember(listOf(
            FilyTurn(FilyRole.User, request),
            FilyTurn(FilyRole.Assistant, call.toString()),
            FilyTurn(FilyRole.Tool, outcome.toString()),
            FilyTurn(FilyRole.Assistant, answer.toString()),
        ))
        return reply
    }

    /** "open the second file": opens it directly, recorded as the model would have done it. */
    private suspend fun openListed(request: String, listed: List<ListedFile>, position: Int): String {
        val file = (if (position == -1) listed.lastOrNull() else listed.getOrNull(position))
            ?: return "There ${if (listed.size == 1) "is only 1 file" else "are only ${listed.size} files"} on screen."
        val args = buildJsonObject { put("documentId", file.documentId) }
        val result = tools.execute("openDocument", args)
        val opened = "\"opened\"" in result
        val reply = if (opened) "Opening ${file.name}." else "I couldn't open ${file.name}."
        val alias = tools.alias(file.documentId)
        remember(listOf(
            FilyTurn(FilyRole.User, request),
            FilyTurn(FilyRole.Assistant, buildJsonObject {
                put("kind", "tool"); put("name", "openDocument"); put("arguments", buildJsonObject { put("documentId", alias) })
            }.toString()),
            FilyTurn(FilyRole.Tool, result),
            FilyTurn(FilyRole.Assistant, buildJsonObject {
                put("kind", "reply"); put("text", reply); put("citations", buildJsonArray { if (opened) add(kotlinx.serialization.json.JsonPrimitive(alias)) })
            }.toString()),
        ))
        return reply
    }

    private fun remember(exchange: List<FilyTurn>) {
        history += exchange.toList()
        while (history.size > MAX_HISTORY_EXCHANGES) history.removeAt(0)
    }

    private companion object {
        const val MAX_TOOL_STEPS = 3
        const val MAX_HISTORY_EXCHANGES = 2
        const val UNOFFERED_TOOL = """{"status":"failed","reason":"unsupported_tool"}"""
        const val MAX_FILE_HINTS = 80
        val COMMAND_HINTS = listOf(
            "Fily", "next page", "previous page", "go to page", "zoom in", "zoom out", "scroll down", "scroll up",
            "select cell", "next sheet", "next photo", "rotate", "play", "pause", "open", "find", "resume", "CV",
            "PDF", "invoice", "receipt", "bookmark", "share", "print", "scan", "merge",
        )
    }
}
