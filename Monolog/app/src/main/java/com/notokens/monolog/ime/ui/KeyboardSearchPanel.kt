package com.notokens.monolog.ime.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.notokens.monolog.MainActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.ai.WebSearchKind
import com.notokens.monolog.ui.GlassAccent
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun KeyboardSearchPanel(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    ai: ImeAi,
    availableHeight: Dp
) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(query.text, query.selection) {
        caretOn = true
        while (true) {
            delay(510)
            caretOn = !caretOn
        }
    }
    val caretColor = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
            .glassSurface(26, GlassAccent.LAVENDER)
            .heightIn(max = minOf(availableHeight - 8.dp, 350.dp)).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
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
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search query" }
                    .drawWithContent {
                        drawContent()
                        val textLayout = layout
                        if (caretOn && query.selection.collapsed && textLayout != null) {
                            val offset = query.selection.end.coerceIn(0, textLayout.layoutInput.text.length)
                            val rect = textLayout.getCursorRect(offset)
                            drawLine(caretColor, Offset(rect.left, rect.top), Offset(rect.left, rect.bottom), 1.5.dp.toPx())
                        }
                    },
                onTextLayout = { layout = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = PaperKeyText),
                decorationBox = { inner ->
                    Box {
                        if (query.text.isEmpty()) Text(if (ai.personal) "Search your journal and captures" else "Ask offline", color = PaperKeySubText)
                        inner()
                    }
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ai.personalAllowed) TextButton(ai::togglePersonal,
                modifier = Modifier.semantics { selected = ai.personal }) {
                Text(if (ai.personal) "Personal on" else "Personal")
            }
            if (ai.webAllowed) TextButton(ai::toggleWeb) {
                Text(if (ai.web) "Web on" else "Web off")
            }
            TextButton({ ai.ask(query.text) }, enabled = query.text.isNotBlank() && !ai.busy) {
                Text(if (ai.web) "Search" else "Ask")
            }
        }
        if (ai.busy) com.notokens.monolog.ui.ProcessingDots()
        if (ai.askActions.isNotEmpty()) Row { DeviceActionButtons(ai.askActions) }
        if (ai.web && ai.webAllowed) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safe Search", style = MaterialTheme.typography.labelLarge, color = PaperKeyText)
                    Text(WebLookup.searchProvider?.label ?: "In-keyboard search unavailable",
                        style = MaterialTheme.typography.labelSmall, color = PaperKeySubText)
                }
                Switch(checked = ai.safeSearch, onCheckedChange = ai::setWebSafeSearch,
                    modifier = Modifier.semantics { contentDescription = "Safe Search" })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (WebLookup.searchProvider != null) {
                    TextButton({ ai.ask(query.text, WebSearchKind.IMAGES) }, enabled = query.text.isNotBlank() && !ai.busy) {
                        Text("Images")
                    }
                }
                TextButton({ ai.searchWeb(query.text, ai.preview?.webKind ?: WebSearchKind.WEB) },
                    enabled = query.text.isNotBlank()) { Text("More in browser") }
            }
        }
        ai.preview?.let { result ->
            if (result.webKind == null) AiResultCard(result, ai::apply)
            else if (ai.web && ai.webAllowed) AskWebResults(result, ai::apply)
        }
        if (ai.webAllowed && !ai.web && !ai.personal && ai.preview?.text == com.notokens.monolog.ai.AiText.UNKNOWN_ANSWER) {
            TextButton({
                ai.toggleWeb()
                ai.ask(query.text)
            }, enabled = query.text.isNotBlank() && !ai.busy) {
                Text("Search web")
            }
        }
        if (ai.personal && ai.personalAllowed) {
            for (match in ai.personalMatches) {
                Column(Modifier.fillMaxWidth().clickable {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_PERSONAL_MATCH
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        match.noteId?.let { putExtra("note", it) }
                        match.reminderId?.let { putExtra("reminder", it) }
                        match.day?.let { putExtra("day", it) }
                    }
                    context.startActivity(intent)
                    onClose()
                }.padding(vertical = 6.dp)) {
                    Text(match.title, color = PaperKeyText, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(match.excerpt, color = PaperKeySubText, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
