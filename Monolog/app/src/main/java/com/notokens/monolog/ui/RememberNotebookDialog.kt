package com.notokens.monolog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.notokens.monolog.R
import com.notokens.monolog.data.RememberEntry
import com.notokens.monolog.data.RememberLanguage
import com.notokens.monolog.data.RememberNotebook
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RememberNotebookButton() {
    var visible by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().glassSurface(22).clickable(onClickLabel = "Open Remember notebook") { visible = true }
        .heightIn(min = 74.dp)) {
        CardCornerArtwork(R.drawable.art_journal, 93.dp, opacity = .58f)
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.75f).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("Remember", color = PaperKeyText, style = MaterialTheme.typography.titleMedium)
            Text("Facts you asked Monolog to keep", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        }
        MonologIcon(Glyph.ARROW, Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp))
    }
    if (visible) RememberNotebookDialog { visible = false }
}

@Composable
private fun RememberNotebookDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val notebook = remember(context) { RememberNotebook(context) }
    val entries by notebook.entries.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var entryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<RememberEntry?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun edit(entry: RememberEntry? = null) {
        entryId = entry?.id
        text = entry?.body.orEmpty()
        error = null
        editing = true
    }
    if (deleteEntry != null) AlertDialog(onDismissRequest = { deleteEntry = null },
        title = { Text("Delete this entry?") }, text = { Text("It will be removed from your Remember notebook.") },
        confirmButton = { TextButton({
            val id = deleteEntry?.id ?: return@TextButton
            deleteEntry = null
            scope.launch {
                try { notebook.delete(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn’t delete the entry." }
            }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleteEntry = null }) { Text("Cancel") } })

    Dialog(onDismissRequest = { if (!saving && deleteEntry == null) onClose() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (editing) "Remember entry" else "Remember", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconControl(Glyph.CLOSE, "Close Remember notebook", onClose, enabled = !saving)
                }
                if (editing) {
                    OutlinedTextField(text, { text = it.take(RememberLanguage.MAX_LENGTH) }, Modifier.fillMaxWidth(),
                        label = { Text("What should Monolog remember?") }, minLines = 3, maxLines = 5,
                        shape = RoundedCornerShape(16.dp), enabled = !saving)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ editing = false; error = null }, enabled = !saving) { Text("Cancel") }
                        Button(enabled = text.isNotBlank() && !saving, onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    if (notebook.save(text, entryId)) { editing = false; error = null }
                                    else error = "This entry couldn’t be saved with the current privacy settings."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Couldn’t save the entry." }
                                finally { saving = false }
                            }
                        }) { Text("Save") }
                    }
                } else {
                    if (entries.isEmpty()) Text("Add a fact, or start with “Remember” while typing or dictating.", color = PaperKeySubText)
                    else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            Column(Modifier.fillMaxWidth().glassSurface(17).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(entry.body, color = PaperKeyText)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton({ edit(entry) }) { Text("Edit") }
                                    TextButton({ deleteEntry = entry }) { Text("Delete") }
                                }
                            }
                        }
                    }
                    Button(onClick = { edit() }) { Text("Add entry") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
