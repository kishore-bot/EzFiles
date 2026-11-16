package com.ezfiles.reader.application.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Runs the model's tool calls against the bounded [AssistantActions] and returns compact JSON shaped
 * like the tool results in the training data. Navigation goes through [AssistantNavigation], so the
 * foreground re-checks authorization and the reader's unsaved-work guard, as for every assistant.
 */
class ToolExecutor(
    private val actions: AssistantActions,
    private val navigation: AssistantNavigation,
    private val tidy: AssistantTidyActions? = null,
    private val reader: ReaderControl? = null,
) {
    val supported: Set<String> = setOf(
        "controlReader",
        "searchLibrary", "searchDocuments", "listRecentDocuments", "getDocumentInfo",
        "getDocumentExcerpt", "findTextInDocument", "openDocument", "listBookmarks", "openBookmark", "addBookmark",
        "prepareShareDocument", "prepareShareDocuments", "preparePrintDocument",
        "openScanner", "openImageToText", "openTextEditor", "createTextDocument", "openTextToPdf", "createPdfFromText",
        "openImagesToPdf", "createPdfFromImages", "openPdfTools", "mergePdfDocuments", "extractPdfPages",
        "prepareOrganizePdf", "prepareResizePdf", "preparePdfToImages", "preparePdfToText", "preparePdfToZip",
        "openPasswordTool", "openTidyUp", "openSimilarDocuments", "startTidyScan",
    ) + if (tidy != null) setOf("getTidyReport") else emptySet()

    /**
     * The model sees "doc_1" instead of 64-character opaque IDs: each full ID costs ~35 tokens to
     * read and to cite, and short IDs are what it was trained on.
     */
    private val ids = DocumentAliases()

    fun alias(documentId: String): String = ids.alias(documentId)

    suspend fun execute(name: String, args: JsonObject?): String {
        val arguments = ids.resolveArguments(args ?: JsonObject(emptyMap()))
        return try {
            when (name) {
                "searchLibrary" -> libraryQuery(arguments).let { query ->
                    documents(withContentFallback(query.query, actions.searchLibrary(query, RESULT_LIMIT)))
                }
                "searchDocuments" -> arguments.string("query").orEmpty().let { query ->
                    documents(withContentFallback(query, actions.searchDocuments(query, RESULT_LIMIT)))
                }
                "listRecentDocuments" -> documents(actions.listRecentDocuments(RESULT_LIMIT))
                "getDocumentInfo" -> documentInfo(arguments.required("documentId"))
                "getDocumentExcerpt" -> excerpt(arguments.required("documentId"), arguments.int("maxChars"))
                "findTextInDocument" -> findText(arguments.required("documentId"), arguments.required("query"))
                "openDocument" -> arguments.required("documentId").let { id ->
                    try {
                        openDocument(id)
                    } catch (notFound: IllegalArgumentException) {
                        openByName(args?.string("documentId").orEmpty()) ?: throw notFound
                    }
                }
                "listBookmarks" -> bookmarks(arguments.string("documentId").orEmpty())
                "openBookmark" -> openBookmark(arguments.required("bookmarkId"))
                "addBookmark" -> addBookmark(arguments.required("documentId"), arguments.int("pageNumber") ?: throw MissingArgument())
                "prepareShareDocument" -> documentHandoff(arguments.required("documentId"), AssistantAction.Share, "chooser_opened")
                "prepareShareDocuments" -> shareDocuments(arguments.ids("documentIds"))
                "preparePrintDocument" -> documentHandoff(arguments.required("documentId"), AssistantAction.Print, "print_preview_opened")
                "openScanner" -> tool("ScanToPdf")
                "openImageToText" -> tool("ImageToText")
                "openTextEditor" -> tool("NewText")
                "openTextToPdf" -> tool("TextToPdf")
                "openImagesToPdf", "createPdfFromImages" -> tool("ImagesToPdf")
                "openTidyUp", "openSimilarDocuments" -> tool("TidyUp")
                "startTidyScan" -> handoff("scan_started") { AssistantDestination(null, AssistantAction.Tool, it, tool = "TidyUp", startScan = true) }
                "createTextDocument" -> draft("NewText", arguments.required("text"), arguments.string("title").orEmpty())
                "createPdfFromText" -> draft("TextToPdf", arguments.required("text"), arguments.string("title").orEmpty())
                "openPdfTools" -> pdfTool(null, arguments.string("documentId"))
                "mergePdfDocuments" -> mergePdfs(arguments.ids("documentIds"))
                "extractPdfPages" -> pdfTool("Split", arguments.string("documentId"))
                "prepareOrganizePdf" -> pdfTool("Organize", arguments.string("documentId"))
                "prepareResizePdf" -> pdfTool("PageSize", arguments.string("documentId"))
                "preparePdfToImages" -> pdfTool("ToImages", arguments.string("documentId"))
                "preparePdfToText" -> pdfTool("ToText", arguments.string("documentId"))
                "preparePdfToZip" -> pdfTool("ToZip", arguments.string("documentId"))
                "openPasswordTool" -> pdfTool("AddPassword", arguments.string("documentId"))
                "getTidyReport" -> tidyReport()
                "controlReader" -> reader?.perform(arguments.required("operation"), (arguments["value"] as? JsonPrimitive)?.content)
                    ?: failed("no_document")
                else -> failed("unsupported_tool")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AssistantAccessDenied) {
            failed(if (actions.access.enabled.value) "content_restricted" else "access_off")
        } catch (_: MissingArgument) {
            failed("missing_arguments")
        } catch (_: IllegalArgumentException) {
            failed("not_available")
        }
    }

    /**
     * The model made up an ID from the user's words ("resume_1"): search the names for those words.
     * One match opens; several go back to the model as search results, so it can ask which one.
     */
    private suspend fun openByName(said: String): String? {
        if (said.isBlank() || ids.isAlias(said)) return null
        val query = said.substringBeforeLast('.').split(Regex("[^A-Za-z]+")).filter { it.length >= 2 }.joinToString(" ")
        if (query.isBlank()) return null
        val found = actions.searchDocuments(query, RESULT_LIMIT)
        return when (found.documents.size) {
            0 -> null
            1 -> openDocument(found.documents.single().id)
            else -> documents(found)
        }
    }

    /** No file name matched: look inside files ("the one that mentions INR 48,000"), if text may be read. */
    private suspend fun withContentFallback(query: String, byName: AssistantDocumentResults): AssistantDocumentResults =
        if (byName.documents.isNotEmpty() || query.isBlank() || !actions.access.contentAccess.value) byName
        else actions.searchContent(query, RESULT_LIMIT).takeIf { it.documents.isNotEmpty() } ?: byName

    private fun documents(results: AssistantDocumentResults): String = buildJsonObject {
        put("documents", JsonArray(results.documents.map { doc ->
            buildJsonObject {
                put("id", ids.alias(doc.id))
                put("name", doc.name)
            }
        }))
        put("partial", results.partial)
    }.toString()

    private suspend fun documentInfo(id: String): String {
        val doc = actions.getDocumentInfo(id)
        return buildJsonObject {
            put("documentId", ids.alias(doc.id))
            put("name", doc.name)
            doc.sizeBytes?.let { put("sizeBytes", it) }
            doc.modifiedAt?.let { put("modified", isoDate(it)) }
        }.toString()
    }

    /** The user's words for the current request; excerpts pick the passages that answer them. */
    var question: String = ""

    private suspend fun excerpt(id: String, maxChars: Int?): String {
        // Short enough to leave room for the reply in the model's context window.
        val budget = (maxChars ?: MAX_EXCERPT_CHARS).coerceIn(1, MAX_EXCERPT_CHARS)
        val excerpt = if (question.isNotBlank()) actions.getRelevantPassages(id, question, budget) else actions.getDocumentExcerpt(id, budget)
        return buildJsonObject {
            put("documentId", ids.alias(excerpt.document.id))
            put("text", excerpt.text)
            put("available", excerpt.available)
            put("truncated", excerpt.truncated)
        }.toString()
    }

    private suspend fun findText(id: String, query: String): String {
        val found = actions.findTextInDocument(id, query, limit = 5)
        return buildJsonObject {
            put("documentId", ids.alias(found.document.id))
            put("matches", JsonArray(found.matches.map { match ->
                buildJsonObject {
                    put("offset", match.offset)
                    put("snippet", match.snippet)
                }
            }))
            put("available", found.available)
            put("partial", found.partial)
        }.toString()
    }

    /**
     * Asks the foreground to show a screen. Like the platform assistants, this only opens a review:
     * sharing, printing, saving and every PDF change still need the user's own confirmation.
     */
    private suspend fun handoff(status: String, extra: JsonObjectBuilder.() -> Unit = {}, build: suspend (Long) -> AssistantDestination): String {
        val authorization = actions.access.captureAuthorization()
        val destination = build(authorization)
        actions.access.recheckAuthorization(authorization)
        navigation.request(destination)
        return buildJsonObject {
            put("status", status)
            extra()
        }.toString()
    }

    private suspend fun openDocument(id: String): String =
        handoff("opened", { put("documentId", ids.alias(id)) }) { AssistantDestination(actions.resolveDocument(id), AssistantAction.Open, it) }

    private suspend fun documentHandoff(id: String, action: AssistantAction, status: String): String =
        handoff(status, { put("documentId", ids.alias(id)) }) { AssistantDestination(actions.resolveDocument(id), action, it) }

    private suspend fun shareDocuments(ids: List<String>): String = handoff("chooser_opened") {
        require(ids.isNotEmpty()) { "Choose at least one document." }
        val refs = actions.resolveDocuments(ids)
        AssistantDestination(refs.first(), AssistantAction.Share, it, documents = refs)
    }

    private suspend fun addBookmark(id: String, pageNumber: Int): String = handoff("review_pending", { put("documentId", ids.alias(id)) }) {
        val ref = actions.resolveDocument(id)
        require(ref.displayName.endsWith(".pdf", ignoreCase = true) && pageNumber in 1..100_000) { "Choose a PDF and an existing page." }
        AssistantDestination(ref, AssistantAction.Bookmark, it, pageNumber = pageNumber)
    }

    private suspend fun tool(name: String): String =
        handoff("opened") { AssistantDestination(null, AssistantAction.Tool, it, tool = name) }

    private suspend fun draft(toolName: String, text: String, title: String): String = handoff("draft_ready") {
        val draft = AssistantDraft(text, title)
        AssistantDestination(null, AssistantAction.Tool, it, tool = toolName, draftText = draft.text, draftTitle = draft.title)
    }

    /** [operation] null opens the PDF tools list. */
    private suspend fun pdfTool(operation: String?, documentId: String?): String = handoff(if (operation == null) "opened" else "review_pending") {
        val ref = documentId?.takeIf(String::isNotBlank)?.let { id -> actions.resolveDocument(id) }
        require(ref == null || ref.displayName.endsWith(".pdf", ignoreCase = true)) { "Choose a PDF file." }
        AssistantDestination(ref, AssistantAction.Tool, it, tool = "PdfTools", pdfTool = operation)
    }

    private suspend fun mergePdfs(ids: List<String>): String = handoff("review_pending") {
        val refs = if (ids.isEmpty()) emptyList() else actions.resolveDocuments(ids)
        require(refs.all { ref -> ref.displayName.endsWith(".pdf", ignoreCase = true) }) { "Choose PDF files." }
        AssistantDestination(refs.firstOrNull(), AssistantAction.Tool, it, tool = "PdfTools", pdfTool = "Merge", documents = refs)
    }

    private suspend fun tidyReport(): String {
        val report = requireNotNull(tidy).getReport()
        return buildJsonObject {
            put("available", report.available)
            put("partial", report.partial)
            put("duplicateGroups", report.duplicateGroups)
            put("similarDocumentGroups", report.similarDocumentGroups)
            put("similarPhotoGroups", report.similarPhotoGroups)
            put("reclaimableBytes", report.reclaimableBytes)
            report.scannedAt?.let { put("scannedAt", isoDate(it)) }
        }.toString()
    }

    private suspend fun bookmarks(documentId: String): String {
        val list = actions.listBookmarks(documentId, RESULT_LIMIT)
        return buildJsonObject {
            put("bookmarks", JsonArray(list.map { bookmark ->
                buildJsonObject {
                    put("bookmarkId", bookmark.id)
                    put("documentId", ids.alias(bookmark.document.id))
                    put("name", bookmark.document.name)
                    bookmark.pageNumber?.let { put("pageNumber", it) }
                    bookmark.chapterNumber?.let { put("chapterNumber", it) }
                }
            }))
        }.toString()
    }

    private suspend fun openBookmark(bookmarkId: String): String = handoff("opened", { put("bookmarkId", bookmarkId) }) {
        val target = actions.resolveBookmark(bookmarkId)
        AssistantDestination(target.ref, AssistantAction.Open, it, bookmarkLocation = target.location)
    }

    private fun libraryQuery(args: JsonObject): AssistantLibraryQuery = AssistantLibraryQuery(
        query = args.string("query").orEmpty(),
        anyOf = args.string("category")?.let(::categoryKeywords).orEmpty(),
        extensions = args.string("fileType")?.let(::extensionsFor).orEmpty(),
        exclude = (args["exclude"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        modifiedAfter = args.string("after")?.let(::parseIsoDate),
        modifiedBefore = args.string("before")?.let(::parseIsoDate),
        minBytes = (args["minBytes"] as? JsonPrimitive)?.longOrNull,
        sort = when (args.string("sort")) {
            "modified_desc" -> AssistantSort.ModifiedNewest
            "opened_desc" -> AssistantSort.OpenedNewest
            "size_desc" -> AssistantSort.SizeLargest
            else -> AssistantSort.Relevance
        },
    )

    private class MissingArgument : Exception()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.required(key: String): String = string(key)?.takeIf(String::isNotBlank) ?: throw MissingArgument()

    private fun JsonObject.ids(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun failed(reason: String): String = buildJsonObject {
        put("status", "failed")
        put("reason", reason)
    }.toString()

    private companion object {
        const val RESULT_LIMIT = 8
        const val MAX_EXCERPT_CHARS = 1500
    }
}

/** Short, stable stand-ins for opaque document IDs, valid for the life of the app process. */
internal class DocumentAliases {
    private val byId = HashMap<String, String>()
    private val byAlias = HashMap<String, String>()

    fun alias(id: String): String = byId.getOrPut(id) { "doc_${byId.size + 1}".also { byAlias[it] = id } }

    /** An unknown value passes through, so a wrong ID still fails the normal access checks. */
    fun resolve(value: String): String = byAlias[value] ?: value

    fun isAlias(value: String): Boolean = value in byAlias

    fun resolveArguments(args: JsonObject): JsonObject = JsonObject(args.mapValues { (key, value) ->
        when (key) {
            "documentId" -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(resolve(it.content)) } ?: value
            "documentIds" -> (value as? JsonArray)?.let { list ->
                JsonArray(list.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(resolve(it.content)) } ?: item })
            } ?: value
            else -> value
        }
    })
}

/** Name keywords for the categories the model asks for; names only, never file contents. */
internal fun categoryKeywords(category: String): List<String> {
    val key = category.lowercase().trim()
    return when (key) {
        "resume", "resumes", "cv", "cvs" -> listOf("resume", "cv", "curriculum", "biodata")
        "invoice", "invoices", "bill", "bills" -> listOf("invoice", "bill")
        "receipt", "receipts" -> listOf("receipt")
        "ticket", "tickets", "travel" -> listOf("ticket", "boarding", "itinerary", "pnr")
        "statement", "statements", "bank" -> listOf("statement", "bank")
        "id", "ids", "identity" -> listOf("aadhaar", "aadhar", "passport", "pan", "licence", "license", "voter")
        "certificate", "certificates" -> listOf("certificate", "cert")
        "contract", "contracts", "agreement" -> listOf("contract", "agreement")
        "report", "reports" -> listOf("report")
        else -> listOf(key)
    }
}

internal fun extensionsFor(fileType: String): Set<String> = when (fileType.lowercase().trim().removePrefix(".")) {
    "pdf" -> setOf("pdf")
    "word", "doc", "docx", "document" -> setOf("doc", "docx", "odt", "rtf")
    "excel", "sheet", "spreadsheet", "xls", "xlsx", "csv" -> setOf("xls", "xlsx", "csv", "ods")
    "powerpoint", "slides", "presentation", "ppt", "pptx" -> setOf("ppt", "pptx", "odp")
    "image", "photo", "picture", "jpg", "jpeg", "png" -> setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
    "text", "txt", "note", "markdown", "md" -> setOf("txt", "md")
    "video" -> setOf("mp4", "mkv", "webm", "mov")
    "audio", "music" -> setOf("mp3", "m4a", "wav", "ogg", "flac")
    else -> setOf(fileType.lowercase().trim().removePrefix("."))
}

/** `YYYY-MM-DD` to UTC epoch milliseconds, or null when malformed. */
internal fun parseIsoDate(value: String): Long? {
    val parts = value.trim().take(10).split('-')
    if (parts.size != 3) return null
    val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
    if (m !in 1..12 || d !in 1..31) return null
    return daysFromCivil(y, m, d) * 86_400_000L
}

internal fun isoDate(epochMillis: Long): String {
    val (y, m, d) = civilFromDays(epochMillis.floorDiv(86_400_000L))
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

// Howard Hinnant's civil-calendar algorithms; no date library is available in common code.
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = (z - era * 146_097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}
