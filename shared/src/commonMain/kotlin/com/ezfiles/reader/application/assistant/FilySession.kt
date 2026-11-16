package com.ezfiles.reader.application.assistant

/**
 * One spoken conversation with Fily: the model decides, [ToolExecutor] acts, and the tool's JSON
 * goes back to the model for a grounded reply. Not thread-safe; callers ask one question at a time.
 */
class FilySession(
    /** Continues a rendered prompt; [ModelManager.complete] in the app. */
    private val complete: suspend (String) -> String,
    private val tools: ToolExecutor,
    private val access: AssistantAccess,
    private val today: () -> String,
) {
    /** Completed exchanges, oldest first; each starts with the user's turn. */
    private val history = ArrayList<List<FilyTurn>>()

    /** Updated by the UI as the user moves between the library and documents. */
    var screen: FilyScreen = FilyScreen()

    /** Returns the text to show and speak. */
    suspend fun ask(request: String): String {
        val toolNames = ToolSelector.select(request, screen, tools.supported)
        val system = FilyPrompt.system(toolNames)
        val state = FilyTurn(FilyRole.User, FilyPrompt.appState(access.enabled.value, access.contentAccess.value, today(), screen))
        val exchange = mutableListOf(FilyTurn(FilyRole.User, request))
        repeat(MAX_TOOL_STEPS + 1) { step ->
            val earlier = history.takeLast(MAX_HISTORY_EXCHANGES).flatten()
            val raw = complete(FilyPrompt.render(system, listOf(state) + earlier + exchange)).trim()
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

    private fun remember(exchange: List<FilyTurn>) {
        history += exchange.toList()
        while (history.size > MAX_HISTORY_EXCHANGES) history.removeAt(0)
    }

    private companion object {
        const val MAX_TOOL_STEPS = 3
        const val MAX_HISTORY_EXCHANGES = 2
        const val UNOFFERED_TOOL = """{"status":"failed","reason":"unsupported_tool"}"""
    }
}
