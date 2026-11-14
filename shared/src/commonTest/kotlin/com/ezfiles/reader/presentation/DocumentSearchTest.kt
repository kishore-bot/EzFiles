package com.ezfiles.reader.presentation

import androidx.compose.ui.text.SpanStyle
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.ContentBlock.Code
import com.ezfiles.reader.domain.ContentBlock.Divider
import com.ezfiles.reader.domain.ContentBlock.Heading
import com.ezfiles.reader.domain.ContentBlock.ListItem
import com.ezfiles.reader.domain.ContentBlock.Paragraph
import com.ezfiles.reader.domain.ContentBlock.Quote
import com.ezfiles.reader.domain.ContentBlock.Section
import com.ezfiles.reader.domain.ContentBlock.TableRow
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.viewers.richText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentSearchTest {
    private fun search(query: String, vararg pages: List<ContentBlock>) = SearchResults(query).plus(pages.toList())

    @Test
    fun matching_ignores_case() {
        val found = search("hello", listOf(Paragraph("HeLLo"), Paragraph("say HELLO"), Paragraph("hell"))).matches
        assertEquals(listOf(SearchMatch(0, 0, 0, 0, 5), SearchMatch(0, 1, 0, 4, 9)), found)
    }

    @Test
    fun finds_every_match_in_a_block_without_overlap() {
        val found = search("aa", listOf(Paragraph("aaaaa aa"))).matches
        assertEquals(listOf(0 to 2, 2 to 4, 6 to 8), found.map { it.start to it.end })
    }

    @Test
    fun finds_matches_in_table_cells_by_column() {
        val page = listOf(TableRow(1, listOf("apple", "pear", "Pineapple"), header = false))
        val found = search("apple", page).matches
        assertEquals(listOf(SearchMatch(0, 0, 0, 0, 5), SearchMatch(0, 0, 2, 4, 9)), found)
    }

    @Test
    fun finds_matches_in_list_items_but_not_their_markers() {
        val page = listOf(ListItem(depth = 1, text = "third step", marker = "step 3."))
        assertEquals(listOf(SearchMatch(0, 0, 0, 6, 10)), search("step", page).matches)
    }

    @Test
    fun searches_every_kind_of_text_block() {
        val page = listOf(Heading(1, "x"), Quote("x"), Code("x"), Section("x"), Divider, ContentBlock.Entry("x", 1, false))
        assertEquals(listOf(0, 1, 2, 3), search("x", page).matches.map { it.block })
    }

    @Test
    fun offsets_are_utf16_units() {
        // The emoji is one code point but two UTF-16 units.
        assertEquals(3, search("hello", listOf(Paragraph("😀 Hello"))).matches.single().start)
        val emoji = search("😀", listOf(Paragraph("a😀b"))).matches.single()
        assertEquals(1 to 3, emoji.start to emoji.end)
    }

    @Test
    fun offsets_survive_letters_whose_lowercase_is_longer() {
        assertEquals(3, search("ABC", listOf(Paragraph("İİ abc"))).matches.single().start)
    }

    @Test
    fun an_empty_query_matches_nothing() {
        val results = search("", listOf(Paragraph("anything")))
        assertTrue(results.matches.isEmpty())
        assertFalse(results.capped)
    }

    @Test
    fun appending_batches_scans_only_the_new_pages() {
        val first = listOf<ContentBlock>(Paragraph("one match"))
        val second = listOf<ContentBlock>(Paragraph("no"), Paragraph("match two"))
        val before = SearchResults("match").plus(listOf(first))
        var scannedBlocks = 0
        val after = before.plus(listOf(first, second)) { scannedBlocks++ }

        assertEquals(2, scannedBlocks)
        assertEquals(2, after.pagesSearched)
        assertEquals(listOf(SearchMatch(0, 0, 0, 4, 9), SearchMatch(1, 1, 0, 0, 5)), after.matches)
        assertSame(after, after.plus(listOf(first, second)))
    }

    @Test
    fun an_earlier_blocks_highlights_keep_their_identity_when_batches_arrive() {
        val first = listOf<ContentBlock>(Paragraph("x"))
        val before = SearchResults("x").plus(listOf(first))
        val after = before.plus(listOf(first, listOf(Paragraph("x"))))
        assertSame(before.highlights(0, 0..0, null)!!.matches, after.highlights(0, 0..0, null)!!.matches)
    }

    @Test
    fun stops_at_the_match_limit() {
        val results = SearchResults("a", limit = 3).plus(listOf(listOf(Paragraph("aa"), Paragraph("aa")), listOf(Paragraph("a"))))
        assertEquals(3, results.matches.size)
        assertTrue(results.capped)
        assertEquals(1, results.pagesSearched)
    }

    @Test
    fun highlights_cover_only_the_items_blocks() {
        val page = listOf<ContentBlock>(
            Paragraph("x"),
            TableRow(null, listOf("x", "y")),
            TableRow(null, listOf("y", "xx")),
        )
        val results = search("x", page)
        val tableHits = results.matches.drop(1)
        val current = tableHits.last()

        assertNull(results.highlights(1, 0..0, null))
        assertNull(results.highlights(0, 0..0, current)!!.current)
        val table = results.highlights(0, 1..2, current)!!
        assertEquals(tableHits, table.matches)
        assertEquals(current, table.current)
        assertEquals(listOf(tableHits[0]), table.inPart(1, 0))
        assertEquals(tableHits.drop(1), table.inPart(2, 1))
        assertEquals(emptyList(), table.inPart(1, 1))
        assertEquals(current, table.currentIn(2, 1))
        assertNull(table.currentIn(1, 0))
    }

    @Test
    fun next_and_previous_wrap_around() {
        val search = ReaderSearch().withResults(search("x", listOf(Paragraph("x x x"))), searching = false)
        assertEquals(0, search.currentIndex)
        assertEquals(1, search.next().currentIndex)
        assertEquals(0, search.next().next().next().currentIndex)
        assertEquals(2, search.previous().currentIndex)
        assertEquals(0, search.previous().next().currentIndex)
    }

    @Test
    fun stepping_with_no_matches_changes_nothing() {
        val search = ReaderSearch()
        assertSame(search, search.next())
        assertSame(search, search.previous())
        assertNull(search.currentMatch)
    }

    @Test
    fun stepping_onto_the_same_match_still_asks_to_reveal_it() {
        val search = ReaderSearch().withResults(search("x", listOf(Paragraph("x"))), searching = false)
        val stepped = search.next()
        assertEquals(search.currentMatch, stepped.currentMatch)
        assertNotSame(search, stepped)
        assertTrue(stepped.revealTick != search.revealTick)
    }

    @Test
    fun later_batches_keep_the_current_match() {
        val first = listOf<ContentBlock>(Paragraph("x x"))
        val partial = SearchResults("x").plus(listOf(first))
        val search = ReaderSearch().withResults(partial, searching = true).next()

        val grown = search.withResults(partial.plus(listOf(first, listOf(Paragraph("x")))), searching = false)
        assertEquals(1, grown.currentIndex)
        assertEquals(search.revealTick, grown.revealTick)
    }

    @Test
    fun the_first_match_is_selected_even_if_it_only_arrives_later() {
        val empty = SearchResults("x").plus(listOf(listOf(Paragraph("nothing"))))
        val waiting = ReaderSearch().withResults(empty, searching = true)
        assertEquals(-1, waiting.currentIndex)

        val found = waiting.withResults(empty.plus(listOf(listOf(Paragraph("nothing")), listOf(Paragraph("x")))), searching = false)
        assertEquals(SearchMatch(1, 0, 0, 0, 1), found.currentMatch)
    }

    @Test
    fun a_new_query_selects_its_first_match() {
        val old = ReaderSearch().withResults(search("x", listOf(Paragraph("x x"))), searching = false).next()
        val new = old.withResults(search("y", listOf(Paragraph("y"))), searching = false)
        assertEquals(0, new.currentIndex)
    }

    @Test
    fun count_label_shows_position_and_whether_more_may_follow() {
        val results = search("x", listOf(Paragraph("x x x")))
        assertNull(ReaderSearch().countLabel)
        assertEquals("2 / 3", ReaderSearch().withResults(results, searching = false).next().countLabel)
        assertEquals("1 / 3+", ReaderSearch().withResults(results, searching = true).countLabel)
        val capped = SearchResults("x", limit = 2).plus(listOf(listOf(Paragraph("x x x"))))
        assertEquals("1 / 2+", ReaderSearch().withResults(capped, searching = false).countLabel)
    }

    @Test
    fun rich_text_marks_matches_and_the_current_one_differently() {
        val first = SearchMatch(0, 0, 0, 0, 1)
        val second = SearchMatch(0, 0, 0, 2, 3)
        val styles = richText("x x", emptyList(), listOf(first, second), second).spanStyles
        assertEquals(listOf(0 to 1, 2 to 3), styles.map { it.start to it.end })
        assertEquals(
            listOf<SpanStyle>(
                SpanStyle(color = EzPaper.Text, background = EzColors.SearchMatch),
                SpanStyle(color = EzPaper.Text, background = EzColors.SearchCurrent),
            ),
            styles.map { it.item },
        )
    }
}
