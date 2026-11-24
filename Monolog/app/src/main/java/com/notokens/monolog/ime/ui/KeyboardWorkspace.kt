package com.notokens.monolog.ime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.KeyboardPlacement
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.theme.PaperKeyText
import kotlin.math.roundToInt

internal enum class KeyboardPanel { EDIT_TEXT, LANGUAGES }
internal enum class EditorCommand(val label: String) {
    LEFT("←"), RIGHT("→"), UP("↑"), DOWN("↓"), SELECT("Select"),
    ALL("Select all"), CUT("Cut"), COPY("Copy"), PASTE("Paste"),
    UNDO("Undo"), REDO("Redo"), AUTOFILL("Autofill")
}

internal val LocalActiveKeyboardLanguage = staticCompositionLocalOf { KeyboardLanguage.of("en") }
internal val LocalKeyboardLayoutMenu = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val SplitKeyboardGap = 26.dp
internal val LocalSplitKeys = staticCompositionLocalOf { false }

@Composable
internal fun PinnedKeyboardWidgets(
    tiles: List<DashboardTile>,
    onClose: (() -> Unit)?,
    onOpen: (DashboardTile) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val closeWidth = if (onClose == null) 0.dp else 34.dp
        val buttonWidth = ((maxWidth - closeWidth) / tiles.size.coerceAtLeast(1)).coerceIn(0.dp, 38.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically) {
            for (tile in tiles) {
                Box(Modifier.width(buttonWidth).height(38.dp).clickable(role = Role.Button) { onOpen(tile) }
                    .semantics { contentDescription = tile.label }, contentAlignment = Alignment.Center) {
                    MonologIcon(tile.glyph, Modifier.size(20.dp), PaperKeyText)
                }
            }
            if (onClose != null) {
                Box(Modifier.width(closeWidth).height(38.dp).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Hide pinned widgets" }, contentAlignment = Alignment.Center) {
                    MonologIcon(Glyph.CLOSE, Modifier.size(18.dp), PaperKeyText)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceHeading(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        IconControl(Glyph.BACK, "Back to keyboard", onClose)
        Text(title, color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun KeyboardPlacementPicker(active: KeyboardPlacement, onClose: () -> Unit, onPick: (KeyboardPlacement) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        WorkspaceHeading("Keyboard layout", onClose)
        for (mode in KeyboardPlacement.entries) {
            Text(if (active == mode) "✓  ${mode.label}" else mode.label,
                Modifier.fillMaxWidth().clickable { onPick(mode) }.padding(8.dp), color = PaperKeyText)
        }
    }
}

@Composable
internal fun KeyboardLanguagePicker(active: KeyboardLanguage, onPick: (KeyboardLanguage) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        WorkspaceHeading("Typing language", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (language in KeyboardLanguage.all) {
                Text(if (language == active) "✓  ${language.label}" else language.label,
                    Modifier.fillMaxWidth().clickable { onPick(language) }.padding(10.dp), color = PaperKeyText)
            }
        }
    }
}

@Composable
internal fun KeyboardEditorTools(onCommand: (EditorCommand) -> Unit, onClose: () -> Unit) {
    var selecting by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (selecting) onCommand(EditorCommand.SELECT) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        WorkspaceHeading("Text editing", onClose)
        val rows = listOf(
            listOf(EditorCommand.LEFT, EditorCommand.UP, EditorCommand.DOWN, EditorCommand.RIGHT),
            listOf(EditorCommand.SELECT, EditorCommand.ALL, EditorCommand.CUT, EditorCommand.COPY),
            listOf(EditorCommand.PASTE, EditorCommand.UNDO, EditorCommand.REDO)
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { command ->
                    val arrow = command in listOf(EditorCommand.LEFT, EditorCommand.UP, EditorCommand.DOWN, EditorCommand.RIGHT)
                    KeyButton(Modifier.weight(1f).fillMaxHeight(),
                        text = command.label, label = if (arrow) "Move cursor ${command.name.lowercase()}" else command.label,
                        fontSize = if (arrow) 22.sp else 13.sp, isActive = command == EditorCommand.SELECT && selecting,
                        repeatOnHold = arrow, onClick = {
                            if (command == EditorCommand.SELECT) selecting = !selecting
                            onCommand(command)
                        })
                }
            }
        }
    }
}

@Composable
internal fun KeyboardPosition(
    placement: KeyboardPlacement,
    bodyHeight: Dp,
    onFloatingBounds: (android.graphics.Rect?) -> Unit,
    onDock: () -> Unit,
    content: @Composable (Modifier, Float, @Composable () -> Unit) -> Unit
) {
    val onBounds by rememberUpdatedState(onFloatingBounds)
    var scale by remember { mutableFloatStateOf(.82f) }
    var displacement by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(placement) {
        onBounds(null)
        onDispose { onBounds(null) }
    }
    if (placement == KeyboardPlacement.FULL || placement == KeyboardPlacement.SPLIT) {
        content(Modifier, 1f) {}
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val docked = placement != KeyboardPlacement.FLOATING
        if (docked) {
            val alignment = if (placement == KeyboardPlacement.LEFT) Alignment.BottomStart else Alignment.BottomEnd
            Box(Modifier.align(alignment).width(maxWidth * .79f)) { content(Modifier, 1f) {} }
        } else {
            val density = LocalDensity.current
            val pad = (LocalConfiguration.current.screenHeightDp.dp * .22f).coerceIn(68.dp, 180.dp)
            val panelWidth = maxWidth * scale
            val limitX = with(density) { ((maxWidth - panelWidth) / 2 - 4.dp).coerceAtLeast(0.dp).toPx() }
            val limitY = with(density) { pad.toPx() }
            val windowWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val dragLimit by rememberUpdatedState(limitX)
            val shape = RoundedCornerShape(23.dp)
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 5.dp)
                .height(bodyHeight + 106.dp + pad), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.absoluteOffset {
                    IntOffset(displacement.x.coerceIn(-limitX, limitX).roundToInt(),
                        displacement.y.coerceIn(-limitY, 0f).roundToInt())
                }.width(panelWidth).onGloballyPositioned {
                    val r = it.boundsInWindow()
                    if (!r.isEmpty) onBounds(android.graphics.Rect(
                        r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()
                    ))
                }.shadow(6.dp, shape, clip = false).clip(shape)) {
                    content(Modifier, scale) {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).fillMaxHeight().pointerInput(limitY) {
                                detectDragGestures { change, delta ->
                                    change.consume()
                                    displacement = Offset((displacement.x + delta.x).coerceIn(-dragLimit, dragLimit),
                                        (displacement.y + delta.y).coerceIn(-limitY, 0f))
                                }
                            }.semantics { contentDescription = "Drag floating keyboard" }, contentAlignment = Alignment.Center) {
                                MonologIcon(Glyph.DRAG_HANDLE, Modifier.size(18.dp), PaperKeyText)
                            }
                            Box(Modifier.size(48.dp).pointerInput(windowWidth) {
                                detectDragGestures { change, delta ->
                                    change.consume()
                                    val outward = if (kotlin.math.abs(delta.x) >= kotlin.math.abs(delta.y)) delta.x else -delta.y
                                    scale = (scale + outward * 2 / windowWidth).coerceIn(.66f, .96f)
                                }
                            }.clickable(role = Role.Button, onClickLabel = "Dock keyboard", onClick = onDock)
                                .semantics { contentDescription = "Expand keyboard. Drag to resize." },
                                contentAlignment = Alignment.Center) {
                                MonologIcon(Glyph.EXPAND, Modifier.size(19.dp), PaperKeyText)
                            }
                        }
                    }
                }
            }
        }
    }
}
