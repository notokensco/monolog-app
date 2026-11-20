package com.notokens.monolog.ime.shortcuts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

enum class ShortcutPage { MENU, REMINDER, ALARM, NOTE }
enum class DraftField { TITLE, BODY }

/** The IME owns this editor; its keystrokes must never use the host InputConnection. */
data class ShortcutDraft(
    val page: ShortcutPage = ShortcutPage.MENU,
    val field: DraftField = DraftField.TITLE,
    val title: TextFieldValue = TextFieldValue(),
    val body: TextFieldValue = TextFieldValue(),
    val dueAt: Long = System.currentTimeMillis() + 3_600_000
) {
    val isEditing: Boolean get() = page != ShortcutPage.MENU
    val activeValue: TextFieldValue get() = if (this.field == DraftField.TITLE) title else body
    fun update(value: TextFieldValue): ShortcutDraft = if (field == DraftField.TITLE) copy(title = value) else copy(body = value)
    fun insert(text: String): ShortcutDraft {
        val value = activeValue
        val start = value.selection.min
        val end = value.selection.max
        return update(TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length)))
    }
    fun backspace(): ShortcutDraft {
        val value = activeValue
        val start = value.selection.min
        val end = value.selection.max
        if (start != end) return insert("")
        if (start == 0) return this
        val previous = Character.offsetByCodePoints(value.text, start, -1)
        return update(TextFieldValue(value.text.removeRange(previous, start), TextRange(previous)))
    }
    fun enter(): ShortcutDraft = if (page == ShortcutPage.NOTE && field == DraftField.TITLE) copy(field = DraftField.BODY)
        else if (page == ShortcutPage.NOTE) insert("\n") else this
}
