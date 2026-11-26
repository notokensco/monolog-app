package com.notokens.monolog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookInstructionTest {
    private val books = listOf(JournalBook(7, "Flower"))

    @Test fun filesANoteUsingTheLastLine() {
        assertEquals("Water the lilies." to BookPlacement(7),
            bookInstruction("Water the lilies.\nAdd this note to the flower book.", books))
    }

    @Test fun leavesUnrecognizedOrEmbeddedInstructionsAsNoteText() {
        assertNull(bookInstruction("Add this note to the missing book", books))
        assertNull(bookInstruction("Add this note to the flower book\nThese are draft examples.", books))
    }
}
