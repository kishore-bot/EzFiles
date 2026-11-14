package com.ezfiles.reader.presentation.viewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookModelTest {
    private val manifest = decodeBookManifest(
        """{"title":"T","author":"A","rtl":false,"fixed":false,"extra":1,
           "chapters":[{"href":"OEBPS/c1.xhtml","size":10},{"href":"OEBPS/c 2.xhtml","size":20},{"href":"OEBPS/notes.xhtml","size":5}],
           "toc":[{"title":"One","href":"OEBPS/c1.xhtml","depth":0},{"title":"One, part","href":"OEBPS/c1.xhtml#p","depth":1},
                  {"title":"Two","href":"OEBPS/c 2.xhtml","depth":0}]}""".encodeToByteArray(),
    )!!

    @Test
    fun the_manifest_reads_and_entries_find_their_chapter() {
        assertEquals("T", manifest.title)
        assertEquals(1, manifest.chapterOf("OEBPS/c 2.xhtml"))
        assertEquals(0, manifest.chapterOf("OEBPS/c1.xhtml#p"))
        assertEquals(-1, manifest.chapterOf("elsewhere.xhtml"))
        assertNull(decodeBookManifest("not json".encodeToByteArray()))
    }

    @Test
    fun the_header_names_the_chapter_not_a_section_inside_it() {
        assertEquals("One", manifest.contents[manifest.entryAt(0)].title)
        assertEquals("Two", manifest.contents[manifest.entryAt(1)].title)
        // A chapter without an entry of its own belongs to the one before.
        assertEquals("Two", manifest.contents[manifest.entryAt(2)].title)
    }

    @Test
    fun a_book_without_contents_lists_its_chapters() {
        val bare = manifest.copy(toc = emptyList())
        assertEquals(listOf("Section 1", "Section 2", "Section 3"), bare.contents.map { it.title })
    }

    @Test
    fun messages_need_the_token() {
        val pos = """{"type":"pos","ch":2,"page":9,"pages":4,"fraction":0.5,"percent":1.7,"t":"secret"}"""
        assertEquals(BookMessage.Position(2, 3, 4, 0.5, 1.0), parseBookMessage(pos, "secret"))
        assertNull(parseBookMessage(pos, "other"), "a book's own request can't pass as the reader")
        assertNull(parseBookMessage("""{"type":"menu"}""", "secret"))
        assertNull(parseBookMessage(pos, ""))
        assertEquals(BookMessage.Menu, parseBookMessage("""{"type":"menu","t":"s"}""", "s"))
        assertEquals(BookMessage.Link("https://x.io"), parseBookMessage("""{"type":"link","url":"https://x.io","t":"s"}""", "s"))
        assertNull(parseBookMessage("""{"type":"pos","t":"s"}""", "s"), "a position needs its chapter")
        assertNull(parseBookMessage("[1]", "s"))
    }

    @Test
    fun tokens_differ() {
        val a = newBookToken()
        assertTrue(a.length >= 32)
        assertNotEquals(a, newBookToken())
    }

    @Test
    fun the_page_gets_its_token_and_start_once_before_the_book() {
        val shell = "const TOKEN = '__EZTOKEN__';\nconst START = __EZSTATE__;\nconst BOOK = {\"title\":\"__EZTOKEN__ __EZSTATE__\"};"
        val page = bookReaderPage(shell, "tok", BookPosition(3, 0.25), BookSettings(theme = "sepia"))
        assertTrue(page.startsWith("const TOKEN = 'tok';\nconst START = {\"ch\":3,\"fraction\":0.25,\"reducedMotion\":false,\"settings\":{\"theme\":\"sepia\""))
        assertTrue(page.endsWith("{\"title\":\"__EZTOKEN__ __EZSTATE__\"};"), "the book's own text is left alone")
    }

    @Test
    fun settings_step_and_survive_storage() {
        val s = BookSettings()
        assertEquals(110, s.larger().size)
        assertEquals(90, s.smaller().size)
        assertEquals(240, s.copy(size = 240).larger().size)
        assertEquals(s.copy(theme = "black", spacing = 1.5), decodeBookSettings(encodeBookSettings(s.copy(theme = "black", spacing = 1.5))))
        assertNull(decodeBookSettings("{broken"))
    }

    @Test
    fun scripts_quote_what_they_carry() {
        assertEquals("window.ez&&ez.goHref(\"a\\\"b.xhtml#x\")", goToHrefScript("a\"b.xhtml#x"))
        assertEquals("window.ez&&ez.goPercent(1.0)", goToPercentScript(3f))
    }
}
