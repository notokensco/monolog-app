package com.notokens.monolog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notokens.monolog.data.JournalBooks
import kotlinx.coroutines.launch

@Composable
internal fun BookPicker(selected: Long?, label: String, onChoose: (Long?) -> Unit, onDismiss: () -> Unit,
    emptyLabel: String = "No book") {
    val context = LocalContext.current
    val store = remember(context) { JournalBooks(context) }
    val books by store.books.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (creating) "Create a book" else label) },
        shape = RoundedCornerShape(20.dp),
        text = {
            if (creating) Column {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Book name") }, singleLine = true)
                if (failed) Text("Couldn’t create this book.", color = MaterialTheme.colorScheme.error)
            } else Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                for ((id, title) in listOf(null to emptyLabel) + books.map { it.id to it.name }) {
                    FilterChip(selected == id, { onChoose(id); onDismiss() }, { Text(title) })
                }
            }
        }, confirmButton = {
            if (creating) TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        onChoose(store.create(name))
                        onDismiss()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }) { Text("Create") }
            else TextButton({ creating = true }) { Text("New book") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
