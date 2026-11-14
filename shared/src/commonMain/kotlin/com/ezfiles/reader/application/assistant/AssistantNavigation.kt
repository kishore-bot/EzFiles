package com.ezfiles.reader.application.assistant

import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AssistantAction { Open, Share, Print, Tool, Bookmark }

/** A resolved local handoff. Authorization must be checked again when the foreground consumes it. */
data class AssistantDestination(
    val ref: DocumentRef?,
    val action: AssistantAction,
    val authorization: Long,
    val tool: String? = null,
    val bookmarkLocation: BookmarkLocation? = null,
    val documents: List<DocumentRef> = emptyList(),
    val pdfTool: String? = null,
    val draftText: String? = null,
    val draftTitle: String? = null,
    val pageNumber: Int? = null,
    val startScan: Boolean = false,
    val requestSequence: Long = 0,
    /** Set only by the foreground reader's existing unsaved-work guard. */
    val readerExitApprovedFor: Int? = null,
)

class AssistantNavigation {
    private val state = MutableStateFlow<AssistantDestination?>(null)
    val pending: StateFlow<AssistantDestination?> = state.asStateFlow()
    private var sequence = 0L

    /** Called by platform handoffs on the main thread. Repeated requests get distinct UI state. */
    fun request(destination: AssistantDestination) { state.value = destination.copy(requestSequence = ++sequence) }

    fun consume(destination: AssistantDestination) { state.compareAndSet(destination, null) }

    fun clear() { state.value = null }
}
