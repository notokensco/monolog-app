package com.notokens.monolog.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    isShortcutsOpen: Boolean,
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
        MKey(
            active = isShortcutsOpen,
            enabled = !editorContext.isIncognito,
            onClick = onCaptureContext,
            onLongClick = onToggleShortcuts
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MKey(
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val view = LocalView.current
    Box(
        Modifier.size(48.dp)
            .clip(RoundedCornerShape(11.dp))
            .then(if (active) Modifier.glassSurface(11, GlassAccent.LAVENDER) else Modifier)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = "Open quick shortcuts",
                onLongClick = {
                    triggerHapticFeedback(view, isLong = true)
                    onLongClick()
                },
                onClick = {
                    triggerHapticFeedback(view)
                    onClick()
                }
            )
            .semantics { contentDescription = "Monolog context key" },
        contentAlignment = Alignment.Center
    ) {
        MonologLogo(Modifier.size(48.dp), alpha = if (enabled) 1f else .4f)
    }
}
