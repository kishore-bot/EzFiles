package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlViewerPolicyTest {
    @Test
    fun each_page_kind_gets_its_own_policy() {
        val web = PageKind.Web.contentSecurityPolicy
        assertTrue("'self'" in web && "'unsafe-inline'" in web, "web pages run their own scripts")
        assertFalse("http" in web || "*" in web, "and nothing from the network")
        val pages = PageKind.Pages.contentSecurityPolicy
        assertTrue("script-src 'nonce-ezpages'" in pages && "'unsafe-inline'" !in pages.substringAfter("script-src"))
        assertFalse("script-src" in PageKind.Preview.contentSecurityPolicy, "Markdown runs no scripts")
        for (kind in PageKind.entries) assertFalse('"' in kind.contentSecurityPolicy, kind.name)
        val secured = secureHtml("<!doctype html><p>x</p>", pages)
        assertTrue(secured.startsWith("<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\""))
        assertFalse("name=\"viewport\"" in secured, "Word pages set their own width")
    }

    @Test
    fun requests_to_the_page_host_name_files_beside_it() {
        assertEquals("doc/pages.html", pageRequestPath(pageUrl(PageKind.Pages, "a.docx")))
        assertEquals("My page.html", pageRequestPath(pageUrl(PageKind.Web, "My page.html")))
        assertEquals("assets/a b.css", pageRequestPath("${PAGE_ORIGIN}assets/a%20b.css?v=1#x"))
        assertEquals(null, pageRequestPath("https://example.com/a.css"))
        assertEquals(null, pageRequestPath("https://$PAGE_HOST.evil.com/a.css"))
    }

    @Test
    fun web_pages_open_their_neighbours_in_place() {
        val page = pageUrl(PageKind.Web, "index.html")
        assertEquals(HtmlLinkDecision.SameDocument, htmlLinkDecision("$page#top", userGesture = true, pageUrl = page))
        assertEquals(HtmlLinkDecision.Navigate, htmlLinkDecision("${PAGE_ORIGIN}about.html", true, pageUrl = page, sameSiteNavigation = true))
        assertEquals(HtmlLinkDecision.Refuse, htmlLinkDecision("${PAGE_ORIGIN}about.html", true, pageUrl = page))
        assertEquals(HtmlLinkDecision.AskToOpen, htmlLinkDecision("https://example.com/", true, pageUrl = page, sameSiteNavigation = true))
        assertEquals(HtmlLinkDecision.Ignore, htmlLinkDecision("${PAGE_ORIGIN}a.html", true, mainFrame = false, pageUrl = page, sameSiteNavigation = true))
    }

    @Test
    fun local_and_online_files_are_told_apart() {
        assertTrue(usesLocalFiles("<link rel=stylesheet href=assets/site.css>"))
        assertTrue(usesLocalFiles("<script src=app.js></script>"))
        assertTrue(usesLocalFiles("<img srcset=\"a.png 1x, b.png 2x\">"))
        assertTrue(usesLocalFiles("<style>@import 'base.css'; body{}</style>"))
        assertTrue(usesLocalFiles("<div style=\"background:url(bg.jpg)\"></div>"))
        assertFalse(usesLocalFiles("<img src=\"data:image/png;base64,AA\"><a href=other.html>x</a><img src=https://x.example/a.png>"))
        assertTrue(usesOnlineContent("<script src=https://cdn.example/x.js></script>"))
        assertTrue(usesOnlineContent("<link rel=stylesheet href=//fonts.example/css>"))
        assertFalse(usesOnlineContent("<a href=https://example.com>link</a><img src=a.png>"))
    }

    @Test
    fun linked_media_files_are_found_once_in_page_order() {
        val html = """
            <video controls src="clips/My%20clip.mp4?v=2#t=3" width="240"></video>
            <audio controls><source src="tone.ogg" type="audio/ogg"><source src="tone" type="audio/mpeg"></audio>
            <video><source src="movie.bin"></video>
            <audio src="clips/My%20clip.mp4"></audio>
            <video src="data:video/mp4;base64,AAAA"></video>
            <video src="https://example.com/a.mp4"></video><video src="//cdn.example/b.mp4"></video>
            <audio src="/etc/sound.wav"></audio><audio src="javascript:alert(1)"></audio><audio src=""></audio>
            <!-- <video src="hidden.mp4"></video> -->
            <img src="photo.jpg"><a href="song.mp3">song</a>
            <audio src="..\up\bad%zz.wav"></audio>
        """.trimIndent()
        assertEquals(
            listOf(
                LinkedMedia("clips/My clip.mp4", "My clip.mp4", MediaKind.Video),
                LinkedMedia("tone.ogg", "tone.ogg", MediaKind.Audio),
                LinkedMedia("tone", "tone", MediaKind.Audio),
                LinkedMedia("movie.bin", "movie.bin", MediaKind.Video),
                LinkedMedia("../up/bad%zz.wav", "bad%zz.wav", MediaKind.Audio),
            ),
            findLinkedMedia(html),
        )
        assertEquals(emptyList(), findLinkedMedia("<p>No media</p>"))
    }

    private val cspTag = "http-equiv=\"Content-Security-Policy\""

    /** The offset of the injected preamble, checking it went in exactly once and nothing else changed. */
    private fun policyAt(html: String): Int {
        val secured = secureHtml(html)
        val tag = secured.indexOf(cspTag)
        assertTrue(tag >= 0, "no policy in: $secured")
        assertEquals(tag, secured.lastIndexOf(cspTag), "more than one policy")
        val start = secured.indexOf("<meta charset=\"utf-8\">")
        val end = secured.indexOf("initial-scale=1\">") + "initial-scale=1\">".length
        assertEquals(html, secured.removeRange(start, end))
        return start
    }

    @Test
    fun policy_is_strict_and_names_what_default_src_leaves_out() {
        for (rule in listOf(
            "default-src 'none'",
            "img-src data:",
            "style-src 'unsafe-inline'",
            "font-src data:",
            "media-src data:",
            "base-uri 'none'",
            "form-action 'none'",
        )) {
            assertTrue(rule in HTML_CONTENT_SECURITY_POLICY, rule)
        }
        assertFalse("script-src" in HTML_CONTENT_SECURITY_POLICY)
        assertFalse("'unsafe-eval'" in HTML_CONTENT_SECURITY_POLICY)
        assertFalse('"' in HTML_CONTENT_SECURITY_POLICY, "would end the attribute it sits in")
    }

    @Test
    fun policy_goes_first_in_a_page_with_no_doctype() {
        assertEquals(0, policyAt(""))
        assertEquals(0, policyAt("just text"))
        assertEquals(0, policyAt("<p>no head at all</p>"))
        assertEquals(0, policyAt("<html><head><title>t</title></head><body>b</body></html>"))
    }

    @Test
    fun policy_stays_after_the_doctype_so_the_page_keeps_its_layout_mode() {
        val doctype = "<!DOCTYPE html>"
        assertEquals(doctype.length, policyAt("$doctype<html><head></head></html>"))
        assertEquals(doctype.length + 2, policyAt("\n\t$doctype<p>x"))
        val xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"x.dtd\">"
        assertEquals(xml.length, policyAt("$xml<html>"))
        val comment = "<!-- licence --><!doctype html>"
        assertEquals(comment.length, policyAt("$comment<html>"))
    }

    @Test
    fun head_closing_tricks_cannot_move_the_policy_behind_a_script() {
        val attacks = listOf(
            "</head><script>alert(1)</script><head>",
            "<head></head></head><body><script>alert(1)</script>",
            "<script>alert(1)</script><!doctype html>",
            // Ends at once for the HTML parser, though a naive scan would read on to the "-->" and the doctype.
            "<!--><script>alert(1)</script><!-- --><!doctype html>",
            "<!---><script>alert(1)</script><!-- --><!doctype html>",
            "<!-- x --!><script>alert(1)</script><!-- --><!doctype html>",
            "<!doctype html",
            "<!-- never closed <script>alert(1)</script>",
            "<? never closed",
            "﻿<script>alert(1)</script>",
            "x<!doctype html><script>alert(1)</script>",
        )
        for (attack in attacks) assertEquals(0, policyAt(attack), attack)
    }

    @Test
    fun a_doctype_ends_at_its_first_angle_bracket_even_inside_quotes() {
        val html = "<!doctype html x=\"><script>alert(1)</script>\">"
        val at = policyAt(html)
        assertEquals(html.indexOf('>') + 1, at)
        assertTrue(at <= html.indexOf("<script>"))
    }

    @Test
    fun a_page_cannot_replace_the_policy_with_its_own() {
        val html = "<!doctype html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src *\">"
        val secured = secureHtml(html)
        // Policies add up, so what counts is that ours is there too, and first.
        assertTrue(secured.indexOf(HTML_CONTENT_SECURITY_POLICY) < secured.indexOf("default-src *"))
    }

    @Test
    fun only_tapped_web_and_mail_links_are_offered() {
        for (url in listOf(
            "http://example.com/",
            "https://example.com/a?b=c#d",
            "HTTPS://EXAMPLE.COM",
            "mailto:someone@example.com",
            "https://user@example.com/",
            " https://example.com/ ",
        )) {
            assertEquals(HtmlLinkDecision.AskToOpen, htmlLinkDecision(url, userGesture = true), url)
        }
    }

    @Test
    fun every_other_scheme_is_refused() {
        for (url in listOf(
            "file:///sdcard/secret.txt",
            "content://com.example/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/x",
            "about:blank",
            "about:srcdoc",
            "tel:123",
            "market://details?id=x",
            "myapp://open",
            "ftp://example.com/",
            "https:",
            "https://",
            "http:///path",
            "http:example.com",
            "relative/path.html",
            "",
            "https://example.com/" + "a".repeat(10_000),
        )) {
            assertEquals(HtmlLinkDecision.Refuse, htmlLinkDecision(url, userGesture = true), url)
        }
    }

    @Test
    fun a_navigation_nobody_tapped_is_dropped_without_a_prompt() {
        // A <meta http-equiv="refresh"> arrives like this, and must not pop up a dialog.
        assertEquals(HtmlLinkDecision.Ignore, htmlLinkDecision("https://example.com/", userGesture = false))
        assertEquals(HtmlLinkDecision.Ignore, htmlLinkDecision("intent://x", userGesture = false))
        assertEquals(HtmlLinkDecision.Ignore, htmlLinkDecision("https://example.com/", userGesture = true, mainFrame = false))
    }

    @Test
    fun anchors_in_the_page_keep_working() {
        assertEquals(HtmlLinkDecision.SameDocument, htmlLinkDecision("about:blank#top", userGesture = true))
        assertEquals(HtmlLinkDecision.SameDocument, htmlLinkDecision("about:blank#top", userGesture = false))
        assertEquals(HtmlLinkDecision.Refuse, htmlLinkDecision("about:blankfoo#top", userGesture = true))
    }

    @Test
    fun only_data_resources_get_past_the_client() {
        assertTrue(htmlResourceAllowed("data:image/png;base64,AAAA"))
        assertTrue(htmlResourceAllowed("DATA:image/png;base64,AAAA"))
        for (url in listOf("http://example.com/a.png", "https://x/y", "file:///etc/hosts", "content://a/b", "about:blank", "x.png", "")) {
            assertFalse(htmlResourceAllowed(url), url)
        }
    }

    @Test
    fun a_displayed_link_hides_nothing_and_stays_readable() {
        assertEquals("https://example.com/moc.live", htmlLinkForDisplay("https://example.com/‮moc.live‬"))
        assertEquals("https://example.com/", htmlLinkForDisplay("https://exam\nple.com/\u0000"))
        val long = htmlLinkForDisplay("https://example.com/" + "a".repeat(1000))
        assertEquals(301, long.length)
        assertTrue(long.endsWith("…"))
    }

    private fun inert(html: String) = findInertContent(html)

    private val nothing = HtmlInertContent(scripts = false, external = false)

    @Test
    fun a_self_contained_page_needs_no_note() {
        val page = """
            <!doctype html><html><head><title>&lt;script&gt;</title>
            <style>body { background: url("data:image/png;base64,AA") } a[href="x"] { color: red } .b { background: url(#f) }</style>
            <link rel="canonical" href="https://example.com/"><link rel="icon" href="icon.png"></head>
            <body style="color: red"><img src="data:image/png;base64,AAAA"><a href="#top">top</a>
            <a href="https://example.com/">web</a><a href="mailto:a@b.c">mail</a><a>none</a>
            <!-- <script src="old.js"></script> <img src="old.png"> -->
            <pre>&lt;script&gt;alert(1)&lt;/script&gt; a &lt; b</pre><p>1 < 2 <3 </p><textarea><script>x</script></textarea>
            <svg><use href="#shape"/></svg></body></html>
        """.trimIndent()
        assertEquals(nothing, inert(page))
        assertFalse(inert(page).any)
    }

    @Test
    fun scripts_are_noticed_in_every_form() {
        for (html in listOf(
            "<script>document.body.innerHTML='x'</script>",
            "<SCRIPT SRC=a.js></SCRIPT>",
            "<script",
            "<body onload=\"x()\">",
            "<img src=\"data:image/png;base64,AA\" ONERROR=x()>",
            "<a href=\"javascript:x()\">x</a>",
            "<a href=' JavaScript:x()'>x</a>",
        )) {
            assertTrue(inert(html).scripts, html)
            assertFalse(inert(html).external, html)
        }
    }

    @Test
    fun outside_files_are_noticed_in_every_form() {
        for (html in listOf(
            "<img src=\"https://example.com/a.png\">",
            "<img src=a.png>",
            "<img src='//example.com/a.png'>",
            "<img srcset=\"a.png 2x\">",
            "<video poster=\"a.png\"></video>",
            "<audio src=\"a.mp3\"></audio>",
            "<source src=\"a.mp4\">",
            "<body background=\"a.png\">",
            "<link rel=\"stylesheet\" href=\"a.css\">",
            "<LINK HREF=a.css REL=STYLESHEET>",
            "<style>@import 'a.css';</style>",
            "<style>body { background: url(a.png) }</style>",
            "<style>@font-face { src: URL( 'https://example.com/f.woff' ) }</style>",
            "<p style=\"background: url('a.png')\">x</p>",
            "<iframe src=\"page.html\"></iframe>",
            "<iframe srcdoc=\"x\"></iframe>",
            "<object data=\"a.pdf\"></object>",
            "<embed src=\"a.swf\">",
            "<a href=\"other.html\">next</a>",
            "<a href=\"file:///sdcard/x.html\">x</a>",
            "<svg><image href=\"a.png\"/></svg>",
            "<svg><use xlink:href=\"a.svg#x\"/></svg>",
        )) {
            assertTrue(inert(html).external, html)
            assertFalse(inert(html).scripts, html)
        }
    }

    @Test
    fun the_scan_survives_broken_markup_in_linear_time() {
        for (html in listOf(
            "<",
            "<<<<",
            "</",
            "<!",
            "<!--",
            "<a",
            "<a href",
            "<a href=",
            "<a href=\"",
            "<a href='x",
            "<a =>",
            "<style>",
            "<title>",
            "<a/b/c>",
        )) {
            findInertContent(html)
            secureHtml(html)
        }
        // These would take minutes if any step scanned ahead again for each of the openers.
        findInertContent("<".repeat(4_000_000))
        findInertContent("<a ".repeat(1_000_000))
        findInertContent("<!--".repeat(1_000_000))
        findInertContent("<a x=' ".repeat(1_000_000))
    }

    private fun ByteArray.encoding() = sniffHtmlEncoding(this)

    @Test
    fun a_byte_order_mark_wins_over_the_page_declaration() {
        val meta = "<meta charset=shift_jis>".encodeToByteArray()
        assertEquals(HtmlEncoding("utf-8", 3), (byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + meta).encoding())
        assertEquals(HtmlEncoding("utf-16le", 2), (byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + meta).encoding())
        assertEquals(HtmlEncoding("utf-16be", 2), (byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + meta).encoding())
    }

    @Test
    fun the_page_declaration_comes_next_in_either_form() {
        fun label(html: String) = html.encodeToByteArray().encoding().label
        assertEquals("shift_jis", label("<meta charset=Shift_JIS>"))
        assertEquals("euc-kr", label("<!doctype html><html><head><META CHARSET=\"EUC-KR\"></head>"))
        assertEquals("koi8-r", label("<meta charset=' koi8-r '>"))
        assertEquals("big5", label("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=big5\">"))
        assertEquals("gbk", label("<meta content='text/html;charset = \"gbk\"; x' HTTP-EQUIV='content-type'>"))
        assertEquals("iso-8859-2", label("<meta name=x><meta charset=iso-8859-2><meta charset=koi8-r>"))
    }

    @Test
    fun the_declaration_is_read_as_the_standard_says() {
        fun label(html: String) = html.encodeToByteArray().encoding().label
        assertEquals("windows-1252", label("<meta charset=iso-8859-1>"))
        assertEquals("windows-1252", label("<meta charset=latin1>"))
        assertEquals("windows-1252", label("<meta charset=us-ascii>"))
        // Text readable as ASCII can't be UTF-16, whatever it says.
        assertEquals("utf-8", label("<meta charset=utf-16>"))
    }

    @Test
    fun anything_else_is_utf8() {
        fun label(html: String) = html.encodeToByteArray().encoding().label
        assertEquals("utf-8", label(""))
        assertEquals("utf-8", label("<p>no declaration</p>"))
        assertEquals("utf-8", label("<!-- <meta charset=koi8-r> -->"))
        assertEquals("utf-8", label("<script>'<meta charset=koi8-r>'</script>"))
        assertEquals("utf-8", label("<meta charset=>"))
        assertEquals("utf-8", label("<meta name=charset content=koi8-r>"))
        assertEquals("utf-8", label("<meta http-equiv=refresh content='0; charset=koi8-r'>"))
        assertEquals("utf-8", label(" ".repeat(9_000) + "<meta charset=koi8-r>"))
    }

    @Test
    fun decodes_by_what_it_found() {
        assertEquals("héllo €", decodeHtml("héllo €".encodeToByteArray()))
        assertEquals("héllo", decodeHtml(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".encodeToByteArray()))
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 0xE9.toByte(), 0, 0x3D, 0xD8.toByte(), 0, 0xDE.toByte())
        assertEquals("hé😀", decodeHtml(le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'h'.code.toByte(), 0, 0xE9.toByte())
        assertEquals("hé", decodeHtml(be))
    }

    @Test
    fun malformed_input_still_gives_text() {
        assertEquals("a�b", decodeHtml(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())))
        // A cut-off UTF-16 unit.
        assertEquals("h�", decodeHtml(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 0x41)))
        assertEquals("", decodeHtml(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals("", decodeHtml(ByteArray(0)))
        // A charset nobody has falls back to UTF-8.
        val made = "<meta charset=no-such-charset>é"
        assertEquals(made, decodeHtml(made.encodeToByteArray()))
    }

    @Test
    fun a_prepared_page_is_secured_and_knows_what_is_off() {
        val prepared = prepareHtml("<!doctype html><script>x()</script><p>hi".encodeToByteArray())
        assertTrue(prepared.page.startsWith("<!doctype html><meta charset=\"utf-8\">"))
        assertTrue(prepared.page.endsWith("<script>x()</script><p>hi"))
        assertEquals(HtmlInertContent(scripts = true, external = false), prepared.inert)
    }
}
