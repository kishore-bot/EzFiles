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
    fun pdfPassagesCarryTheirPageAndAPageCanBeAskedFor() {
        val pages = listOf("Cover page of the agreement.", "General terms apply.", "Payment: INR 48,000 due on signing.")
        val starts = pages.runningFold(0) { at, page -> at + page.length + 1 }.dropLast(1)
        val text = pages.joinToString("\n")
        val (found, _) = Passages.select(text + "\n" + "Filler text. ".repeat(200), "how much is the payment", 120, starts)
        assertTrue(found.startsWith("[page 3] Payment"), found)
        val (page, _) = Passages.select(text, "what does page two say", 500, starts)
        assertEquals("[page 2] General terms apply.", page)
        assertTrue(Passages.select(text, "what does page 9 say about payment", 500, starts).first.contains("INR 48,000"))
    }

    @Test
    fun aCvQuestionFindsTheCurrentJobNotTheProjectsHeading() {
        val pages = listOf(
            "KISHORE K AI Backend Engineer kishore@example.com Summary: backend systems and on-device AI.",
            "Software Engineer, Nimbus Labs Pvt Ltd (2024 - Present) building payment APIs in Python. " +
                "Intern, Aster Works (2023 - 2024) testing Android apps.",
            "OBSERVABILITY Prometheus, Sentry MOBILE Android, Jetpack Compose " + "Selected Projects & Engineering Experience ".repeat(2) +
                "Donkey: multiplayer card game backend with websockets and matchmaking. ".repeat(6),
        )
        val starts = pages.runningFold(0) { at, page -> at + page.length + 1 }.dropLast(1)
        val text = pages.joinToString("\n")
        val (experience, _) = Passages.select(text, "what is my current experience in this CV", 700, starts)
        assertTrue("Nimbus Labs" in experience && "Present" in experience, experience)
        assertTrue(experience.startsWith("[page 1] KISHORE K"), experience)
        val (company, _) = Passages.select(text, "which is my current company", 700, starts)
        assertTrue("Nimbus Labs" in company, company)
        // Passages never start or end inside a word.
        assertTrue(Regex("""\b[A-Z]{3,}\b""").findAll(experience).none { it.value == "BSERVABILITY" })
    }

    @Test
    fun aQuestionWithAFileOpenIsAboutThatFile() {
        val open = FilyScreen("doc_1")
        val tools = ToolSelector.select("which is my current company", open, FilyPrompt.toolNames.toSet())
        assertFalse("searchLibrary" in tools, tools.toString())
        assertTrue("getDocumentExcerpt" in tools)
        assertTrue("searchLibrary" in ToolSelector.select("find my other resume", open, FilyPrompt.toolNames.toSet()))
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
