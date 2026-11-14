package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.domain.mediaKindOf

/**
 * The most a page may hold to be rendered, in bytes, matching the engine's cap
 * on whole-file Markdown and JSON. Decoding and the WebView's own copy hold
 * several times this, and the bytes are read in one go. A larger page is
 * rarely a document anyone reads on a phone; it shows as streamed source.
 */
const val MAX_HTML_PREVIEW_BYTES: Long = 8L * 1024 * 1024

/** The base URL of a locked page. It is opaque, so relative paths resolve to nothing. */
internal const val HTML_BASE_URL = "about:blank"

/**
 * What a shown page may do.
 * - [Web]: a web page, as an offline browser shows it: its scripts run, and
 *   its styles, scripts, pictures and fonts load from its own folder where
 *   the app may read it. Nothing loads from the network.
 * - [Pages]: a Word document laid out as pages; only the engine's own script runs.
 * - [Preview]: rendered Markdown; no scripts, pictures from its folder.
 * - [Book]: an e-book's reader page. Only its own script runs (by nonce); the
 *   book's chapters load into a frame that runs none.
 */
internal enum class PageKind { Web, Pages, Preview, Book }

/** A host that can't exist (`.invalid` is reserved), answered by the app itself, never the network. */
internal const val PAGE_HOST = "page.ezfiles.invalid"
internal const val PAGE_ORIGIN = "https://$PAGE_HOST/"

/** The nonce of the engine's page script, as `docx_html::SCRIPT_NONCE` sets it. */
private const val PAGES_SCRIPT_NONCE = "ezpages"

internal val PageKind.contentSecurityPolicy: String
    get() = when (this) {
        PageKind.Web ->
            "default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval'; base-uri 'self'; form-action 'self'"
        PageKind.Pages ->
            "default-src 'none'; img-src 'self' data:; font-src 'self' data:; style-src 'unsafe-inline'; " +
                "script-src 'nonce-$PAGES_SCRIPT_NONCE'; base-uri 'none'; form-action 'none'"
        PageKind.Preview ->
            "default-src 'none'; img-src 'self' data:; media-src 'self' data:; style-src 'unsafe-inline'; " +
                "font-src 'self' data:; base-uri 'none'; form-action 'none'"
        PageKind.Book ->
            "default-src 'none'; script-src 'nonce-$BOOK_SCRIPT_NONCE'; style-src 'unsafe-inline'; connect-src 'self'; " +
                "frame-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'none'"
    }

/** The nonce of the e-book reader's script, as `epub_book.rs` writes it. */
private const val BOOK_SCRIPT_NONCE = "ezbook"

/**
 * What a book's own files may do inside the reader's frame, sent with each of
 * them. The frame's sandbox already stops scripts; this says so again.
 */
internal const val BOOK_FILE_POLICY =
    "script-src 'none'; object-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'self'"

/**
 * Where a page is shown from. A web page or preview sits at its own name, so
 * its relative links name files beside it; Word pages ask for `res/` and
 * `ezfonts/` paths under `doc/`.
 */
internal fun pageUrl(kind: PageKind, name: String): String = when (kind) {
    PageKind.Pages -> "${PAGE_ORIGIN}doc/pages.html"
    PageKind.Book -> "${PAGE_ORIGIN}ezbook/reader.html"
    else -> PAGE_ORIGIN + name.encodeUrlPath()
}

/** The decoded path a request on [PAGE_HOST] asks for, or `null` for any other URL. */
internal fun pageRequestPath(url: String): String? {
    if (!url.startsWith(PAGE_ORIGIN)) return null
    val path = url.removePrefix(PAGE_ORIGIN).substringBefore('#').substringBefore('?')
    return percentDecode(path)
}

private fun String.encodeUrlPath(): String = buildString {
    for (b in this@encodeUrlPath.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar()) else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}

/**
 * `default-src 'none'` does not cover `base-uri` or `form-action`, so they are
 * named too. A `POST` form would otherwise skip the link policy, because WebView
 * does not ask about it before navigating.
 */
internal const val HTML_CONTENT_SECURITY_POLICY =
    "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; media-src data:; " +
        "base-uri 'none'; form-action 'none'"

/**
 * Goes first in every rendered page. The viewport fits a page that names none to
 * the phone; a page's own viewport tag comes later, and the last one wins.
 */
private const val HTML_PREAMBLE =
    "<meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$HTML_CONTENT_SECURITY_POLICY\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

/**
 * [html] with the security policy before anything that could load or run. A
 * later policy in the page can only tighten this one, and `</head>` tricks don't
 * matter because nothing precedes the policy that could close a head.
 */
internal fun secureHtml(html: String, policy: String = HTML_CONTENT_SECURITY_POLICY): String {
    val at = policyOffset(html)
    val preamble = if (policy == HTML_CONTENT_SECURITY_POLICY) HTML_PREAMBLE else preambleFor(policy)
    return buildString(html.length + preamble.length) {
        append(html, 0, at)
        append(preamble)
        append(html, at, html.length)
    }
}

/** Word pages and the book reader set their own viewport, so they are given none. */
private fun preambleFor(policy: String) =
    "<meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" +
        if (policy == PageKind.Pages.contentSecurityPolicy || policy == PageKind.Book.contentSecurityPolicy) "" else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

/**
 * Right after the doctype, because any tag before it would put the page in
 * quirks mode and change its layout; otherwise the very start. Whatever isn't
 * read here exactly as the HTML parser reads it also gives the very start, so
 * an odd prolog can cost a page its layout mode, but never its policy.
 */
private fun policyOffset(html: String): Int {
    var at = 0
    while (true) {
        while (at < html.length && html[at] in HTML_WHITESPACE) at++
        at = when {
            html.startsWith("<!doctype", at, ignoreCase = true) -> {
                // Every state of the parser's doctype token ends at the first '>', even inside quotes.
                return html.indexOf('>', at) + 1
            }
            html.startsWith("<?", at) -> html.indexOf('>', at).takeIf { it >= 0 }?.plus(1) ?: return 0
            html.startsWith("<!--", at) -> plainCommentEnd(html, at) ?: return 0
            else -> return 0
        }
    }
}

/** Where the comment at [start] ends, or `null` for the forms that end somewhere other than the first `-->`. */
private fun plainCommentEnd(html: String, start: Int): Int? {
    val body = start + 4
    if (html.startsWith(">", body) || html.startsWith("->", body)) return null
    val end = html.indexOf("-->", body)
    if (end < 0) return null
    val bang = html.indexOf("--!>", body)
    return if (bang in 0 until end) null else end + 3
}

private const val HTML_WHITESPACE = " \t\n\u000C\r"

/** What to do with a navigation the page asks for. */
internal enum class HtmlLinkDecision {
    /** A `#anchor` in this page: WebView scrolls. */
    SameDocument,

    /** A web or mail link the user tapped: ask, then hand to another app. */
    AskToOpen,

    /** A link the user tapped that must not be followed: say so. */
    Refuse,

    /** The page navigated by itself, such as `<meta http-equiv="refresh">`: nothing is shown. */
    Ignore,

    /** A web page's link to another page beside it: it opens in place, as in a browser. */
    Navigate,
}

/** Longer URLs are refused: they are no real link, and an intent can't carry one of megabytes. */
private const val MAX_LINK_LENGTH = 8 * 1024

internal fun htmlLinkDecision(
    url: String,
    userGesture: Boolean,
    mainFrame: Boolean = true,
    pageUrl: String = HTML_BASE_URL,
    sameSiteNavigation: Boolean = false,
): HtmlLinkDecision {
    val link = url.trim()
    return when {
        link.startsWith("$pageUrl#") -> HtmlLinkDecision.SameDocument
        !mainFrame -> HtmlLinkDecision.Ignore
        link.startsWith(PAGE_ORIGIN) -> if (sameSiteNavigation) HtmlLinkDecision.Navigate else if (userGesture) HtmlLinkDecision.Refuse else HtmlLinkDecision.Ignore
        !userGesture -> HtmlLinkDecision.Ignore
        link.length > MAX_LINK_LENGTH -> HtmlLinkDecision.Refuse
        else -> when (schemeOf(link)) {
            "http", "https" -> if (hasHost(link)) HtmlLinkDecision.AskToOpen else HtmlLinkDecision.Refuse
            "mailto" -> HtmlLinkDecision.AskToOpen
            else -> HtmlLinkDecision.Refuse
        }
    }
}

/** A `data:` resource is part of the page; anything else would be a fetch, and gets an empty answer. */
internal fun htmlResourceAllowed(url: String): Boolean = schemeOf(url.trim()) == "data"

/** The URL for the dialog: no characters that reorder or hide text, and short enough to read. */
internal fun htmlLinkForDisplay(url: String): String {
    val shown = url.trim().filterNot { it.isISOControl() || it in BIDI_CONTROLS }
    return if (shown.length > MAX_DISPLAYED_LINK) shown.take(MAX_DISPLAYED_LINK) + "…" else shown
}

private const val MAX_DISPLAYED_LINK = 300
private const val BIDI_CONTROLS = "؜‎‏‪‫‬‭‮⁦⁧⁨⁩"

private fun schemeOf(url: String): String? {
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val scheme = url.substring(0, colon)
    val valid = scheme[0].isAsciiLetter() && scheme.all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }
    return if (valid) scheme.lowercase() else null
}

private fun hasHost(url: String): Boolean {
    val start = url.indexOf(':') + 1
    if (!url.startsWith("//", start)) return false
    return url.getOrNull(start + 2)?.let { it !in "/?#" } ?: false
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/** What a rendered page can't do here. */
internal data class HtmlInertContent(
    /** `<script>`, `on…` handlers or `javascript:` links. */
    val scripts: Boolean,
    /** Pictures, styles, frames or links to other files, none of which load. */
    val external: Boolean,
) {
    val any: Boolean get() = scripts || external
}

/** Finds what won't work, without running or fetching any of it; `data:` pictures and inline styles do work. */
internal fun findInertContent(html: String): HtmlInertContent {
    var scripts = false
    var external = false
    scanHtmlTags(html) { tag ->
        if (tag.name == "script") scripts = true
        if (tag.name in ALWAYS_EXTERNAL_TAGS) external = true
        if (tag.rawText != null && tag.name == "style" && cssIsExternal(tag.rawText)) external = true
        for ((name, value) in tag.attributes) {
            when {
                name.length > 2 && name.startsWith("on") -> scripts = true
                name == "style" -> external = external || cssIsExternal(value)
                // A script's own file counts as the script it is, not as missing content.
                name in RESOURCE_ATTRIBUTES && tag.name != "script" -> {
                    if (value.isJavascriptUrl()) scripts = true else if (!value.isInPage()) external = true
                }
                name == "href" || name == "xlink:href" -> when {
                    value.isJavascriptUrl() -> scripts = true
                    tag.name == "a" || tag.name == "area" -> external = external || !value.isInPage() && !value.isOutgoingLink()
                    tag.name == "link" -> external = external || tag.relIsStylesheet()
                    tag.name == "image" || tag.name == "use" -> external = external || !value.isInPage()
                }
            }
        }
        !(scripts && external)
    }
    return HtmlInertContent(scripts, external)
}

private val ALWAYS_EXTERNAL_TAGS = setOf("iframe", "frame", "object", "embed", "applet")
private val RESOURCE_ATTRIBUTES = setOf("src", "srcset", "data", "poster", "background")

private fun String.isInPage(): Boolean {
    val value = trim().lowercase()
    return value.isEmpty() || value.startsWith("#") || value.startsWith("data:")
}

private fun String.isOutgoingLink(): Boolean {
    val value = trim().lowercase()
    return OUTGOING_SCHEMES.any(value::startsWith)
}

private val OUTGOING_SCHEMES = listOf("http:", "https:", "mailto:", "//")

private fun String.isJavascriptUrl(): Boolean = trim().startsWith("javascript:", ignoreCase = true)

private fun HtmlTag.relIsStylesheet(): Boolean =
    attributes.any { (name, value) -> name == "rel" && value.contains("stylesheet", ignoreCase = true) }

private fun cssIsExternal(css: String): Boolean {
    if (css.contains("@import", ignoreCase = true)) return true
    var at = css.indexOf("url(", ignoreCase = true)
    while (at >= 0) {
        val close = css.indexOf(')', at)
        val inner = css.substring(at + 4, if (close < 0) css.length else close).trim().trim('"', '\'')
        if (!inner.isInPage()) return true
        at = css.indexOf("url(", at + 4, ignoreCase = true)
    }
    return false
}

/** One start tag; [rawText] is the content of an element whose content isn't markup. */
internal class HtmlTag(val name: String, val attributes: List<Pair<String, String>>, val rawText: String?)

private val RAW_TEXT_TAGS = setOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes")

/**
 * A small tokenizer, in one pass, so that markup in comments, in `<style>` or in
 * a script's strings doesn't count. It stops at markup left open, as the parser
 * would treat the rest as that one token. [onTag] returns `false` to stop early.
 */
internal fun scanHtmlTags(html: String, onTag: (HtmlTag) -> Boolean) {
    var at = 0
    while (true) {
        at = html.indexOf('<', at)
        if (at < 0 || at + 1 >= html.length) return
        val next = html[at + 1]
        when {
            html.startsWith("<!--", at) -> {
                val body = at + 4
                at = when {
                    html.startsWith(">", body) -> body + 1
                    html.startsWith("->", body) -> body + 2
                    else -> html.indexOf("-->", body).takeIf { it >= 0 }?.plus(3) ?: return
                }
            }
            next == '!' || next == '?' || next == '/' -> at = html.indexOf('>', at).takeIf { it >= 0 }?.plus(1) ?: return
            next.isAsciiLetter() -> {
                var end = at + 1
                while (end < html.length && html[end] != '>' && html[end] != '/' && html[end] !in HTML_WHITESPACE) end++
                val name = html.substring(at + 1, end).lowercase()
                val attributes = mutableListOf<Pair<String, String>>()
                end = readAttributes(html, end, attributes)
                if (end < 0) {
                    onTag(HtmlTag(name, attributes, null))
                    return
                }
                val raw = name in RAW_TEXT_TAGS
                val close = if (raw) html.indexOf("</$name", end, ignoreCase = true) else -1
                val rawText = if (!raw) null else html.substring(end, if (close < 0) html.length else close)
                if (!onTag(HtmlTag(name, attributes, rawText))) return
                if (raw && close < 0) return
                at = if (raw) close else end
            }
            else -> at++
        }
    }
}

/** Reads attributes up to and past the tag's `>`; returns where the tag ended, or -1 if it doesn't. */
private fun readAttributes(html: String, start: Int, into: MutableList<Pair<String, String>>): Int {
    var at = start
    while (true) {
        while (at < html.length && (html[at] in HTML_WHITESPACE || html[at] == '/')) at++
        if (at >= html.length) return -1
        if (html[at] == '>') return at + 1
        val nameStart = at
        at++ // A leading '=' is part of the name.
        while (at < html.length && html[at] != '=' && html[at] != '>' && html[at] != '/' && html[at] !in HTML_WHITESPACE) at++
        val name = html.substring(nameStart, at).lowercase()
        while (at < html.length && html[at] in HTML_WHITESPACE) at++
        if (at >= html.length || html[at] != '=') {
            into += name to ""
            continue
        }
        at++
        while (at < html.length && html[at] in HTML_WHITESPACE) at++
        if (at >= html.length) return -1
        val quote = html[at]
        if (quote == '"' || quote == '\'') {
            val close = html.indexOf(quote, at + 1)
            if (close < 0) return -1
            into += name to html.substring(at + 1, close)
            at = close + 1
        } else {
            val valueStart = at
            while (at < html.length && html[at] != '>' && html[at] !in HTML_WHITESPACE) at++
            into += name to html.substring(valueStart, at)
        }
    }
}

/** How a page's bytes turn into text: [label] is a charset name, after [bomLength] bytes of byte order mark. */
internal data class HtmlEncoding(val label: String, val bomLength: Int = 0)

/** How far to look for `<meta charset>`: pages must declare it early, and browsers stop looking too. */
private const val CHARSET_SCAN_BYTES = 8 * 1024

/** Byte order mark, then `<meta charset>`, then UTF-8. */
internal fun sniffHtmlEncoding(bytes: ByteArray): HtmlEncoding {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    return when {
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> HtmlEncoding("utf-8", 3)
        at(0) == 0xFF && at(1) == 0xFE -> HtmlEncoding("utf-16le", 2)
        at(0) == 0xFE && at(1) == 0xFF -> HtmlEncoding("utf-16be", 2)
        else -> HtmlEncoding(canonicalCharset(metaCharset(bytes)))
    }
}

/** The name a `<meta>` gives; bytes above ASCII can't be part of one, so any single-byte reading finds it. */
private fun metaCharset(bytes: ByteArray): String? {
    val head = CharArray(minOf(bytes.size, CHARSET_SCAN_BYTES)) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
    var found: String? = null
    scanHtmlTags(head) { tag ->
        if (tag.name == "meta") {
            found = tag.attributes.firstOrNull { it.first == "charset" }?.second?.trim()?.takeIf { it.isNotEmpty() }
                ?: tag.contentTypeCharset()
        }
        found == null
    }
    return found
}

/** `<meta http-equiv="Content-Type" content="text/html; charset=…">`. */
private fun HtmlTag.contentTypeCharset(): String? {
    val isContentType = attributes.any { (name, value) -> name == "http-equiv" && value.trim().equals("content-type", ignoreCase = true) }
    if (!isContentType) return null
    val content = attributes.firstOrNull { it.first == "content" }?.second ?: return null
    val at = content.indexOf("charset", ignoreCase = true)
    if (at < 0) return null
    val value = content.substring(at + "charset".length).trimStart()
    if (!value.startsWith('=')) return null
    return value.drop(1).substringBefore(';').trim().trim('"', '\'').trim().takeIf { it.isNotEmpty() }
}

/** As the HTML standard says: bytes readable as ASCII can't be UTF-16, and "latin1" means windows-1252. */
private fun canonicalCharset(label: String?): String = when (val name = label?.lowercase()) {
    null, "utf8", "utf-8", "unicode-1-1-utf-8", "utf-16", "utf-16le", "utf-16be", "x-user-defined" -> "utf-8"
    "latin1", "iso-8859-1", "iso8859-1", "ascii", "us-ascii", "l1", "cp1252", "x-cp1252" -> "windows-1252"
    else -> name
}

/** Never fails: malformed sequences and unknown charsets end up as replacement characters. */
internal fun decodeHtml(bytes: ByteArray): String {
    val encoding = sniffHtmlEncoding(bytes)
    return when (encoding.label) {
        "utf-8" -> bytes.decodeToString(encoding.bomLength, bytes.size)
        "utf-16le" -> decodeUtf16(bytes, encoding.bomLength, littleEndian = true)
        "utf-16be" -> decodeUtf16(bytes, encoding.bomLength, littleEndian = false)
        else -> decodeLegacyHtml(bytes, encoding.label) ?: bytes.decodeToString()
    }
}

private fun decodeUtf16(bytes: ByteArray, start: Int, littleEndian: Boolean): String {
    val units = (bytes.size - start) / 2
    return buildString(units + 1) {
        for (index in 0 until units) {
            val first = bytes[start + index * 2].toInt() and 0xFF
            val second = bytes[start + index * 2 + 1].toInt() and 0xFF
            append((if (littleEndian) first or (second shl 8) else (first shl 8) or second).toChar())
        }
        if ((bytes.size - start) % 2 != 0) append('�')
    }
}

/** Decodes a legacy charset the platform knows, or gives `null`. */
internal expect fun decodeLegacyHtml(bytes: ByteArray, label: String): String?

/**
 * A page ready for the WebView: what of it can't work here, the media files
 * it links to, and whether it uses files from its folder.
 */
internal class PreparedHtml(
    val page: String,
    val inert: HtmlInertContent,
    val media: List<LinkedMedia> = emptyList(),
    val localFiles: Boolean = false,
    val online: Boolean = false,
)

internal fun prepareHtml(bytes: ByteArray, kind: PageKind = PageKind.Web): PreparedHtml = when (kind) {
    PageKind.Web -> {
        val text = decodeHtml(bytes)
        PreparedHtml(
            secureHtml(text, kind.contentSecurityPolicy),
            findInertContent(text),
            findLinkedMedia(text),
            localFiles = usesLocalFiles(text),
            online = usesOnlineContent(text),
        )
    }
    // The engine writes this as UTF-8, with everything from the document escaped.
    PageKind.Pages, PageKind.Book ->
        PreparedHtml(secureHtml(bytes.decodeToString(), kind.contentSecurityPolicy), HtmlInertContent(scripts = false, external = false))
    PageKind.Preview -> {
        val text = bytes.decodeToString()
        PreparedHtml(secureHtml(text, kind.contentSecurityPolicy), findInertContent(text), localFiles = usesLocalFiles(text))
    }
}

/** Whether the page asks for anything from the web, which can't load offline. */
internal fun usesOnlineContent(html: String): Boolean {
    var found = false
    scanHtmlTags(html) { tag ->
        for ((name, value) in tag.attributes) {
            val online = when {
                name in RESOURCE_ATTRIBUTES -> value.trim().lowercase().let { it.startsWith("http:") || it.startsWith("https:") || it.startsWith("//") }
                name == "href" -> tag.name == "link" && value.trim().lowercase().let { it.startsWith("http") || it.startsWith("//") }
                else -> false
            }
            if (online) found = true
        }
        if (tag.name in ALWAYS_EXTERNAL_TAGS && tag.attributes.any { it.first == "src" && it.second.trim().lowercase().startsWith("http") }) found = true
        !found
    }
    return found
}

/** Whether the page names files beside it: styles, scripts, pictures, media, frames or fonts. */
internal fun usesLocalFiles(html: String): Boolean {
    var found = false
    scanHtmlTags(html) { tag ->
        for ((name, value) in tag.attributes) {
            val local = when {
                name == "srcset" -> value.split(',').any { it.trim().substringBefore(' ').isLocalFile() }
                name in RESOURCE_ATTRIBUTES -> value.isLocalFile()
                name == "href" -> (tag.name == "link" || tag.name == "image" || tag.name == "use") && value.isLocalFile()
                name == "style" -> cssNamesLocalFile(value)
                else -> false
            }
            if (local) found = true
        }
        if (tag.rawText != null && tag.name == "style" && cssNamesLocalFile(tag.rawText)) found = true
        !found
    }
    return found
}

private fun String.isLocalFile(): Boolean {
    val v = trim()
    return v.isNotEmpty() && !v.isInPage() && !v.isOutgoingLink() && !v.isJavascriptUrl() && schemeOf(v) == null
}

private val CSS_IMPORT = Regex("@import\\s+(?:url\\()?['\"]?([^'\")\\s;]+)")

private fun cssNamesLocalFile(css: String): Boolean {
    var at = css.indexOf("url(", ignoreCase = true)
    while (at >= 0) {
        val close = css.indexOf(')', at)
        val inner = css.substring(at + 4, if (close < 0) css.length else close).trim().trim('"', '\'')
        if (inner.isLocalFile()) return true
        at = css.indexOf("url(", at + 4, ignoreCase = true)
    }
    return CSS_IMPORT.findAll(css).any { it.groupValues[1].isLocalFile() }
}

/**
 * Audio or video a page plays from a file of its own, such as `clip.mp4` next
 * to it. The page can't load it, so the viewer offers to play it. [path] is
 * the link decoded, without query or fragment; [name] is its last part.
 */
internal data class LinkedMedia(val path: String, val name: String, val kind: MediaKind)

/** More would be a gallery, not a page with a clip; the bar shows these. */
private const val MAX_LINKED_MEDIA = 24

/**
 * The relative files `<video>`, `<audio>` and their `<source>`s name, in page
 * order, once each. Media inside the page (`data:`) plays there already, and
 * web addresses can't load without a network, so neither is listed.
 */
internal fun findLinkedMedia(html: String): List<LinkedMedia> {
    val found = LinkedHashMap<String, LinkedMedia>()
    var enclosing: MediaKind? = null
    scanHtmlTags(html) { tag ->
        val ownKind = when (tag.name) {
            "video" -> MediaKind.Video
            "audio" -> MediaKind.Audio
            else -> null
        }
        if (ownKind != null) enclosing = ownKind
        if (ownKind != null || tag.name == "source") {
            val src = tag.attributes.firstOrNull { it.first == "src" }?.second?.trim().orEmpty()
            val type = tag.attributes.firstOrNull { it.first == "type" }?.second?.trim()?.lowercase().orEmpty()
            val path = relativeMediaPath(src)
            if (path != null && path !in found) {
                val name = path.substringAfterLast('/')
                val kind = mediaKindOf(name)
                    ?: when {
                        type.startsWith("audio/") -> MediaKind.Audio
                        type.startsWith("video/") -> MediaKind.Video
                        else -> ownKind ?: enclosing
                    }
                if (kind != null && name.isNotEmpty()) found[path] = LinkedMedia(path, name, kind)
            }
        }
        found.size < MAX_LINKED_MEDIA
    }
    return found.values.toList()
}

/** A link to a file beside the page, decoded; `null` for anything else. */
private fun relativeMediaPath(src: String): String? {
    if (src.isEmpty() || src.isInPage() || schemeOf(src) != null || src.startsWith("//") || src.startsWith("/")) return null
    val path = percentDecode(src.substringBefore('#').substringBefore('?')).replace('\\', '/')
    return path.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
}

/** `%20` and the like; invalid escapes stay as written. */
private fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val digits = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3) else ""
        val hex = if (digits.length == 2 && digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) digits.toInt(16) else null
        if (hex != null) {
            bytes += hex.toByte()
            i += 3
        } else {
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
