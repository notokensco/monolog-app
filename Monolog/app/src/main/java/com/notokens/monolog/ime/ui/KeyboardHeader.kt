package com.notokens.monolog.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ui.*
import com.notokens.monolog.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardHeader(
    editorContext: EditorContext,
    onToggleShortcuts: () -> Unit,
    onCaptureContext: () -> Unit,
    onToggleDashboard: () -> Unit,
    dashboardOpen: Boolean,
    onHoldDashboard: (() -> Unit)? = null,
    dashboardChevron: Boolean = false,
    onModeAction: () -> Unit,
    toolbarAi: Boolean,
    isContextActive: Boolean = false,
    suggestions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val dashboardLabel = if (dashboardOpen) "Close keyboard dashboard" else "Keyboard dashboard"
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                role = Role.Button,
                onClick = onToggleDashboard,
                onLongClick = onHoldDashboard,
                onLongClickLabel = "Show pinned widgets"
            ).semantics { contentDescription = dashboardLabel },
            contentAlignment = Alignment.Center
        ) {
            MonologIcon(when {
                dashboardOpen -> Glyph.CLOSE
                dashboardChevron -> Glyph.CHEVRON
                else -> Glyph.DASHBOARD
            }, Modifier.size(21.dp), PaperKeyText)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            suggestions()
        }
        if (!editorContext.isRestrictedField && !editorContext.isNumber) {
            IconControl(
                if (toolbarAi) Glyph.BOLT else Glyph.MIC,
                if (toolbarAi) "Open Monolog AI" else "Open Vox",
                onModeAction
            )
        }
        ContextKey(
            enabled = !editorContext.isIncognito,
            active = isContextActive,
            onClick = onCaptureContext,
            onLongClick = onToggleShortcuts
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ContextKey(
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val tapOpensShortcuts by settings.tapOpensShortcuts.collectAsState(false)
    val holdMs by settings.shortcutHoldMs.collectAsState(initial = 500)
    val haptics by settings.shortcutHaptics.collectAsState(initial = true)
    val vibrationHaptics by settings.vibrationHaptics.collectAsState(initial = true)
    val defaultConfiguration = LocalViewConfiguration.current
    val configuration = remember(defaultConfiguration, holdMs) {
        object : ViewConfiguration by defaultConfiguration {
            override val longPressTimeoutMillis: Long = holdMs.toLong()
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides configuration) {
        Box(
            Modifier.size(48.dp)
                .activeKeyGlow(active)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = if (tapOpensShortcuts) "Open quick shortcuts" else "Capture context",
                    onLongClickLabel = if (tapOpensShortcuts) "Capture context" else "Open quick shortcuts",
                    onLongClick = {
                        if (haptics && vibrationHaptics) triggerHapticFeedback(view, isLong = true)
                        if (tapOpensShortcuts) onClick() else onLongClick()
                    },
                    onClick = {
                        if (haptics && vibrationHaptics) triggerHapticFeedback(view)
                        if (tapOpensShortcuts) onLongClick() else onClick()
                    }
                )
                .semantics { contentDescription = "Monolog context key" },
            contentAlignment = Alignment.Center
        ) {
            MonologMark(Modifier.size(48.dp), alpha = if (enabled) 1f else .4f)
        }
    }
}
