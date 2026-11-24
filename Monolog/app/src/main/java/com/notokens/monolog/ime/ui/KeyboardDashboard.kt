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
import com.notokens.monolog.ime.SensitiveDraft
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.Intent
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
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

internal enum class DashboardTile(val label: String, val glyph: Glyph, val extra: Boolean = false) {
    SETTINGS("Settings", Glyph.SETTINGS), PRIVATE("Incognito", Glyph.PRIVACY),
    CLIPBOARD("Clipboard", Glyph.CLIPBOARD), VOX("Vox", Glyph.MIC),
    SWIPE("Swipe", Glyph.DRAG_HANDLE), CORRECT("Autocorrect", Glyph.REWRITE),
    TONE("Smart Toning", Glyph.BOLT), HIDE("Hide keyboard", Glyph.DOWN),
    BORDERS("Key borders", Glyph.LAYERS, true), POPUPS("Key popups", Glyph.EXPAND, true),
    FILTER("Smart Filter", Glyph.REWRITE, true), DEFAULT_VOX("Default Vox", Glyph.MIC, true),
    LONG_PRESS("Long-press keys", Glyph.DOT, true), HAPTICS("Vibration haptics", Glyph.BELL, true),
    TALLER("Taller keyboard", Glyph.PLUS, true), SHORTER("Shorter keyboard", Glyph.DOWN, true),
    EDIT_TEXT("Text editing", Glyph.EDIT, true), UNDO("Undo", Glyph.BACK, true),
    REDO("Redo", Glyph.ARROW, true), AUTOFILL("Autofill", Glyph.SHIELD, true),
    LANGUAGE("Languages", Glyph.GLOBE, true), APPEARANCE("Appearance", Glyph.PAPER, true),
    TRANSLATE("Translate", Glyph.GLOBE, true), GIFS("GIFs", Glyph.PHOTO, true),
    STICKERS("Stickers", Glyph.SMILE, true);

    fun allowed(privateMode: Boolean, restricted: Boolean) =
        (!privateMode && !restricted) || this in setOf(
            SETTINGS, PRIVATE, HIDE, VOX, AUTOFILL, EDIT_TEXT, UNDO, REDO, LANGUAGE, APPEARANCE
        )
}

@Composable
internal fun visibleDashboardTiles(settings: SettingsStore): List<DashboardTile> {
    val hidden by settings.hiddenDashboardTiles.collectAsState(emptySet())
    val extras by settings.dashboardExtras.collectAsState(emptySet())
    val order by settings.dashboardOrder.collectAsState(emptyList())
    val tiles = DashboardTile.entries.filter { if (it.extra) it.name in extras else it.name !in hidden }
    return (order.mapNotNull { id -> tiles.firstOrNull { it.name == id } } + tiles).distinct().take(12)
}

internal suspend fun toggleDashboardSetting(tile: DashboardTile, settings: SettingsStore) {
    when (tile) {
        DashboardTile.SWIPE -> settings.setSwipeTyping(!settings.swipeTyping.first())
        DashboardTile.CORRECT -> settings.setAutocorrect(!settings.autocorrect.first())
        DashboardTile.TONE -> settings.setSmartTone(!settings.smartTone.first())
        DashboardTile.BORDERS -> settings.setKeyBorders(!settings.keyBorders.first())
        DashboardTile.POPUPS -> settings.setKeyPopups(!settings.keyPopups.first())
        DashboardTile.FILTER -> settings.setSmartVoxFilter(!settings.smartVoxFilter.first())
        DashboardTile.DEFAULT_VOX -> settings.setDefaultVox(!settings.defaultVox.first())
        DashboardTile.LONG_PRESS -> settings.setLongPressCharacters(!settings.longPressCharacters.first())
        DashboardTile.HAPTICS -> settings.setVibrationHaptics(!settings.vibrationHaptics.first())
        DashboardTile.TALLER -> settings.setKeyHeightPercent(settings.keyHeightPercent.first() + 5)
        DashboardTile.SHORTER -> settings.setKeyHeightPercent(settings.keyHeightPercent.first() - 5)
        else -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardDashboard(
    settings: SettingsStore,
    privateMode: Boolean,
    clips: List<String>,
    images: ClipboardImages,
    onPasteImage: (ClipboardImage) -> Unit,
    rowHeight: Dp,
    tiles: List<DashboardTile>,
    toolbarPins: List<String>,
    restricted: Boolean,
    showClips: Boolean,
    onShowClips: (Boolean) -> Unit,
    onActivate: (DashboardTile) -> Unit,
    onPaste: (String) -> Unit
) {
    val clipboard by settings.keyboardClipboard.collectAsState(false)
    val pins by settings.clipboardPins.collectAsState(emptyList())
    val borders by settings.keyBorders.collectAsState(false)
    val popups by settings.keyPopups.collectAsState(false)
    val smartFilter by settings.smartVoxFilter.collectAsState(true)
    val defaultVox by settings.defaultVox.collectAsState(false)
    val longPress by settings.longPressCharacters.collectAsState(true)
    val vibrationHaptics by settings.vibrationHaptics.collectAsState(true)
    val swipe by settings.swipeTyping.collectAsState(true)
    val autocorrect by settings.autocorrect.collectAsState(true)
    val smart by settings.smartTone.collectAsState(false)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DashboardTile?>(null) }
    var expandedText by remember { mutableStateOf<String?>(null) }
    var expandedImage by remember { mutableStateOf<ClipboardImage?>(null) }
    val context = LocalContext.current
    val tileBounds = remember { mutableMapOf<DashboardTile, Rect>() }
    var dragging by remember { mutableStateOf<DashboardTile?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    val currentTiles by rememberUpdatedState(tiles)

    LaunchedEffect(privateMode, restricted, clipboard) {
        if (privateMode || restricted) onShowClips(false)
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
                text = if (showAdd) "Add widget" else if (editing) "Edit dashboard" else if (showClips) "Clipboard" else "Keyboard dashboard",
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
                    } else onShowClips(false)
                })
            } else {
                if (editing && tiles.size < 12) {
                    IconControl(if (showAdd) Glyph.BACK else Glyph.PLUS,
                        if (showAdd) "Back to widgets" else "Add dashboard widget", { showAdd = !showAdd })
                }
                IconControl(Glyph.SHARE, "Share Monolog", {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://monolog.notokens.co")
                    runCatching {
                        context.startActivity(Intent.createChooser(share, "Share Monolog").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                IconControl(
                    if (editing) Glyph.CLOSE else Glyph.EDIT,
                    if (editing) "Finish editing dashboard" else "Edit dashboard",
                    { editing = !editing; showAdd = false }
                )
            }
        }

        if (showAdd) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                DashboardTile.entries.filterNot(tiles::contains).forEach { tile ->
                    Row(Modifier.fillMaxWidth().clickable {
                        showAdd = false
                        scope.launch {
                            if (tile.extra) settings.setDashboardExtra(tile.name, true)
                            else settings.setDashboardTileVisible(tile.name, true)
                        }
                    }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonologIcon(tile.glyph, Modifier.size(18.dp), PaperKeyText)
                        Text(tile.label, Modifier.padding(start = 9.dp), color = PaperKeyText)
                    }
                }
            }
        } else if (showClips && !privateMode && !restricted) {
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
                            val textItems = if (pinned) pins else recent
                            val imageItems = images.items.filter { images.pinned(it) == pinned }
                            if (textItems.isNotEmpty() || imageItems.isNotEmpty()) Text(if (pinned) "Pinned" else "Recent",
                                Modifier.padding(start = 4.dp, top = 5.dp), color = PaperKeySubText, fontSize = 12.sp)
                            textItems.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    row.forEach { item ->
                                        val shape = RoundedCornerShape(10.dp)
                                        Box(Modifier.weight(1f).height(62.dp).clip(shape).background(PaperPillBackground)
                                            .border(1.dp, PaperPillBorder, shape).combinedClickable(
                                                onClick = { onPaste(item) },
                                                onLongClick = { expandedText = item }
                                            ).padding(horizontal = 7.dp), contentAlignment = Alignment.CenterStart) {
                                            Text(item, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                fontSize = 13.sp, color = PaperKeyText)
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (imageItems.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                imageItems.forEach { item ->
                                    key(item.file) {
                                        ClipboardImagePreview(item, expanded = false, modifier = Modifier.combinedClickable(
                                            onClickLabel = "Paste image", onClick = { onPasteImage(item) },
                                            onLongClickLabel = "Image options", onLongClick = { expandedImage = item }
                                        ))
                                    }
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
                            val selected = !editing && when (tile) {
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
                            val enabled = editing || tile.allowed(privateMode, restricted)
                            val pinned = tile.name in toolbarPins

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { tileBounds[tile] = it.boundsInRoot() }
                                    .pointerInput(tile) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { local ->
                                                val bounds = tileBounds[tile] ?: return@detectDragGesturesAfterLongPress
                                                dragPosition = bounds.topLeft + local
                                                dragStart = dragPosition
                                                dragging = tile
                                            },
                                            onDragEnd = {
                                                val from = currentTiles.indexOf(tile)
                                                val to = currentTiles.indexOfFirst { tileBounds[it]?.contains(dragPosition) == true }
                                                if (dragging == tile && from >= 0 && to >= 0 && from != to) {
                                                    val order = currentTiles.toMutableList()
                                                    order[from] = order[to]
                                                    order[to] = tile
                                                    scope.launch { settings.setDashboardOrder(order.map { it.name }) }
                                                }
                                                dragging = null
                                            },
                                            onDragCancel = { dragging = null }
                                        ) { change, delta ->
                                            change.consume()
                                            dragPosition += delta
                                        }
                                    }
                                    .zIndex(if (dragging == tile) 1f else 0f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        if (dragging == tile) {
                                            alpha = .86f
                                            translationX = dragPosition.x - dragStart.x
                                            translationY = dragPosition.y - dragStart.y
                                        } else alpha = scale
                                    }
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
                                        } else onActivate(tile)
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
                                if (editing) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(26.dp)
                                            .clickable(enabled = pinned || toolbarPins.size < 4) {
                                                scope.launch { settings.toggleDashboardPin(tile.name) }
                                            }
                                            .semantics {
                                                contentDescription = if (pinned) "Unpin ${tile.label} from toolbar"
                                                    else "Pin ${tile.label} to toolbar"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        MonologIcon(if (pinned) Glyph.PIN else Glyph.DOT, Modifier.size(12.dp),
                                            if (pinned) PaperAccentBlue else PaperKeySubText)
                                    }
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
