package com.ezfiles.reader.presentation.viewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeHighlightTest {
    private fun styledWords(language: String, text: String): Set<String> {
        val coloured = highlightCode(language, text) ?: return emptySet()
        return coloured.spanStyles.map { text.substring(it.start, it.end) }.toSet()
    }

    @Test
    fun markdown_is_coloured_like_the_editor() {
        val words = styledWords("markdown", "# Title\n\n**bold** and `code` and [link](https://x.y)\n- item\n```kotlin\nval x = 1\n```\n> quote")
        assertTrue("# Title" in words)
        assertTrue("**bold**" in words)
        assertTrue("`code`" in words)
        assertTrue("[link](https://x.y)" in words)
        assertTrue("- " in words)
        assertTrue("val x = 1" in words, "fenced code")
        assertTrue("> quote" in words)
    }

    @Test
    fun code_gets_keywords_strings_comments_and_numbers() {
        val words = styledWords("kotlin", "@Composable fun main() { val s = \"hi\" // note\n  return 42 }")
        assertTrue(listOf("@Composable", "fun", "val", "\"hi\"", "// note", "return", "42").all { it in words }, words.toString())
        assertTrue("# a comment" in styledWords("python", "x = 1 # a comment"))
        assertTrue("<div class=\"a\">" in styledWords("html", "<div class=\"a\">text</div>"))
    }

    @Test
    fun plain_text_and_huge_blocks_stay_plain() {
        assertNull(highlightCode(null, "x"))
        assertNull(highlightCode("txt", "x"))
        assertNull(highlightCode("kotlin", "x".repeat(300_000)))
        assertEquals("plain", highlightCode("kotlin", "plain")!!.text)
    }
}
