package com.ezfiles.reader.presentation.viewers

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The engine's description of an e-book, as `epub_book::manifest_json` writes it. */
@Serializable
internal data class BookManifest(
    val title: String = "",
    val author: String = "",
    val rtl: Boolean = false,
    val fixed: Boolean = false,
    val chapters: List<BookChapter> = emptyList(),
    val toc: List<BookTocEntry> = emptyList(),
) {
    /** The chapter an entry or link points into, or -1. */
    fun chapterOf(href: String): Int = href.substringBefore('#').let { path -> chapters.indexOfFirst { it.href == path } }

    /** The contents, or one entry per chapter when the book lists none. */
    val contents: List<BookTocEntry> by lazy {
        toc.ifEmpty { chapters.mapIndexed { i, c -> BookTocEntry("Section ${i + 1}", c.href, 0) } }
    }

    /**
     * The contents entry the reader is in: of the entries in the last chapter
     * at or before [chapter] that has any, the first (the chapter's own title,
     * not a section further into it).
     */
    fun entryAt(chapter: Int): Int {
        val last = contents.indexOfLast { chapterOf(it.href) in 0..chapter }
        if (last < 0) return -1
        val start = chapterOf(contents[last].href)
        return contents.indexOfFirst { chapterOf(it.href) == start }
    }
}

@Serializable
internal data class BookChapter(val href: String, val size: Long = 0)

@Serializable
internal data class BookTocEntry(val title: String, val href: String, val depth: Int = 0)

/** How books look; one setting for every book, as on an e-reader. [size] is the text size in percent. */
@Serializable
internal data class BookSettings(
    val theme: String = "white",
    val font: String = "original",
    val size: Int = 100,
    /** Line height; 0 keeps the book's own. */
    val spacing: Double = 0.0,
    /** 0 narrow, 1 normal, 2 wide. */
    val margin: Int = 1,
    val justify: Boolean = false,
) {
    fun larger() = copy(size = TEXT_SIZES.firstOrNull { it > size } ?: size)
    fun smaller() = copy(size = TEXT_SIZES.lastOrNull { it < size } ?: size)

    companion object {
        val TEXT_SIZES = listOf(70, 80, 90, 100, 110, 120, 135, 150, 170, 200, 240)
    }
}

/** Where a reader is in a book: a chapter and how far through it, 0 to 1. */
internal data class BookPosition(val chapter: Int, val fraction: Double)

/** Colours a highlight can have; the reader page draws the same four. */
internal val HIGHLIGHT_COLORS = listOf("yellow", "green", "blue", "pink")

/**
 * Text the reader marked. [start] and [end] count characters from the start of
 * the chapter's body, so they hold across text sizes, fonts and screens.
 */
@Serializable
internal data class BookHighlight(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: String = "yellow",
    val note: String = "",
    val created: Long = 0,
)

/** A marked page: the first character on it, as for [BookHighlight]. */
@Serializable
internal data class BookBookmark(val id: String, val chapter: Int, val offset: Int, val text: String = "", val created: Long = 0)

/** A book's highlights, notes and bookmarks. */
@Serializable
internal data class BookAnnotations(
    val highlights: List<BookHighlight> = emptyList(),
    val bookmarks: List<BookBookmark> = emptyList(),
) {
    fun withHighlight(h: BookHighlight) = copy(highlights = (highlights.filterNot { it.id == h.id } + h).sortedWith(compareBy({ it.chapter }, { it.start })).take(MAX_ANNOTATIONS))
    fun withoutHighlight(id: String) = copy(highlights = highlights.filterNot { it.id == id })
    fun withBookmark(b: BookBookmark) = copy(bookmarks = (bookmarks.filterNot { it.id == b.id } + b).sortedWith(compareBy({ it.chapter }, { it.offset })).take(MAX_ANNOTATIONS))
    fun withoutBookmark(id: String) = copy(bookmarks = bookmarks.filterNot { it.id == id })

    companion object {
        /** Per book, of each kind; far beyond what anyone marks in one book. */
        const val MAX_ANNOTATIONS = 5000
    }
}

/** What the reader page tells the app. */
internal sealed interface BookMessage {
    data object Turned : BookMessage
    /** [bookmark] is the id of the bookmark on this page, if any. */
    data class Position(
        val chapter: Int,
        val page: Int,
        val pages: Int,
        val fraction: Double,
        val percent: Double,
        val bookmark: String? = null,
    ) : BookMessage
    data object Menu : BookMessage
    data class Link(val url: String) : BookMessage
    data object End : BookMessage
    data object Failed : BookMessage

    /** The reader highlighted chosen text; [wantsNote] when it was the Note action. */
    data class Highlighted(val highlight: BookHighlight, val wantsNote: Boolean) : BookMessage
    data class HighlightTapped(val id: String) : BookMessage
    data class Bookmarked(val bookmark: BookBookmark) : BookMessage
    data class Unbookmarked(val id: String) : BookMessage

    /** Text is chosen ([active]); [low] when it sits in the lower part of the page. */
    data class Selection(val active: Boolean, val low: Boolean) : BookMessage
}

internal val BookJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A message from the reader page, or `null` if it doesn't carry [token] or makes no sense. */
internal fun parseBookMessage(raw: String, token: String): BookMessage? {
    val o: JsonObject = try {
        BookJson.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        return null
    }
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull
    fun number(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
    if (token.isEmpty() || text("t") != token) return null
    return when (text("type")) {
        "pos" -> {
            val chapter = int("ch") ?: return null
            val pages = (int("pages") ?: 1).coerceAtLeast(1)
            BookMessage.Position(
                chapter = chapter.coerceAtLeast(0),
                page = (int("page") ?: 0).coerceIn(0, pages - 1),
                pages = pages,
                fraction = (number("fraction") ?: 0.0).coerceIn(0.0, 1.0),
                percent = (number("percent") ?: 0.0).coerceIn(0.0, 1.0),
                bookmark = text("bookmark")?.takeIf(::validId),
            )
        }
        "highlight" -> {
            val id = text("id")?.takeIf(::validId) ?: return null
            val chapter = int("ch")?.takeIf { it >= 0 } ?: return null
            val start = int("start")?.takeIf { it >= 0 } ?: return null
            val end = int("end")?.takeIf { it > start } ?: return null
            val text = text("text").orEmpty().take(MAX_QUOTE_CHARS)
            val color = text("color")?.takeIf { it in HIGHLIGHT_COLORS } ?: "yellow"
            BookMessage.Highlighted(BookHighlight(id, chapter, start, end, text, color), wantsNote = text("note") == "true")
        }
        "tapHighlight" -> text("id")?.takeIf(::validId)?.let(BookMessage::HighlightTapped)
        "bookmark" -> {
            val id = text("id")?.takeIf(::validId) ?: return null
            val chapter = int("ch")?.takeIf { it >= 0 } ?: return null
            val offset = int("offset")?.takeIf { it >= 0 } ?: return null
            BookMessage.Bookmarked(BookBookmark(id, chapter, offset, text("text").orEmpty().take(MAX_QUOTE_CHARS)))
        }
        "unbookmark" -> text("id")?.takeIf(::validId)?.let(BookMessage::Unbookmarked)
        "selection" -> BookMessage.Selection(active = text("active") == "true", low = text("low") == "true")
        "turn" -> BookMessage.Turned
        "menu" -> BookMessage.Menu
        "link" -> text("url")?.let(BookMessage::Link)
        "end" -> BookMessage.End
        "error" -> BookMessage.Failed
        else -> null
    }
}

/** Quoted text kept with a highlight or bookmark. */
private const val MAX_QUOTE_CHARS = 2000

/** The reader page makes ids of hex digits; anything else isn't one of its own. */
private fun validId(id: String) = id.length in 8..64 && id.all { it in '0'..'9' || it in 'a'..'f' }

/** A fresh secret for one opened book's messages. */
@OptIn(ExperimentalUuidApi::class)
internal fun newBookToken(): String = Uuid.random().toHexString()

/**
 * The reader page with the app's part filled in. The placeholders come before
 * the book's own JSON in the page, so only their first appearance is replaced.
 */
internal fun bookReaderPage(
    shell: String,
    token: String,
    start: BookPosition?,
    settings: BookSettings,
    annotations: BookAnnotations = BookAnnotations(),
    reducedMotion: Boolean = false,
): String {
    val state = buildString {
        append("{\"ch\":").append(start?.chapter ?: 0)
        append(",\"fraction\":").append(start?.fraction ?: 0.0)
        append(",\"reducedMotion\":").append(reducedMotion)
        append(",\"settings\":").append(BookJson.encodeToString(BookSettings.serializer(), settings))
        append(",\"highlights\":").append(highlightsJson(annotations.highlights))
        append(",\"bookmarks\":").append(bookmarksJson(annotations.bookmarks))
        append('}')
    }
    return shell.replaceFirst("__EZTOKEN__", token).replaceFirst("__EZSTATE__", state)
}

/** What the page needs to draw highlights: no quoted text, which it has. */
private fun highlightsJson(list: List<BookHighlight>) = list.joinToString(",", "[", "]") { h ->
    "{\"id\":\"${h.id}\",\"ch\":${h.chapter},\"start\":${h.start},\"end\":${h.end}," +
        "\"color\":\"${h.color.takeIf { it in HIGHLIGHT_COLORS } ?: "yellow"}\",\"note\":${h.note.isNotBlank()}}"
}

private fun bookmarksJson(list: List<BookBookmark>) =
    list.joinToString(",", "[", "]") { "{\"id\":\"${it.id}\",\"ch\":${it.chapter},\"offset\":${it.offset}}" }

internal fun setHighlightsScript(list: List<BookHighlight>) = "window.ez&&ez.setHighlights(${highlightsJson(list)})"

internal fun setBookmarksScript(list: List<BookBookmark>) = "window.ez&&ez.setBookmarks(${bookmarksJson(list)})"

internal fun goToOffsetScript(chapter: Int, offset: Int) = "window.ez&&ez.goOffset($chapter,$offset)"

internal const val TOGGLE_BOOKMARK_SCRIPT = "window.ez&&ez.toggleBookmark()"

/** Highlights the text the reader chose; [withNote] asks for a note next. */
internal fun highlightSelectionScript(withNote: Boolean, color: String = "yellow") =
    "window.ez&&ez.highlight($withNote,\"${color.takeIf { it in HIGHLIGHT_COLORS } ?: "yellow"}\")"

/** JavaScript that applies [settings] to the open page. */
internal fun applyBookSettingsScript(settings: BookSettings) =
    "window.ez&&ez.apply(${BookJson.encodeToString(BookSettings.serializer(), settings)})"

/** JavaScript that opens [href] (a chapter path, maybe with `#fragment`). */
internal fun goToHrefScript(href: String) = "window.ez&&ez.goHref(${BookJson.encodeToString(String.serializer(), href)})"

internal fun goToPercentScript(fraction: Float) = "window.ez&&ez.goPercent(${fraction.coerceIn(0f, 1f)})"

/** Remembers book settings, where each book was left, and its highlights, notes and bookmarks. */
internal interface BookStore {
    fun settings(): BookSettings?
    fun saveSettings(settings: BookSettings)
    fun position(book: String): BookPosition?
    fun savePosition(book: String, position: BookPosition)
    fun annotations(book: String): BookAnnotations
    fun saveAnnotations(book: String, annotations: BookAnnotations)
}

/** Keeps nothing; for platforms without storage and for previews. */
internal object NoBookStore : BookStore {
    override fun settings(): BookSettings? = null
    override fun saveSettings(settings: BookSettings) = Unit
    override fun position(book: String): BookPosition? = null
    override fun savePosition(book: String, position: BookPosition) = Unit
    override fun annotations(book: String) = BookAnnotations()
    override fun saveAnnotations(book: String, annotations: BookAnnotations) = Unit
}

internal fun encodeBookAnnotations(annotations: BookAnnotations): String = BookJson.encodeToString(BookAnnotations.serializer(), annotations)

internal fun decodeBookAnnotations(text: String?): BookAnnotations = text?.let {
    try {
        BookJson.decodeFromString(BookAnnotations.serializer(), it)
    } catch (_: Exception) {
        null
    }
} ?: BookAnnotations()

internal fun encodeBookSettings(settings: BookSettings): String = BookJson.encodeToString(BookSettings.serializer(), settings)

internal fun decodeBookSettings(text: String?): BookSettings? = text?.let {
    try {
        BookJson.decodeFromString(BookSettings.serializer(), it)
    } catch (_: Exception) {
        null
    }
}

internal fun decodeBookManifest(bytes: ByteArray): BookManifest? = try {
    BookJson.decodeFromString(BookManifest.serializer(), bytes.decodeToString())
} catch (_: Exception) {
    null
}
