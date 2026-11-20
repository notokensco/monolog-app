package com.notokens.monolog.ai

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextTest {
    @Test
    fun forecastQuestionRecognizesOnlyTheBoundedIntent() {
        val today = LocalDate.of(2026, 11, 21)
        assertEquals(today.plusDays(1), AiText.weatherLookup("Is tomorrow going to rain?", today)?.date)
        assertEquals(today, AiText.weatherLookup("Will it rain today?", today)?.date)
        assertNull(AiText.weatherLookup("Send the draft to Pat. Will it rain today?", today))
        assertNull(AiText.weatherLookup("Will it rain tomorrow? My address is 99 Example St", today))
    }

    @Test
    fun rewriteFactCheckRejectsChangedNumbersAndContacts() {
        val facts = DraftFacts.read("Email alex@example.com by 5 PM. The invoice is ₹200.")
        assertTrue(facts.preserves("By 5 PM, email alex@example.com about the ₹200 invoice."))
        assertFalse(facts.preserves("By 6 PM, email alex@example.com about the ₹200 invoice."))
        assertFalse(facts.preserves("Email sam@example.com by 5 PM. The invoice is ₹200."))
    }
    @Test
    fun shortFragmentAndExactDetailsStayWithinTheDraft() {
        val draft = "Hi, Hey, What"
        assertEquals("Hi, Hey, What?", AiText.simpleRewrite(draft))
        assertNull(AiText.checkedRewrite(draft, "Hello. I am writing to share an important company announcement."))

        val detailedDraft = "remind Alex at 5 PM with alex@example.com"
        assertEquals("Remind Alex at 5 PM with alex@example.com.",
            AiText.checkedRewrite(detailedDraft, "Remind Alex at 5 PM with alex@example.com."))
        assertNull(AiText.checkedRewrite(detailedDraft, "Remind at 5 PM with alex@example.com."))
    }

    @Test
    fun personalRoleplayAndUnsupportedCaptureFactsAreRejected() {
        val question = "Hi, who is Napoleon?"
        assertNull(AiText.checkedAnswer(question, "Hi, I am Napoleon. I am a local park ranger."))
        assertNull(AiText.checkedAnswer(question, "The mayor is a local official."))
        assertEquals("Napoleon Bonaparte was a French military leader and emperor.",
            AiText.checkedAnswer(question, "Napoleon Bonaparte was a French military leader and emperor."))
        assertNull(AiText.checkedAnswer("How much?", "The boots cost 900 rupees.", "Boots: 750 rupees"))
    }
    @Test
    fun arithmeticRunsLocallyAndOnlyRecognizesArithmeticQuestions() {
        assertEquals("5", AiText.deviceAnswer("2 plus 3"))
        assertEquals("5", AiText.deviceAnswer("What is 2+3?"))
        assertEquals("2.5", AiText.deviceAnswer("5 divided by 2"))
        assertNull(AiText.deviceAnswer("Remember to buy 2 plus 3 pens"))
        assertFalse(AiText.needsWeb("What is rain?"))
        assertTrue(AiText.needsWeb("Will it rain tomorrow?"))
    }

    @Test
    fun naturalAnswersAndUsefulShortRewritesPassTheChecks() {
        assertEquals("who is Napoleon?", AiText.answerPrompt("Hi, who is Napoleon?"))
        val answer = "He was a French military commander.\nHe served as emperor."
        assertEquals(answer, AiText.checkedAnswer("Hi, who was Napoleon?", answer))
        assertFalse(AiText.isBareFragment("send me the report"))
        assertEquals("Please provide the report.", AiText.checkedRewrite("send me the report", "\"Please provide the report.\""))
        assertEquals("Remind Alex at 5 PM with alex@example.com",
            AiText.checkedRewrite("remind Alex at 5 PM with alex@example.com", "Subject: Remind Alex at 5 PM with alex@example.com"))
        assertEquals("This is a test.", AiText.inlinePolish("this is a test."))
    }
    @Test
    fun proactiveAnswersRecognizeCompleteQuestionsAndWeather() {
        assertEquals("who was Napoleon?", AiText.proactiveQuestion("Hey, who was Napoleon?"))
        assertNull(AiText.proactiveQuestion("Who was"))
        assertNull(AiText.proactiveQuestion("I sent a note. Who was Napoleon?"))
        val today = LocalDate.of(2026, 11, 21)
        assertEquals(today.plusDays(1), AiText.weatherLookup("Hey, how is the weather tomorrow?", today)?.date)
    }
}
