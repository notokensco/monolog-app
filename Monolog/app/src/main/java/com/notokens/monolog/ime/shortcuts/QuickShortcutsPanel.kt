package com.notokens.monolog.ime.shortcuts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.ime.EditorContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Explicit, private entry points for the IME panel and future M-key actions. */
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

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun QuickShortcutsPanel(onClose: () -> Unit, modifier: Modifier = Modifier, editorContext: EditorContext = EditorContext.default()) {
    val context = LocalContext.current
    val actions = remember { KeyboardLocalActions(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("menu") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf(LocalDateTime.now().plusHours(1).format(timeFormat)) }
    var error by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().background(Color(0xFFF4F5FF)).padding(12.dp)) {
        when (page) {
            "menu" -> {
                Text("Quick shortcuts")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { page = "reminder" }) { Text("Reminder") }
                    Button(onClick = { page = "alarm" }) { Text("Alarm") }
                    Button(onClick = { page = "note" }) { Text("Save note") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_JOURNAL
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                        onClose()
                    }) { Text("Journal") }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
            "note" -> {
                Text("Save to Journal")
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, color = Color.Red)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        scope.launch {
                            try {
                                actions.saveNote(editorContext, title, body)
                                Toast.makeText(context, "Saved to Journal", Toast.LENGTH_SHORT).show()
                                onClose()
                            } catch (e: IllegalArgumentException) { error = e.message ?: "Could not save note" }
                        }
                    }, enabled = title.isNotBlank() || body.isNotBlank()) { Text("Save") }
                    OutlinedButton(onClick = { page = "menu" }) { Text("Cancel") }
                }
            }
            else -> {
                Text(if (page == "alarm") "Set an alarm reminder" else "Set a private reminder")
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(timeText, { timeText = it }, label = { Text("Date and time · yyyy-MM-dd HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, color = Color.Red)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        val dueAt = runCatching { LocalDateTime.parse(timeText, timeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                        if (dueAt == null || dueAt <= System.currentTimeMillis()) {
                            error = "Choose a valid future date and time"
                        } else {
                            scope.launch {
                                try {
                                    actions.saveReminder(editorContext, title, dueAt)
                                    val message = if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                                        "Reminder saved in Monolog" else "Saved, but notifications are disabled. Allow them in Monolog settings."
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    onClose()
                                } catch (e: IllegalArgumentException) { error = e.message ?: "Could not save reminder" }
                            }
                        }
                    }, enabled = title.isNotBlank()) { Text("Save") }
                    OutlinedButton(onClick = { page = "menu" }) { Text("Cancel") }
                }
            }
        }
    }
}
