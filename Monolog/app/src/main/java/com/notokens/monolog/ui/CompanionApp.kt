package com.notokens.monolog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.Reminder
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.setup.SetupScreen
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

private val ink = Color(0xFF121B33)
private val muted = Color(0xFF52607F)
private val background = Color(0xFFE8ECFC)
private val card = Color(0xFFF7F8FF)

fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

fun pickReminderTime(context: Context, initial: Long = System.currentTimeMillis() + 3_600_000, selected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            val result = Calendar.getInstance().apply {
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selected(result.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun CompanionApp(startRoute: String, onRequestMicPermission: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { MonologData.repository(context) }
    val settings = remember { SettingsStore(context.applicationContext) }
    val notes by repo.notes.collectAsState(initial = emptyList())
    val reminders by repo.reminders.collectAsState(initial = emptyList())
    val incognito by settings.incognito.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear local data?") },
        text = { Text("This deletes all notes and reminders from this device and cancels their notifications.") },
        confirmButton = { Button(onClick = {
            scope.launch {
                repo.clearAllData()
                settings.setIncognito(false)
                confirmClear = false
            }
        }) { Text("Clear data") } },
        dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Color(0xFF4263B5))) {
        Scaffold(
            containerColor = background,
            bottomBar = {
                Row(Modifier.fillMaxWidth().background(card).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Today", "Journal", "Settings").forEach { label ->
                        Text(label, Modifier.clickable {
                            nav.navigate(label.lowercase()) { launchSingleTop = true; popUpTo("today") }
                        }.padding(8.dp), color = ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(padding)) {
                composable("today") {
                    Screen("Your day, remembered.") {
                        Text("On-device · Private by default", color = muted)
                        Spacer(Modifier.height(16.dp))
                        Tile("Today's journal", "Your notes, ready when you need them.") { nav.navigate("journal") }
                        Spacer(Modifier.height(12.dp))
                        val next = reminders.firstOrNull { it.dueAt > System.currentTimeMillis() }
                        Tile("Next reminder", next?.let { "${it.title} · ${formatTime(it.dueAt)}" } ?: "No upcoming reminders") { nav.navigate("reminders") }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { nav.navigate("reminders/new") }) { Text("+ Add reminder") }
                        Spacer(Modifier.height(16.dp))
                        Text("Recent notes", color = ink, style = MaterialTheme.typography.titleLarge)
                        if (notes.isEmpty()) Text("Nothing saved yet. Add a note to start your journal.", color = muted)
                        notes.take(3).forEach { note ->
                            Tile(note.title, note.body.ifBlank { formatTime(note.updatedAt) }) { nav.navigate("note/${note.id}") }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                composable("journal") {
                    Screen("Journal") {
                        Text("A private timeline of what you chose to save", color = muted)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { nav.navigate("note/new") }) { Text("+ Add note") }
                        if (notes.isEmpty()) Text("No notes yet. Save a thought to see it here.", Modifier.padding(top = 20.dp), color = muted)
                        notes.forEach { note ->
                            Spacer(Modifier.height(10.dp))
                            Tile(note.title, "${formatTime(note.updatedAt)}\n${note.body.take(100)}") { nav.navigate("note/${note.id}") }
                        }
                    }
                }
                composable("note/new") { NoteEditor(null, onSave = { title, body ->
                    scope.launch { repo.saveNote(title, body); nav.popBackStack() }
                }, onCancel = { nav.popBackStack() }) }
                composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments!!.getLong("id")
                    val note = notes.firstOrNull { it.id == id }
                    if (note != null) Screen(note.title) {
                        Text(formatTime(note.updatedAt), color = muted)
                        Spacer(Modifier.height(18.dp))
                        Text(note.body, color = ink)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.navigate("note/edit/$id") }) { Text("Edit") }
                            OutlinedButton(onClick = { scope.launch { repo.deleteNote(note); nav.popBackStack() } }) { Text("Delete") }
                        }
                    }
                }
                composable("note/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val note = notes.firstOrNull { it.id == entry.arguments!!.getLong("id") }
                    if (note != null) NoteEditor(note, onSave = { title, body ->
                        scope.launch { repo.saveNote(title, body, note); nav.popBackStack() }
                    }, onCancel = { nav.popBackStack() })
                }
                composable("reminders") {
                    Screen("Reminders") {
                        Button(onClick = { nav.navigate("reminders/new") }) { Text("+ Add reminder") }
                        if (reminders.isEmpty()) Text("No reminders yet.", Modifier.padding(top = 20.dp), color = muted)
                        reminders.forEach { reminder ->
                            Spacer(Modifier.height(10.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(reminder.title, color = ink, fontWeight = FontWeight.Bold)
                                    Text(formatTime(reminder.dueAt), color = muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { nav.navigate("reminders/edit/${reminder.id}") }) { Text("Edit") }
                                        OutlinedButton(onClick = { scope.launch { repo.deleteReminder(reminder) } }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
                composable("reminders/new") { ReminderEditor(null, onSave = { title, due ->
                    scope.launch { repo.saveReminder(title, due); nav.popBackStack() }
                }, onCancel = { nav.popBackStack() }) }
                composable("reminders/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val reminder = reminders.firstOrNull { it.id == entry.arguments!!.getLong("id") }
                    if (reminder != null) ReminderEditor(reminder, onSave = { title, due ->
                        scope.launch { repo.saveReminder(title, due, reminder); nav.popBackStack() }
                    }, onCancel = { nav.popBackStack() })
                }
                composable("settings") {
                    Screen("Settings") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Incognito", color = ink, style = MaterialTheme.typography.titleMedium)
                            Switch(checked = incognito, onCheckedChange = { scope.launch { settings.setIncognito(it) } })
                        }
                        Text("When on, keyboard shortcuts and memory saving pause. Password and sensitive fields are excluded even when incognito is off. Monolog does not silently collect text from other apps; only content you explicitly save is stored locally.", color = muted)
                        Spacer(Modifier.height(20.dp))
                        Text("Keyboard setup", color = ink, style = MaterialTheme.typography.titleLarge)
                        SetupScreen(onRequestMicPermission = onRequestMicPermission)
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear local data") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("m  Monolog  ·  On-device", color = ink, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        Text(title, color = ink, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun Tile(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = card), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = muted)
        }
    }
}

@Composable
private fun NoteEditor(note: Note?, onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var title by remember(note?.id) { mutableStateOf(note?.title ?: "") }
    var body by remember(note?.id) { mutableStateOf(note?.body ?: "") }
    Screen(if (note == null) "New note" else "Edit note") {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(body, { body = it }, label = { Text("Your note") }, minLines = 6, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(title, body) }, enabled = title.isNotBlank() || body.isNotBlank()) { Text("Save note") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReminderEditor(reminder: Reminder?, onSave: (String, Long) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var title by remember(reminder?.id) { mutableStateOf(reminder?.title ?: "") }
    var dueAt by remember(reminder?.id) { mutableStateOf(reminder?.dueAt ?: 0L) }
    Screen(if (reminder == null) "New reminder" else "Edit reminder") {
        OutlinedTextField(title, { title = it }, label = { Text("What should I remind you?") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { pickReminderTime(context, dueAt.takeIf { it > 0 } ?: System.currentTimeMillis() + 3_600_000) { dueAt = it } }) {
            Text(if (dueAt == 0L) "Choose date and time" else formatTime(dueAt))
        }
        if (dueAt != 0L && dueAt <= System.currentTimeMillis()) Text("Choose a future time.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("A local notification will appear at this time if notifications are allowed.", color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(title, dueAt) }, enabled = title.isNotBlank() && dueAt > System.currentTimeMillis()) { Text("Save reminder") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
