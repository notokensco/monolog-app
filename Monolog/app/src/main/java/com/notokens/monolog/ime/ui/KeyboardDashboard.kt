package com.notokens.monolog.ime.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberKeyboardClips(settings: SettingsStore, privateMode: Boolean): List<String> {
    val context = LocalContext.current
    val enabled by settings.keyboardClipboard.collectAsState(initial = false)
    val clips = remember { mutableStateListOf<String>() }
    DisposableEffect(context, enabled, privateMode) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val permitted = enabled && !privateMode
        fun update() {
            val text = runCatching {
                val clip = manager?.primaryClip ?: return@runCatching null
                if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true ||
                    clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
            }.getOrNull()?.takeIf { it.length <= 4000 && it.isNotBlank() } ?: return
            clips.remove(text)
            clips.add(0, text)
            while (clips.size > 10) clips.removeAt(clips.lastIndex)
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { update() }
        if (permitted) {
            manager?.addPrimaryClipChangedListener(listener)
            update()
        } else clips.clear()
        onDispose { if (permitted) manager?.removePrimaryClipChangedListener(listener) }
    }
    return if (enabled && !privateMode) clips else emptyList()
}

private enum class DashboardTile(val label: String, val glyph: Glyph) {
    SETTINGS("Settings", Glyph.SETTINGS), PRIVATE("Incognito", Glyph.PRIVACY),
    CLIPBOARD("Clipboard", Glyph.CLIPBOARD), VOX("Vox", Glyph.MIC),
    SWIPE("Swipe", Glyph.DRAG_HANDLE), CORRECT("Autocorrect", Glyph.REWRITE),
    TONE("Smart Toning", Glyph.BOLT), HIDE("Hide keyboard", Glyph.DOWN)
}

@Composable
internal fun KeyboardDashboard(
    settings: SettingsStore, privateMode: Boolean, clips: List<String>,
    onSettings: () -> Unit, onIncognito: () -> Unit, onHide: () -> Unit, onVox: () -> Unit,
    onPaste: (String) -> Unit
) {
    val hidden by settings.hiddenDashboardTiles.collectAsState(emptySet())
    val clipboard by settings.keyboardClipboard.collectAsState(false)
    val swipe by settings.swipeTyping.collectAsState(true)
    val autocorrect by settings.autocorrect.collectAsState(true)
    val smart by settings.smartTone.collectAsState(false)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var showClips by remember { mutableStateOf(false) }
    LaunchedEffect(privateMode, clipboard) {
        if (privateMode || !clipboard) showClips = false
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 142.dp, max = 235.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Edit dashboard" else if (showClips) "Clipboard" else "Keyboard dashboard",
                Modifier.weight(1f))
            if (showClips) IconControl(Glyph.BACK, "Back to dashboard", { showClips = false })
            else IconControl(if (editing) Glyph.CLOSE else Glyph.EDIT,
                if (editing) "Finish editing dashboard" else "Edit dashboard", { editing = !editing })
        }
        if (showClips && clipboard && !privateMode) {
            TextButton(onClick = { scope.launch { settings.setKeyboardClipboard(false) } }) { Text("Turn off clipboard history") }
            if (clips.isEmpty()) Text("No text copied yet.", color = PaperKeySubText)
            clips.forEach { clip ->
                TextButton(onClick = { onPaste(clip) }, modifier = Modifier.fillMaxWidth()) {
                    Text(clip, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        } else {
            val tiles = DashboardTile.entries.filter { editing || it.name !in hidden }
            tiles.chunked(3).forEachIndexed { row, group ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEachIndexed { col, tile ->
                        key(tile) {
                            var entered by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { entered = true }
                            val scale by animateFloatAsState(if (entered) 1f else .84f,
                                tween(175, delayMillis = (row * 3 + col) * 24), label = "Dashboard tile")
                            val selected = if (editing) tile.name !in hidden else when (tile) {
                                DashboardTile.PRIVATE -> privateMode
                                DashboardTile.CLIPBOARD -> clipboard
                                DashboardTile.SWIPE -> swipe
                                DashboardTile.CORRECT -> autocorrect
                                DashboardTile.TONE -> smart
                                else -> false
                            }
                            FilterChip(selected = selected, enabled = editing || !privateMode ||
                                tile in setOf(DashboardTile.SETTINGS, DashboardTile.PRIVATE, DashboardTile.HIDE),
                                modifier = Modifier.weight(1f).graphicsLayer(scaleX = scale, scaleY = scale),
                                leadingIcon = { MonologIcon(tile.glyph, Modifier.size(16.dp)) },
                                label = { Text(tile.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    if (editing) scope.launch { settings.setDashboardTileVisible(tile.name, !selected) }
                                    else when (tile) {
                                        DashboardTile.SETTINGS -> onSettings()
                                        DashboardTile.PRIVATE -> onIncognito()
                                        DashboardTile.HIDE -> onHide()
                                        DashboardTile.VOX -> onVox()
                                        DashboardTile.CLIPBOARD -> showClips = true
                                        DashboardTile.SWIPE -> scope.launch { settings.setSwipeTyping(!swipe) }
                                        DashboardTile.CORRECT -> scope.launch { settings.setAutocorrect(!autocorrect) }
                                        DashboardTile.TONE -> scope.launch { settings.setSmartTone(!smart) }
                                    }
                                })
                        }
                    }
                    repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (showClips && !clipboard && !privateMode) {
                Text("Remember copied text while the keyboard is available.", color = PaperKeySubText, fontSize = 12.sp)
                TextButton(onClick = { scope.launch { settings.setKeyboardClipboard(true); showClips = true } }) {
                    Text("Enable clipboard history")
                }
            }
        }
    }
}

@Composable
internal fun IncognitoStrip(userEnabled: Boolean, settings: SettingsStore) {
    val deadline by settings.keyboardIncognitoUntil.collectAsState(0L)
    var seconds by remember { mutableLongStateOf(0) }
    LaunchedEffect(userEnabled, deadline) {
        while (userEnabled && deadline > 0) {
            seconds = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            if (seconds == 0L) break
            delay(1000)
        }
    }
    Row(Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MonologIcon(Glyph.PRIVACY, Modifier.size(12.dp), PaperKeySubText)
        Text("Incognito", Modifier.weight(1f), color = PaperKeySubText, fontSize = 10.sp, lineHeight = 12.sp)
        if (userEnabled && deadline > 0) {
            Text("%d:%02d".format(seconds / 60, seconds % 60), color = PaperKeySubText,
                fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}
