package com.notokens.monolog.ime.shortcuts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.QuickShortcut
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ui.*
import com.notokens.monolog.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
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
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()
            .glassSurface(28, GlassAccent.LAVENDER)
            .heightIn(max = panelHeight).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

@Composable
internal fun QuickShortcutsMenu(
    panelHeight: Dp,
    onDraftChange: (ShortcutDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context.applicationContext) }
    val shortcuts by settings.quickShortcutOrder.collectAsState(initial = QuickShortcut.entries)
    val scope = rememberCoroutineScope()
    var editingOrder by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val shape = remember(density) {
        with(density) {
            val corner = 23.dp.toPx()
            val tailHeight = 14.dp.toPx()
            val tailHalf = 12.dp.toPx()
            val inset = 24.dp.toPx()
            GenericShape { size, direction ->
                val right = size.width
                val base = size.height - tailHeight
                val tip = if (direction == LayoutDirection.Ltr) right - inset else inset
                val radius = minOf(corner, right / 6f, base / 2f)
                val bottomRadius = minOf(radius, inset - tailHalf)
                moveTo(radius, 0f)
                lineTo(right - radius, 0f)
                quadraticTo(right, 0f, right, radius)
                lineTo(right, base - bottomRadius)
                quadraticTo(right, base, right - bottomRadius, base)
                lineTo(tip + tailHalf, base)
                quadraticTo(tip + tailHalf * .65f, base, tip, size.height)
                quadraticTo(tip - tailHalf * .65f, base, tip - tailHalf, base)
                lineTo(bottomRadius, base)
                quadraticTo(0f, base, 0f, base - bottomRadius)
                lineTo(0f, radius)
                quadraticTo(0f, 0f, radius, 0f)
                close()
            }
        }
    }

    Box(modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 300.dp).fillMaxWidth().heightIn(max = panelHeight)
                .shadow(7.dp, shape, clip = false).clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f))
                .glassSurface(accent = GlassAccent.LAVENDER, shape = shape)
                .padding(bottom = 14.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quick shortcuts", color = PaperKeyText, fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { editingOrder = !editingOrder }) {
                    if (!editingOrder) {
                        MonologIcon(Glyph.EDIT, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(if (editingOrder) "Done" else "Edit", color = PaperKeyText, fontSize = 13.sp)
                }
            }

            if (editingOrder) {
                ShortcutOrderEditor(shortcuts) { shortcut, offset ->
                    scope.launch { settings.moveQuickShortcut(shortcut, offset) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    shortcuts.forEach { shortcut ->
                        ShortcutTile(shortcut, Modifier.weight(1f)) {
                            when (shortcut) {
                                QuickShortcut.REMINDER -> onDraftChange(ShortcutDraft(page = ShortcutPage.REMINDER))
                                QuickShortcut.ALARM -> onDraftChange(ShortcutDraft(page = ShortcutPage.ALARM))
                                QuickShortcut.JOURNAL -> {
                                    context.startActivity(Intent(context, MainActivity::class.java).apply {
                                        action = MainActivity.ACTION_OPEN_JOURNAL
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    })
                                    onClose()
                                }
                            }
                        }
                    }
                }
            }

            TextButton(onClick = { onDraftChange(ShortcutDraft(page = ShortcutPage.NOTE)) }) {
                MonologIcon(Glyph.PAPER, Modifier.size(16.dp), color = PaperKeySubText)
                Spacer(Modifier.width(5.dp))
                Text("Save a note", color = PaperKeyText, fontSize = 13.sp)
            }
        }
    }
}

private data class ShortcutDrag(val from: Int, val distance: Float = 0f) {
    val offset: Int get() = distance.roundToInt()
}

@Composable
private fun ShortcutOrderEditor(shortcuts: List<QuickShortcut>, onMove: (QuickShortcut, Int) -> Unit) {
    val rowHeight = 48.dp
    val rowPixels = with(LocalDensity.current) { rowHeight.toPx() }
    val move by rememberUpdatedState(onMove)
    var drag by remember(shortcuts) { mutableStateOf<ShortcutDrag?>(null) }

    Column(Modifier.fillMaxWidth()) {
        shortcuts.forEachIndexed { index, shortcut ->
            key(shortcut) {
                val label = shortcut.label()
                val active = drag
                val isDragged = active?.from == index
                val translation = when {
                    active == null -> 0f
                    isDragged -> active.distance * rowPixels
                    index in (active.from + 1)..(active.from + active.offset) -> -rowPixels
                    index in (active.from + active.offset) until active.from -> rowPixels
                    else -> 0f
                }
                Row(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = translation }
                        .background(
                            if (isDragged) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(40.dp).fillMaxHeight()
                            .semantics { contentDescription = "Drag $label to reorder" }
                            .pointerInput(index, shortcuts, rowPixels) {
                                detectVerticalDragGestures(
                                    onDragStart = { drag = ShortcutDrag(index) },
                                    onDragCancel = { drag = null },
                                    onDragEnd = {
                                        val offset = drag?.offset ?: 0
                                        drag = null
                                        if (offset != 0) move(shortcut, offset)
                                    }
                                ) { change, delta ->
                                    change.consume()
                                    val current = drag ?: return@detectVerticalDragGestures
                                    drag = current.copy(distance = (current.distance + delta / rowPixels)
                                        .coerceIn(-index.toFloat(), (shortcuts.lastIndex - index).toFloat()))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { MonologIcon(Glyph.DRAG_HANDLE, Modifier.size(16.dp), color = PaperKeySubText) }
                    Text(label, Modifier.weight(1f), color = PaperKeyText, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    IconButton(
                        onClick = { move(shortcut, -1) }, enabled = active == null && index > 0,
                        modifier = Modifier.semantics { contentDescription = "Move $label up" }
                    ) { MonologIcon(Glyph.DOWN, Modifier.size(16.dp).rotate(180f), color = PaperKeySubText) }
                    IconButton(
                        onClick = { move(shortcut, 1) }, enabled = active == null && index < shortcuts.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move $label down" }
                    ) { MonologIcon(Glyph.DOWN, Modifier.size(16.dp), color = PaperKeySubText) }
                }
            }
        }
    }
}

private fun QuickShortcut.label(): String = when (this) {
    QuickShortcut.REMINDER -> "Reminder"
    QuickShortcut.ALARM -> "Alarm"
    QuickShortcut.JOURNAL -> "Journal"
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
private fun ShortcutTile(shortcut: QuickShortcut, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 82.dp).glassSurface(17).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.height(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (shortcut) {
                QuickShortcut.REMINDER -> MonologIcon(Glyph.BELL, Modifier.size(28.dp))
                QuickShortcut.ALARM -> MonologIcon(Glyph.ALARM, Modifier.size(28.dp))
                QuickShortcut.JOURNAL -> MonologMark(Modifier.requiredSize(58.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(shortcut.label(), color = PaperKeyText, fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (shortcut == QuickShortcut.JOURNAL) {
            Text("Open Monolog", color = PaperKeySubText, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
