package com.ezfiles.reader.application.assistant

import kotlin.test.Test
import kotlin.test.assertEquals

class GroundingTest {
    private val evidence = "[page 1] Invoice INV-2041 from Tata Power.\n[page 2] Amount due Rs 1,250 by 12 March 2026."

    @Test
    fun grounded_answer_gets_its_page() {
        assertEquals("The amount due is Rs 1,250 (page 2).", Grounding.check("The amount due is Rs 1,250.", evidence))
    }

    @Test
    fun invented_number_is_refused() {
        assertEquals(Grounding.NOT_FOUND, Grounding.check("The amount due is Rs 4,800.", evidence))
    }

    @Test
    fun answer_with_unsupported_words_is_refused() {
        assertEquals(Grounding.NOT_FOUND, Grounding.check("Your landlord requested quarterly maintenance deposits.", evidence))
    }

    @Test
    fun honest_not_found_is_kept() {
        val reply = "I couldn't find a due date for water."
        assertEquals(reply, Grounding.check(reply, evidence))
    }

    @Test
    fun existing_page_mention_is_not_doubled() {
        val reply = "Page 1 shows invoice INV-2041 from Tata Power."
        assertEquals(reply, Grounding.check(reply, evidence))
    }

    @Test
    fun no_evidence_leaves_reply_alone() {
        assertEquals("Hello!", Grounding.check("Hello!", ""))
    }

    @Test
    fun evidence_is_read_from_json_strings() {
        val text = Grounding.evidenceOf("""{"status":"ok","excerpt":"[page 3] Total 990"}""")
        assertEquals("ok\n[page 3] Total 990\n", text)
    }
}
