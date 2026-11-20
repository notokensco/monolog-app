package com.notokens.monolog.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import com.notokens.monolog.data.SettingsStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ui.*
import com.notokens.monolog.ui.theme.*

@Composable
fun KeyboardHeader(
    editorContext: EditorContext,
    onToggleShortcuts: () -> Unit,
    onCaptureContext: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterVox: () -> Unit,
    onCloseKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false
) {
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconControl(Glyph.DOWN, "Hide keyboard", onCloseKeyboard)
        IconControl(Glyph.SETTINGS, "Keyboard settings", onOpenSettings, frosted = true)
        Row(
            Modifier.weight(1f).height(36.dp).glassSurface(18).clickable(
                enabled = !editorContext.isIncognito, role = Role.Button, onClick = onToggleShortcuts
            ).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (editorContext.isIncognito) {
                MonologIcon(Glyph.SHIELD, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (editorContext.isIncognito) "Private typing" else if (isEditing) "Editing shortcut" else "Quick shortcuts",
                color = PaperKeyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!editorContext.isIncognito) IconControl(Glyph.MIC, "Open Vox", onEnterVox)
        ContextKey(
            enabled = !editorContext.isIncognito,
            onClick = onCaptureContext,
            onLongClick = onToggleShortcuts
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ContextKey(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val holdMs by settings.shortcutHoldMs.collectAsState(initial = 500)
    val haptics by settings.shortcutHaptics.collectAsState(initial = true)
    val defaultConfiguration = LocalViewConfiguration.current
    val configuration = remember(defaultConfiguration, holdMs) {
        object : ViewConfiguration by defaultConfiguration {
            override val longPressTimeoutMillis: Long = holdMs.toLong()
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides configuration) {
        Box(
            Modifier.size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClickLabel = "Open quick shortcuts",
                    onLongClick = {
                        if (haptics) triggerHapticFeedback(view, isLong = true)
                        onLongClick()
                    },
                    onClick = {
                        if (haptics) triggerHapticFeedback(view)
                        onClick()
                    }
                )
                .semantics { contentDescription = "Monolog context key" },
            contentAlignment = Alignment.Center
        ) {
            MonologMark(Modifier.size(48.dp), alpha = if (enabled) 1f else .4f)
        }
    }
}
