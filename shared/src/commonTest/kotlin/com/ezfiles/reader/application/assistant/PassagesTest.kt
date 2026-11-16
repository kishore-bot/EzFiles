package com.ezfiles.reader.application.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassagesTest {
    private val contract = buildString {
        appendLine("Services Agreement between Aster Labs and Kishore K.")
        repeat(30) { appendLine("Clause $it covers general obligations of both parties in ordinary terms and plain language.") }
        appendLine("Payment: the total fee is INR 48,000, payable in two instalments.")
        repeat(30) { appendLine("Schedule $it lists deliverables and acceptance steps for each milestone in detail.") }
        appendLine("Termination requires thirty days written notice by either party.")
    }

    @Test
    fun aQuestionFindsThePassageDeepInTheDocument() {
        val (text, partial) = Passages.select(contract, "How much is the payment?", 300)
        assertTrue("INR 48,000" in text, text)
        assertTrue(partial)
        assertTrue(text.length <= 300)
    }

    @Test
    fun severalPassagesStayInDocumentOrder() {
        val (text, _) = Passages.select(contract, "what does it say about payment and termination notice", 600)
        assertTrue(text.indexOf("INR 48,000") in 0 until text.indexOf("thirty days"), text)
    }

    @Test
    fun noUsefulWordsMeansTheStartOfTheDocument() {
        assertTrue(Passages.select(contract, "summarize this document", 200).first.startsWith("Services Agreement"))
        assertTrue(Passages.select(contract, "what about zebras", 200).first.startsWith("Services Agreement"))
    }

    @Test
    fun aShortDocumentIsReturnedWhole() {
        assertEquals("Amount due: INR 2812." to false, Passages.select("Amount due: INR 2812.", "amount due", 1500))
        assertFalse(Passages.select("Amount due: INR 2812.", "amount due", 1500).second)
    }

    @Test
    fun grammarOffersOnlyTheGivenTools() {
        val grammar = FilyGrammar.forTools(listOf("openDocument"))
        assertTrue("""name ::= "\"openDocument\""""" in grammar)
        assertFalse("searchLibrary" in grammar)
        assertFalse("tool ::=" in FilyGrammar.forTools(emptyList()))
        assertTrue("root ::= \"{\\\"kind\\\":\" ( reply | clarify ) \"}\"" in FilyGrammar.forTools(emptyList()))
    }
}
