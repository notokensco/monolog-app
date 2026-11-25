package com.notokens.monolog.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFormattingTest {
    @Test fun listFormattingUsesTheSelectedLines() {
        val original = TextFieldValue("Before\nFirst\nSecond\nAfter", TextRange(7, 20))
        val numbered = formatNote(original, NoteFormat.Numbers)
        assertEquals("Before\n1. First\n2. Second\nAfter", numbered.text)
        assertEquals("Before\n- [ ] First\n- [ ] Second\nAfter", formatNote(numbered, NoteFormat.Checklist).text)
        assertEquals(original.text, formatNote(numbered, NoteFormat.Numbers).text)
    }

    @Test fun enterContinuesWithAnUncheckedItemAndExitsAnEmptyList() {
        val text = "- [x] Done"
        val previous = TextFieldValue(text, TextRange(text.length))
        val next = TextFieldValue("$text\n", TextRange(text.length + 1))
        val continued = continueNoteList(previous, next)
        assertEquals("$text\n- [ ] ", continued.text)
        assertEquals("$text\n", continueNoteList(continued, TextFieldValue(continued.text + "\n", TextRange(continued.text.length + 1))).text)
    }

    @Test fun checklistRendersRealMarkdownAndUsesSourceOffsets() {
        val source = "Title\r\n\r\n- [x] **Done**\r\n- [ ] Next\r\n- bullet\r\n\r\n```\r\n- [x] code\r\n```"
        val tasks = parseNoteMarkdown(source).tasks.values.toList()
        assertEquals(listOf(MarkdownTask(source.indexOf("[x]") + 1, true), MarkdownTask(source.indexOf("[ ]") + 1, false)), tasks)
        assertEquals("Title - Done - Next - bullet - [x] code", markdownExcerpt(source))
    }
}
