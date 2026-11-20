package com.notokens.monolog.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    onToggleIncognito: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        KeyboardMenu(editorContext.isUserIncognito, onOpenSettings, onToggleIncognito, onCloseKeyboard)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (editorContext.isIncognito) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MonologIcon(Glyph.SHIELD, Modifier.size(16.dp))
                Text("Private typing", color = PaperKeyText, fontSize = 14.sp)
            }
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
private fun KeyboardMenu(incognito: Boolean, onSettings: () -> Unit, onIncognito: () -> Unit, onHide: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconControl(Glyph.DOWN, "Keyboard menu", { expanded = !expanded })
        if (expanded) Popup(
            popupPositionProvider = LeftMenuAboveKeyboard,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false, clippingEnabled = false)
        ) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 6.dp) {
                Column(Modifier.widthIn(min = 192.dp, max = 244.dp).padding(vertical = 4.dp)) {
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { expanded = false; onSettings() })
                    DropdownMenuItem(text = { Text(if (incognito) "End incognito" else "Incognito for 15 minutes") },
                        onClick = { expanded = false; onIncognito() })
                    DropdownMenuItem(text = { Text("Hide keyboard") }, onClick = { expanded = false; onHide() })
                }
            }
        }
    }
}

private object LeftMenuAboveKeyboard : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(x, anchorBounds.top - popupContentSize.height)
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
