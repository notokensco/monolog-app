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
}
