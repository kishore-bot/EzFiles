package com.ezfiles.reader.application.assistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

sealed interface ToolResult {
    data class Success(val summary: String) : ToolResult
    data class Error(val message: String) : ToolResult
    data class Navigation(val documentId: String, val summary: String) : ToolResult
}

class ToolExecutor(private val actions: AssistantActions) {

    suspend fun execute(toolName: String, args: JsonObject?): ToolResult = try {
        when (toolName) {
            "searchDocuments" -> searchDocuments(args)
            "searchLibrary" -> searchLibrary(args)
            "listRecentDocuments" -> listRecent()
            "getDocumentInfo" -> getDocumentInfo(args)
            "getDocumentExcerpt" -> getDocumentExcerpt(args)
            "findTextInDocument" -> findTextInDocument(args)
            "openDocument" -> openDocument(args)
            "listBookmarks" -> listBookmarks(args)
            else -> ToolResult.Error("Unknown tool: $toolName")
        }
    } catch (e: Exception) {
        ToolResult.Error(e.message ?: "Tool failed")
    }

    private suspend fun searchDocuments(args: JsonObject?): ToolResult {
        val query = args?.get("query")?.jsonPrimitive?.content ?: ""
        val results = actions.searchDocuments(query)
        if (results.documents.isEmpty()) return ToolResult.Success("No files found for \"$query\".")
        val list = results.documents.take(5).joinToString("; ") { "${it.name} [${it.id}]" }
        return ToolResult.Success("Found ${results.documents.size} file(s): $list")
    }

    private suspend fun searchLibrary(args: JsonObject?): ToolResult {
        val query = args?.get("query")?.jsonPrimitive?.content ?: ""
        val results = actions.searchDocuments(query)
        if (results.documents.isEmpty()) return ToolResult.Success("No files found for \"$query\".")
        val list = results.documents.take(5).joinToString("; ") { "${it.name} [${it.id}]" }
        return ToolResult.Success("Found ${results.documents.size} file(s): $list")
    }

    private suspend fun listRecent(): ToolResult {
        val results = actions.listRecentDocuments()
        if (results.documents.isEmpty()) return ToolResult.Success("No recent files.")
        val list = results.documents.take(5).joinToString("; ") { it.name }
        return ToolResult.Success("Recent files: $list")
    }

    private suspend fun getDocumentInfo(args: JsonObject?): ToolResult {
        val id = args?.get("documentId")?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing documentId")
        val doc = actions.getDocumentInfo(id)
        val size = doc.sizeBytes?.let { "${it / 1024} KB" } ?: "unknown size"
        return ToolResult.Success("${doc.name}, $size")
    }

    private suspend fun getDocumentExcerpt(args: JsonObject?): ToolResult {
        val id = args?.get("documentId")?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing documentId")
        val maxChars = args["maxChars"]?.jsonPrimitive?.int ?: 500
        val excerpt = actions.getDocumentExcerpt(id, maxChars.coerceAtMost(2000))
        if (!excerpt.available) return ToolResult.Error("Text not available for this file.")
        val text = excerpt.text.take(500)
        return ToolResult.Success("Excerpt from ${excerpt.document.name}: $text")
    }

    private suspend fun findTextInDocument(args: JsonObject?): ToolResult {
        val id = args?.get("documentId")?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing documentId")
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing query")
        val matches = actions.findTextInDocument(id, query)
        if (matches.matches.isEmpty()) return ToolResult.Success("\"$query\" not found in ${matches.document.name}.")
        return ToolResult.Success("Found ${matches.matches.size} match(es) in ${matches.document.name}: ${matches.matches.first().snippet}")
    }

    private suspend fun openDocument(args: JsonObject?): ToolResult {
        val id = args?.get("documentId")?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing documentId")
        val doc = actions.getDocumentInfo(id)
        return ToolResult.Navigation(id, "Opening ${doc.name}")
    }

    private suspend fun listBookmarks(args: JsonObject?): ToolResult {
        val docId = args?.get("documentId")?.jsonPrimitive?.content ?: ""
        val bookmarks = actions.listBookmarks(docId)
        if (bookmarks.isEmpty()) return ToolResult.Success("No bookmarks found.")
        val list = bookmarks.take(5).joinToString("; ") {
            "${it.document.name}${it.pageNumber?.let { p -> " p.$p" } ?: ""}"
        }
        return ToolResult.Success("Bookmarks: $list")
    }
}
