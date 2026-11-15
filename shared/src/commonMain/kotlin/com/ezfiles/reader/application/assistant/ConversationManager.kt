package com.ezfiles.reader.application.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConversationTurn(
    val id: Int,
    val userText: String,
    val responseJson: String,
    val responseText: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class ConversationManager {
    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns
    private var nextId = 0

    fun addTurn(userText: String, responseJson: String, responseText: String) {
        _turns.value = _turns.value + ConversationTurn(nextId++, userText, responseJson, responseText)
    }

    fun recentContext(maxTurns: Int = 4): String {
        val recent = _turns.value.takeLast(maxTurns)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { "User: ${it.userText} → ${it.responseText}" }
    }

    fun clear() {
        _turns.value = emptyList()
        nextId = 0
    }
}
