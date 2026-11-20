package com.notokens.monolog.ime.shortcuts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ui.*
import com.notokens.monolog.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class KeyboardLocalActions(private val context: android.content.Context) {
    private val repository = MonologData.repository(context)
    suspend fun saveNote(editor: EditorContext, title: String, body: String): Long {
        require(!editor.isIncognito) { "Saving is unavailable in sensitive or incognito fields" }
        return repository.saveNote(title, body)
    }
    suspend fun saveReminder(editor: EditorContext, title: String, dueAt: Long): Long {
        require(!editor.isIncognito) { "Saving is unavailable in sensitive or incognito fields" }
        return repository.saveReminder(title, dueAt)
    }
}

@Composable
fun QuickShortcutsPanel(
    draft: ShortcutDraft,
    onDraftChange: (ShortcutDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    editorContext: EditorContext = EditorContext.default()
) {
    val context = LocalContext.current
    val actions = remember { KeyboardLocalActions(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var error by remember(draft.page) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var pickingTime by remember(draft.page) { mutableStateOf(false) }
    val isNote = draft.page == ShortcutPage.NOTE
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val panelHeight = (screenHeight - 220).coerceIn(100, 290).dp

    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = if (draft.isEditing) 600.dp else 330.dp).fillMaxWidth()
            .glassSurface(if (draft.isEditing) 28 else 24, GlassAccent.LAVENDER)
            .heightIn(max = panelHeight).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!draft.isEditing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick shortcuts", color = PaperKeyText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconControl(Glyph.CLOSE, "Close shortcuts", onClose)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile("Reminder", Glyph.BELL, Modifier.weight(1f)) { onDraftChange(ShortcutDraft(page = ShortcutPage.REMINDER)) }
                    ShortcutTile("Alarm", Glyph.ALARM, Modifier.weight(1f)) { onDraftChange(ShortcutDraft(page = ShortcutPage.ALARM)) }
                    ShortcutTile("Journal", null, Modifier.weight(1f)) {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_JOURNAL
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                        onClose()
                    }
                }
                Text("+ Save a note", color = PaperKeySubText, fontSize = 13.sp,
                    modifier = Modifier.clickable { onDraftChange(ShortcutDraft(page = ShortcutPage.NOTE)) }.padding(4.dp))
            } else {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(3.dp).background(PaperKeySubText.copy(alpha = .35f)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MonologIcon(if (isNote) Glyph.BOOK else Glyph.CLOCK)
                    Spacer(Modifier.width(10.dp))
                    Text(if (isNote) "Save to Journal" else if (draft.page == ShortcutPage.ALARM) "Alarm reminder" else "New reminder",
                        color = PaperKeyText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconControl(Glyph.CLOSE, "Cancel draft", onClose)
                }
                DraftInput(draft, DraftField.TITLE, if (isNote) "Title" else "What should I remind you?", onDraftChange)
                if (isNote) {
                    DraftInput(draft, DraftField.BODY, "Write your note…", onDraftChange)
                } else {
                    Row(Modifier.fillMaxWidth().glassSurface(22).clickable { pickingTime = !pickingTime }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonologIcon(Glyph.CLOCK)
                        Spacer(Modifier.width(12.dp))
                        Text(formatTime(draft.dueAt), color = PaperKeyText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        MonologIcon(Glyph.DOWN, Modifier.size(18.dp))
                    }
                    if (pickingTime) {
                        val zoned = Instant.ofEpochMilli(draft.dueAt).atZone(ZoneId.systemDefault())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TimeStepper("Day", zoned.format(DateTimeFormatter.ofPattern("d MMM")), { onDraftChange(draft.copy(dueAt = zoned.minusDays(1).toInstant().toEpochMilli())) }, { onDraftChange(draft.copy(dueAt = zoned.plusDays(1).toInstant().toEpochMilli())) })
                            TimeStepper("Hour", zoned.format(DateTimeFormatter.ofPattern("h a")), { onDraftChange(draft.copy(dueAt = zoned.minusHours(1).toInstant().toEpochMilli())) }, { onDraftChange(draft.copy(dueAt = zoned.plusHours(1).toInstant().toEpochMilli())) })
                            TimeStepper("Minute", zoned.format(DateTimeFormatter.ofPattern("mm")), { onDraftChange(draft.copy(dueAt = zoned.minusMinutes(1).toInstant().toEpochMilli())) }, { onDraftChange(draft.copy(dueAt = zoned.plusMinutes(1).toInstant().toEpochMilli())) })
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Button(modifier = Modifier.fillMaxWidth().height(44.dp), enabled = !saving && (if (isNote) draft.title.text.isNotBlank() || draft.body.text.isNotBlank() else draft.title.text.isNotBlank() && draft.dueAt > System.currentTimeMillis()), onClick = {
                    saving = true
                    scope.launch {
                        try {
                            if (isNote) actions.saveNote(editorContext, draft.title.text, draft.body.text)
                            else actions.saveReminder(editorContext, draft.title.text, draft.dueAt)
                            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            Toast.makeText(context, if (!isNote && !allowed) "Saved. Enable notifications in Monolog to receive alerts." else if (isNote) "Saved to Journal" else "Reminder set", Toast.LENGTH_LONG).show()
                            onClose()
                        } catch (e: Exception) {
                            error = e.message ?: "Could not save. Please try again."
                        } finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else if (isNote) "Save note" else "Set reminder", fontSize = 16.sp) }
                Text(if (isNote) "Saved privately, on this device." else "Set alarms and reminders from any app.",
                    color = PaperKeySubText, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun DraftInput(draft: ShortcutDraft, field: DraftField, placeholder: String, onChange: (ShortcutDraft) -> Unit) {
    val active = draft.field == field
    val value = if (field == DraftField.TITLE) draft.title else draft.body
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val cursorColor = PaperKeyText
    Column(Modifier.fillMaxWidth().glassSurface(20).clickable { onChange(draft.copy(field = field)) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(if (active) "$placeholder · Typing here" else placeholder, color = PaperKeySubText, fontSize = 11.sp)
        // readOnly prevents Compose from opening a second IME connection. Selection is
        // still editable; the service routes its keys into this TextFieldValue instead.
        BasicTextField(value = value, onTextLayout = { textLayout = it }, onValueChange = { newValue ->
            onChange(if (field == DraftField.TITLE) draft.copy(field = field, title = newValue) else draft.copy(field = field, body = newValue))
        }, readOnly = true, textStyle = TextStyle(color = PaperKeyText, fontSize = 17.sp),
            singleLine = field == DraftField.TITLE, maxLines = if (field == DraftField.TITLE) 1 else 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp).onFocusChanged {
                if (it.isFocused && draft.field != field) onChange(draft.copy(field = field))
            }.drawWithContent {
                drawContent()
                if (active && value.selection.collapsed) textLayout?.let { layout ->
                    val cursor = layout.getCursorRect(value.selection.end.coerceAtMost(layout.layoutInput.text.length))
                    drawLine(cursorColor, Offset(cursor.left, cursor.top), Offset(cursor.left, cursor.bottom), 1.dp.toPx())
                }
            },
            decorationBox = { inner ->
                Box { if (value.text.isEmpty() && !active) Text("Tap to write", color = PaperKeySubText, fontSize = 17.sp); inner() }
            })
    }
}

@Composable
private fun ShortcutTile(label: String, icon: Glyph?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.glassSurface(20).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon == null) MonologLogo(Modifier.size(30.dp))
        else MonologIcon(icon, Modifier.size(30.dp))
        Spacer(Modifier.height(9.dp))
        Text(label, color = PaperKeyText, fontSize = 13.sp)
    }
}

@Composable
private fun TimeStepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = PaperKeySubText, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("−", color = PaperKeyText, modifier = Modifier.clickable(onClick = minus).padding(8.dp))
            Text(value, color = PaperKeyText, fontSize = 13.sp)
            Text("+", color = PaperKeyText, modifier = Modifier.clickable(onClick = plus).padding(8.dp))
        }
    }
}
