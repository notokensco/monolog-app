package com.notokens.monolog.ime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ime.ChatSession
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl

@Composable
internal fun ChatAssistToolbarButton(session: ChatSession, onClick: () -> Unit) {
    val status = when {
        session.paused -> "paused"
        session.busy -> "reading the chat"
        session.reply.isNotEmpty() || session.coaching.isNotEmpty() -> "suggestion ready"
        else -> "sharing"
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(width = 48.dp, height = 34.dp).clip(RoundedCornerShape(12.dp))
        .background(if (session.paused) colors.surfaceVariant else colors.primaryContainer)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Chat Assist, $status" }, contentAlignment = Alignment.Center) {
        Text("Chat", color = if (session.paused) colors.onSurfaceVariant else colors.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmall)
        if (session.reply.isNotEmpty() || session.coaching.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).clip(CircleShape).background(colors.primary))
        }
    }
}

@Composable
internal fun ChatAssistPanel(
    modifier: Modifier,
    session: ChatSession?,
    canStart: Boolean,
    error: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onInsert: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.surfaceContainer,
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (session?.reply.isNullOrBlank()) "Chat Assist" else "Suggested reply",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconControl(Glyph.CLOSE, "Close Chat Assist panel", onClose)
            }
            if (session == null) {
                Text(error ?: if (canStart) "Add a goal and share your chat app to get on-device reply suggestions and feedback."
                    else "Download the Better or Best text model to use Chat Assist.")
                TextButton(onClick = onStart, enabled = canStart) { Text("Start") }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 84.dp).verticalScroll(rememberScrollState())) {
                    if (session.reply.isNotBlank()) Text(session.reply, style = MaterialTheme.typography.bodyMedium)
                    else Text(session.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (session.coaching.isNotBlank()) {
                        Text(session.coaching, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                }
                Row {
                    if (!session.paused && !session.busy && session.reply.isNotBlank()) {
                        TextButton(onClick = { onInsert(session.reply) }) { Text("Insert") }
                    }
                    TextButton(onClick = onPause) { Text(if (session.paused) "Resume" else "Pause") }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}
