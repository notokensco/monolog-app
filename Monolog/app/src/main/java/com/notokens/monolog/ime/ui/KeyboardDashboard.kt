package com.notokens.monolog.ime.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.notokens.monolog.ime.SensitiveDraft
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.theme.PaperAccentBlue
import com.notokens.monolog.ui.theme.PaperAccentLavender
import com.notokens.monolog.ui.theme.PaperAccentLavenderBorder
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBackground
import com.notokens.monolog.ui.theme.PaperPillBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLIP_LIFE_MS = 3_600_000L
private val recentClips = mutableStateListOf<Pair<String, Long>>()
private var latestPrivateClip = 0L
private var latestDeletedClip = 0L

@Composable
internal fun rememberKeyboardClips(settings: SettingsStore, privateMode: Boolean, visible: Boolean): List<String> {
    val context = LocalContext.current
    val enabled by settings.keyboardClipboard.collectAsState(initial = false)
    val triggers by settings.sensitiveWords.collectAsState(initial = "")
    val detector = remember(triggers) { SensitiveDraft().apply { setWords(triggers) } }
    LaunchedEffect(enabled, visible) {
        while (enabled && visible) {
            recentClips.removeAll { System.currentTimeMillis() - it.second >= CLIP_LIFE_MS }
            delay(15_000)
        }
    }
    DisposableEffect(context, enabled, privateMode, visible, detector) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val permitted = enabled && visible && !privateMode
        fun update() {
            val text = runCatching {
                val clip = manager?.primaryClip ?: return@runCatching null
                val timestamp = clip.description.timestamp
                if (timestamp <= latestPrivateClip || timestamp == latestDeletedClip || System.currentTimeMillis() - timestamp >= CLIP_LIFE_MS ||
                    clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true ||
                    clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()?.let { it to timestamp }
            }.getOrNull()?.takeIf { (text, _) -> text.length <= 4000 && text.isNotBlank() && !detector.matches(text) } ?: return
            recentClips.removeAll { it.first == text.first }
            recentClips.add(0, text)
            while (recentClips.size > 20) recentClips.removeAt(recentClips.lastIndex)
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { update() }
        if (privateMode) latestPrivateClip = System.currentTimeMillis()
        if (permitted) {
            manager?.addPrimaryClipChangedListener(listener)
            update()
        } else if (!enabled || privateMode) recentClips.clear()
        onDispose {
            if (permitted) manager?.removePrimaryClipChangedListener(listener)
            if (privateMode) latestPrivateClip = System.currentTimeMillis()
        }
    }
    return if (enabled && !privateMode) recentClips.map { it.first } else emptyList()
}

private enum class DashboardTile(val label: String, val glyph: Glyph, val extra: Boolean = false) {
    SETTINGS("Settings", Glyph.SETTINGS), PRIVATE("Incognito", Glyph.PRIVACY),
    CLIPBOARD("Clipboard", Glyph.CLIPBOARD), VOX("Vox", Glyph.MIC),
    SWIPE("Swipe", Glyph.DRAG_HANDLE), CORRECT("Autocorrect", Glyph.REWRITE),
    TONE("Smart Toning", Glyph.BOLT), HIDE("Hide keyboard", Glyph.DOWN),
    BORDERS("Key borders", Glyph.LAYERS, true), POPUPS("Key popups", Glyph.EXPAND, true),
    FILTER("Smart Filter", Glyph.REWRITE, true), DEFAULT_VOX("Default Vox", Glyph.MIC, true),
    LONG_PRESS("Long-press keys", Glyph.DOT, true), HAPTICS("Vibration haptics", Glyph.BELL, true),
    TALLER("Taller keyboard", Glyph.PLUS, true), SHORTER("Shorter keyboard", Glyph.DOWN, true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KeyboardDashboard(
    settings: SettingsStore,
    privateMode: Boolean,
    clips: List<String>,
    images: ClipboardImages,
    onPasteImage: (ClipboardImage) -> Unit,
    rowHeight: Dp,
    onSettings: () -> Unit,
    onIncognito: () -> Unit,
    onHide: () -> Unit,
    onVox: () -> Unit,
    onPaste: (String) -> Unit
) {
    val hidden by settings.hiddenDashboardTiles.collectAsState(emptySet())
    val clipboard by settings.keyboardClipboard.collectAsState(false)
    val pins by settings.clipboardPins.collectAsState(emptyList())
    val extras by settings.dashboardExtras.collectAsState(emptySet())
    val borders by settings.keyBorders.collectAsState(false)
    val popups by settings.keyPopups.collectAsState(false)
    val smartFilter by settings.smartVoxFilter.collectAsState(true)
    val defaultVox by settings.defaultVox.collectAsState(false)
    val longPress by settings.longPressCharacters.collectAsState(true)
    val vibrationHaptics by settings.vibrationHaptics.collectAsState(true)
    val height by settings.keyHeightPercent.collectAsState(100)
    val tiles = DashboardTile.entries.filter { tile ->
        if (tile.extra) tile.name in extras else tile.name !in hidden
    }.take(12)
    val swipe by settings.swipeTyping.collectAsState(true)
    val autocorrect by settings.autocorrect.collectAsState(true)
    val smart by settings.smartTone.collectAsState(false)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DashboardTile?>(null) }
    var expandedText by remember { mutableStateOf<String?>(null) }
    var expandedImage by remember { mutableStateOf<ClipboardImage?>(null) }
    var showClips by remember { mutableStateOf(false) }

    LaunchedEffect(privateMode, clipboard) {
        if (privateMode) showClips = false
        if (!clipboard || privateMode) { expandedText = null; expandedImage = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (editing) "Edit dashboard" else if (showClips) "Clipboard" else "Keyboard dashboard",
                color = PaperKeyText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (showClips) {
                Switch(clipboard, { checked -> scope.launch { settings.setKeyboardClipboard(checked) } },
                    modifier = Modifier.graphicsLayer(scaleX = .7f, scaleY = .7f)
                        .semantics { contentDescription = "Clipboard history" })
                IconControl(Glyph.BACK, "Back to dashboard", {
                    if (expandedText != null || expandedImage != null) {
                        expandedText = null
                        expandedImage = null
                    } else showClips = false
                })
            } else {
                if (editing) Box {
                    if (tiles.size < 12) IconControl(Glyph.PLUS, "Add dashboard widget", { showAdd = true })
                    DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                        DashboardTile.entries.filterNot(tiles::contains).forEach { tile ->
                            DropdownMenuItem(text = { Text(tile.label) }, onClick = {
                                showAdd = false
                                scope.launch {
                                    if (tile.extra) settings.setDashboardExtra(tile.name, true)
                                    else settings.setDashboardTileVisible(tile.name, true)
                                }
                            })
                        }
                    }
                }
                IconControl(
                    if (editing) Glyph.CLOSE else Glyph.EDIT,
                    if (editing) "Finish editing dashboard" else "Edit dashboard",
                    { editing = !editing }
                )
            }
        }

        if (showClips && !privateMode) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val text = expandedText
                val image = expandedImage
                when {
                    !clipboard -> Text("Enable clipboard to keep copied text and images for one hour. Pin items you want to keep.",
                        Modifier.padding(10.dp), color = PaperKeySubText)
                    text != null -> {
                        Text(text, Modifier.padding(10.dp), color = PaperKeyText)
                        ClipboardOptions(text in pins, onPaste = { onPaste(text) }, onPin = {
                            scope.launch { settings.setClipboardPin(text, text !in pins); expandedText = null }
                        }, onRemove = {
                            recentClips.firstOrNull { it.first == text }?.let { latestDeletedClip = it.second }
                            recentClips.removeAll { it.first == text }
                            scope.launch { settings.setClipboardPin(text, false); expandedText = null }
                        })
                    }
                    image != null -> {
                        ClipboardImagePreview(image, expanded = true)
                        ClipboardOptions(images.pinned(image), onPaste = { onPasteImage(image) }, onPin = {
                            scope.launch { images.setPinned(image, !images.pinned(image)); expandedImage = null }
                        }, onRemove = { scope.launch { images.remove(image); expandedImage = null } })
                    }
                    else -> {
                        val recent = clips.filterNot(pins::contains)
                        if (pins.isEmpty() && recent.isEmpty() && images.items.isEmpty()) Text("Nothing copied yet.",
                            Modifier.padding(10.dp), color = PaperKeySubText)
                        for (pinned in listOf(true, false)) {
                            val group = (if (pinned) pins else recent).map { it as Any } +
                                images.items.filter { images.pinned(it) == pinned }
                            if (group.isNotEmpty()) Text(if (pinned) "Pinned" else "Recent",
                                Modifier.padding(start = 4.dp, top = 5.dp), color = PaperKeySubText, fontSize = 12.sp)
                            group.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    row.forEach { item ->
                                        val shape = RoundedCornerShape(10.dp)
                                        Box(Modifier.weight(1f).height(62.dp).clip(shape).background(PaperPillBackground)
                                            .border(1.dp, PaperPillBorder, shape).combinedClickable(
                                                onClick = { if (item is String) onPaste(item) else onPasteImage(item as ClipboardImage) },
                                                onLongClick = { if (item is String) expandedText = item else expandedImage = item as ClipboardImage }
                                            ).padding(horizontal = 7.dp), contentAlignment = Alignment.CenterStart) {
                                            if (item is String) Text(item, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                fontSize = 13.sp, color = PaperKeyText)
                                            else ClipboardImagePreview(item as ClipboardImage, expanded = false)
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val tileRows = tiles.chunked(4)
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                tileRows.forEach { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(rowHeight - 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        group.forEach { tile ->
                            val scale by animateFloatAsState(if (removing == tile) .25f else 1f,
                                tween(150), label = "Dashboard widget")
                            val selected = editing || when (tile) {
                                DashboardTile.PRIVATE -> privateMode
                                DashboardTile.CLIPBOARD -> clipboard
                                DashboardTile.SWIPE -> swipe
                                DashboardTile.CORRECT -> autocorrect
                                DashboardTile.TONE -> smart
                                DashboardTile.BORDERS -> borders
                                DashboardTile.POPUPS -> popups
                                DashboardTile.FILTER -> smartFilter
                                DashboardTile.DEFAULT_VOX -> defaultVox
                                DashboardTile.LONG_PRESS -> longPress
                                DashboardTile.HAPTICS -> vibrationHaptics
                                else -> false
                            }
                            val enabled = editing || !privateMode || tile in setOf(
                                DashboardTile.SETTINGS, DashboardTile.PRIVATE, DashboardTile.HIDE, DashboardTile.VOX
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = scale)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            selected -> PaperAccentLavender
                                            else -> PaperPillBackground
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) PaperAccentLavenderBorder else PaperPillBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .semantics {
                                        if (editing) stateDescription = "Remove widget"
                                    }
                                    .clickable(enabled = enabled) {
                                        if (editing) {
                                            if (removing != null) return@clickable
                                            removing = tile
                                            scope.launch {
                                                delay(150)
                                                if (tile.extra) settings.setDashboardExtra(tile.name, false)
                                                else settings.setDashboardTileVisible(tile.name, false)
                                                removing = null
                                            }
                                        } else {
                                            when (tile) {
                                                DashboardTile.SETTINGS -> onSettings()
                                                DashboardTile.PRIVATE -> onIncognito()
                                                DashboardTile.HIDE -> onHide()
                                                DashboardTile.VOX -> onVox()
                                                DashboardTile.CLIPBOARD -> showClips = true
                                                DashboardTile.SWIPE -> scope.launch { settings.setSwipeTyping(!swipe) }
                                                DashboardTile.CORRECT -> scope.launch { settings.setAutocorrect(!autocorrect) }
                                                DashboardTile.TONE -> scope.launch { settings.setSmartTone(!smart) }
                                                DashboardTile.BORDERS -> scope.launch { settings.setKeyBorders(!borders) }
                                                DashboardTile.POPUPS -> scope.launch { settings.setKeyPopups(!popups) }
                                                DashboardTile.FILTER -> scope.launch { settings.setSmartVoxFilter(!smartFilter) }
                                                DashboardTile.DEFAULT_VOX -> scope.launch { settings.setDefaultVox(!defaultVox) }
                                                DashboardTile.LONG_PRESS -> scope.launch { settings.setLongPressCharacters(!longPress) }
                                                DashboardTile.HAPTICS -> scope.launch { settings.setVibrationHaptics(!vibrationHaptics) }
                                                DashboardTile.TALLER -> scope.launch { settings.setKeyHeightPercent(height + 5) }
                                                DashboardTile.SHORTER -> scope.launch { settings.setKeyHeightPercent(height - 5) }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 3.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    MonologIcon(
                                        tile.glyph,
                                        Modifier.size(16.dp),
                                        if (selected) MaterialTheme.colorScheme.primary else PaperKeyText
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = tile.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else PaperKeyText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardOptions(pinned: Boolean, onPaste: () -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
    Row {
        TextButton(onPaste) { Text("Paste") }
        TextButton(onPin) { Text(if (pinned) "Unpin" else "Pin") }
        TextButton(onRemove) { Text("Remove") }
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
