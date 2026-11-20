package com.notokens.monolog.ime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.matchingJournalEntries
import com.notokens.monolog.ui.GlassAccent
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.markdownExcerpt
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun KeyboardSearchPanel(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    onWebSearch: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { MonologData.repository(context) }
    val notes by repository.notes.collectAsState(initial = emptyList())
    val matches = remember(notes, query.text) { notes.matchingJournalEntries(query.text, limit = 3) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .glassSurface(26, GlassAccent.LAVENDER).padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonologIcon(Glyph.BOLT, Modifier.size(20.dp))
            Text("Monolog AI", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = PaperKeyText)
            IconControl(Glyph.CLOSE, "Close search", onClose, frosted = true)
        }
        Row(
            Modifier.fillMaxWidth().glassSurface(20).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MonologIcon(Glyph.SEARCH, Modifier.size(17.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                readOnly = true,
                singleLine = true,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search query" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = PaperKeyText),
                decorationBox = { inner ->
                    Box {
                        if (query.text.isEmpty()) Text("Search your journal or the web", color = PaperKeySubText)
                        inner()
                    }
                }
            )
        }
        if (query.text.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 80.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (matches.isEmpty()) {
                    Text("No journal matches", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                } else matches.forEach { note ->
                    Text(note.title, color = PaperKeyText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val preview = remember(note.body) { markdownExcerpt(note.body).take(180) }
                    Text(preview, color = PaperKeySubText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton({ onWebSearch(query.text) }, Modifier.align(Alignment.End)) { Text("Search web") }
        }
    }
}
