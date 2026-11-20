package com.notokens.monolog.ime.shortcuts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ShortcutDraftTest {
    @Test fun reminderTypingReplacesSelectionAndKeepsCursor() {
        val draft = ShortcutDraft(page = ShortcutPage.REMINDER, title = TextFieldValue("Call Sam", TextRange(5, 8)))
            .insert("Amma").insert(" ").insert("today")
        assertEquals("Call Amma today", draft.title.text)
        assertEquals(TextRange(15), draft.title.selection)
        assertEquals("", draft.body.text)
    }
    @Test fun backspaceDeletesWholeEmojiAndSelectedText() {
        val draft = ShortcutDraft(page = ShortcutPage.REMINDER, title = TextFieldValue("Hi😊", TextRange(4)))
        assertEquals("Hi", draft.backspace().title.text)
        assertEquals("", draft.copy(title = TextFieldValue("Hi", TextRange(0, 2))).backspace().title.text)
        assertEquals("Hi", draft.copy(title = TextFieldValue("Hi", TextRange.Zero)).backspace().title.text)
    }
    @Test fun noteEnterMovesToBodyAndThenAddsNewlines() {
        val draft = ShortcutDraft(page = ShortcutPage.NOTE).insert("A thought").enter().insert("First line").enter().insert("Second line")
        assertEquals("A thought", draft.title.text)
        assertEquals("First line\nSecond line", draft.body.text)
        assertEquals(DraftField.BODY, draft.field)
    }
    @Test fun changingFieldPreservesBothDrafts() {
        val draft = ShortcutDraft(page = ShortcutPage.NOTE).insert("Title")
            .copy(field = DraftField.BODY).insert("Body")
            .copy(field = DraftField.TITLE).backspace()
        assertEquals("Titl", draft.title.text)
        assertEquals("Body", draft.body.text)
    }
    @Test fun reminderEnterDoesNotSubmitOrChangeTitle() {
        val draft = ShortcutDraft(page = ShortcutPage.REMINDER).insert("Call Amma")
        assertEquals(draft, draft.enter())
        assertFalse(ShortcutDraft().isEditing)
        assertTrue(draft.isEditing)
    }
}
