package com.notokens.monolog.capture

import com.notokens.monolog.data.Note
import com.notokens.monolog.data.matchingJournalEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEntryTest {
    @Test fun shoppingCaptureIsCategorizedAndSearchable() {
        val entry = buildCaptureEntry("10:47 AM\nMyntra\n[Trail shoes]\nShopping cart subtotal")
        val note = Note(title = entry.title, body = entry.body)

        assertEquals("Myntra", entry.title)
        assertEquals("Shopping", entry.category)
        assertTrue(entry.body.contains("> \\[Trail shoes\\]"))
        assertEquals(listOf(note), listOf(note).matchingJournalEntries("shopping trail shoes"))
        assertTrue(listOf(note).matchingJournalEntries("trail jacket").isEmpty())
    }

    @Test fun captureWithoutTextStillHasAFallbackCategory() {
        val entry = buildCaptureEntry("\n  ")

        assertEquals("Screen capture", entry.title)
        assertEquals("**Category:** Screen capture", entry.body)
    }
}
