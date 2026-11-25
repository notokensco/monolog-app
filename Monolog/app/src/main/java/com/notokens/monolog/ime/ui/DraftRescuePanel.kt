package com.notokens.monolog.ime.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ime.DraftRescue
import com.notokens.monolog.ime.RescueDraft
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import kotlinx.coroutines.launch

@Composable
internal fun DraftRescueButton(onClick: () -> Unit, badge: Boolean = false) {
    Box(
        Modifier.padding(horizontal = 3.dp).size(44.dp, 28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open Draft Rescue" },
        contentAlignment = Alignment.Center
    ) {
        Text("DRS", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error))
    }
}

@Composable
internal fun DraftRescuePanel(
    settings: SettingsStore,
    onBack: () -> Unit,
    onView: (RescueDraft) -> Unit,
    onInsert: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by settings.draftRescue.collectAsState(false)
    val savedEntries by DraftRescue.entries.collectAsState()
    val entries = savedEntries.filter(DraftRescue::available)
    LaunchedEffect(Unit) { DraftRescue.refresh() }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconControl(Glyph.BACK, "Close Draft Rescue", onBack)
            Text("Draft Rescue", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            if (enabled) TextButton(onClick = { scope.launch { settings.setDraftRescue(false) } }) { Text("Turn off") }
        }
        when {
            !enabled -> {
                Text("Keep up to 10 recent drafts on this device for 30 minutes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { settings.setDraftRescue(true) } }) { Text("Turn on") }
            }
            entries.isEmpty() -> Text("No recent drafts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(entries) { draft ->
                    Text(draft.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                    Row {
                        TextButton(onClick = { if (DraftRescue.available(draft)) onView(draft) }) { Text("View") }
                        TextButton(onClick = {
                            if (DraftRescue.available(draft)) {
                                val clip = ClipData.newPlainText("Draft Rescue", draft.text)
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                            }
                        }) { Text("Copy") }
                        TextButton(onClick = { if (DraftRescue.available(draft)) onInsert(draft.text) }) { Text("Insert") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DraftRescuePreview(draft: RescueDraft, modifier: Modifier, onClose: () -> Unit) {
    val entries by DraftRescue.entries.collectAsState()
    if (draft !in entries || !DraftRescue.available(draft)) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Draft Rescue", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconControl(Glyph.CLOSE, "Close draft preview", onClose)
            }
            Text(draft.text, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp))
        }
    }
}
