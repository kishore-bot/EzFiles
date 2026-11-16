package com.ezfiles.reader.application.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
) {
    val supported: Set<String> = setOf(
        "searchLibrary", "searchDocuments", "listRecentDocuments", "getDocumentInfo",
        "getDocumentExcerpt", "findTextInDocument", "openDocument", "listBookmarks", "openBookmark",
    )

    suspend fun execute(name: String, args: JsonObject?): String {
        val arguments = args ?: JsonObject(emptyMap())
        return try {
            when (name) {
                "searchLibrary" -> documents(actions.searchLibrary(libraryQuery(arguments), RESULT_LIMIT))
                "searchDocuments" -> documents(actions.searchDocuments(arguments.string("query").orEmpty(), RESULT_LIMIT))
                "listRecentDocuments" -> documents(actions.listRecentDocuments(RESULT_LIMIT))
                "getDocumentInfo" -> documentInfo(arguments.required("documentId"))
                "getDocumentExcerpt" -> excerpt(arguments.required("documentId"), arguments.int("maxChars"))
                "findTextInDocument" -> findText(arguments.required("documentId"), arguments.required("query"))
                "openDocument" -> openDocument(arguments.required("documentId"))
                "listBookmarks" -> bookmarks(arguments.string("documentId").orEmpty())
                "openBookmark" -> openBookmark(arguments.required("bookmarkId"))
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

    private fun documents(results: AssistantDocumentResults): String = buildJsonObject {
        put("documents", JsonArray(results.documents.map { doc ->
            buildJsonObject {
                put("id", doc.id)
                put("name", doc.name)
            }
        }))
        put("partial", results.partial)
    }.toString()

    private suspend fun documentInfo(id: String): String {
        val doc = actions.getDocumentInfo(id)
        return buildJsonObject {
            put("documentId", doc.id)
            put("name", doc.name)
            doc.sizeBytes?.let { put("sizeBytes", it) }
            doc.modifiedAt?.let { put("modified", isoDate(it)) }
        }.toString()
    }

    private suspend fun excerpt(id: String, maxChars: Int?): String {
        // Short enough to leave room for the reply in the model's context window.
        val excerpt = actions.getDocumentExcerpt(id, (maxChars ?: MAX_EXCERPT_CHARS).coerceIn(1, MAX_EXCERPT_CHARS))
        return buildJsonObject {
            put("documentId", excerpt.document.id)
            put("text", excerpt.text)
            put("available", excerpt.available)
            put("truncated", excerpt.truncated)
        }.toString()
    }

    private suspend fun findText(id: String, query: String): String {
        val found = actions.findTextInDocument(id, query, limit = 5)
        return buildJsonObject {
            put("documentId", found.document.id)
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

    private suspend fun openDocument(id: String): String {
        val authorization = actions.access.captureAuthorization()
        val ref = actions.resolveDocument(id)
        actions.access.recheckAuthorization(authorization)
        navigation.request(AssistantDestination(ref, AssistantAction.Open, authorization))
        return buildJsonObject {
            put("status", "opened")
            put("documentId", id)
        }.toString()
    }

    private suspend fun bookmarks(documentId: String): String {
        val list = actions.listBookmarks(documentId, RESULT_LIMIT)
        return buildJsonObject {
            put("bookmarks", JsonArray(list.map { bookmark ->
                buildJsonObject {
                    put("bookmarkId", bookmark.id)
                    put("documentId", bookmark.document.id)
                    put("name", bookmark.document.name)
                    bookmark.pageNumber?.let { put("pageNumber", it) }
                    bookmark.chapterNumber?.let { put("chapterNumber", it) }
                }
            }))
        }.toString()
    }

    private suspend fun openBookmark(bookmarkId: String): String {
        val authorization = actions.access.captureAuthorization()
        val target = actions.resolveBookmark(bookmarkId)
        actions.access.recheckAuthorization(authorization)
        navigation.request(AssistantDestination(target.ref, AssistantAction.Open, authorization, bookmarkLocation = target.location))
        return buildJsonObject {
            put("status", "opened")
            put("bookmarkId", bookmarkId)
        }.toString()
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

    private fun failed(reason: String): String = buildJsonObject {
        put("status", "failed")
        put("reason", reason)
    }.toString()

    private companion object {
        const val RESULT_LIMIT = 8
        const val MAX_EXCERPT_CHARS = 1500
    }
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
