package com.ezfiles.reader.application.assistant

import androidx.compose.runtime.compositionLocalOf

/** Unsaved assistant input; the user reviews it and chooses a destination in the ordinary editor. */
data class AssistantDraft(val text: String, val title: String, val requestId: Long = 0) {
    init {
        require(text.length <= MAX_TEXT_CHARS) { "Use at most 16,000 characters for an assistant draft." }
        require(title.length <= MAX_TITLE_CHARS) { "Use at most 80 characters for a draft title." }
        require(title.none { it.isISOControl() || it in "\\/:*?\"<>|" } && title.trim() !in setOf(".", "..")) {
            "Use a document title, not a path or URI."
        }
        require(requestId >= 0) { "Invalid draft request." }
    }

    companion object {
        const val MAX_TEXT_CHARS = 16_000
        const val MAX_TITLE_CHARS = 80
    }
}

val LocalAssistantDraft = compositionLocalOf<AssistantDraft?> { null }
