package com.notokens.monolog.ime.ui

import android.content.res.Configuration
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ime.KeyboardAction
import com.notokens.monolog.ime.KeyboardLayer
import com.notokens.monolog.ime.KeyboardState
import com.notokens.monolog.ime.ShiftState
import com.notokens.monolog.ime.shortcuts.QuickShortcutsPanel
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ui.*
import com.notokens.monolog.ime.vox.VoxPanel
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
fun MonologKeyboardView(
    editorContext: EditorContext,
    keyboardState: KeyboardState,
    voxState: VoxState,
    isShortcutsOpen: Boolean,
    isVoxOpen: Boolean,
    shortcutDraft: ShortcutDraft,
    onDraftChange: (ShortcutDraft) -> Unit,
    onToggleShortcuts: () -> Unit,
    onCaptureContext: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterVox: () -> Unit,
    onExitVox: () -> Unit,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onClearTranscript: () -> Unit,
    onCommitTranscript: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onCharTyped: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onAction: () -> Unit,
    onShiftClick: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleMoreSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onCloseKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowHeight = if (isLandscape) 38.dp else 54.dp
    val recentEmoji = remember { mutableStateListOf<Emoji>() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .monologBackdrop(keyboard = true)
            .navigationBarsPadding()
    ) {
        // If Vox mode is open, render the full Vox Panel
        if (isVoxOpen) {
            VoxPanel(
                voxState = voxState,
                onStartListening = onStartListening,
                onPauseListening = onPauseListening,
                onClearTranscript = onClearTranscript,
                onCommitTranscript = onCommitTranscript,
                onCancel = onExitVox,
                onRequestPermission = onRequestPermission
            )
        } else {
            if (isShortcutsOpen) {
                QuickShortcutsPanel(
                    draft = shortcutDraft,
                    onDraftChange = onDraftChange,
                    editorContext = editorContext,
                    onClose = onToggleShortcuts
                )
            }

            KeyboardHeader(
                editorContext = editorContext,
                isShortcutsOpen = isShortcutsOpen,
                onToggleShortcuts = onToggleShortcuts,
                onCaptureContext = onCaptureContext,
                onOpenSettings = onOpenSettings,
                onEnterVox = onEnterVox,
                onCloseKeyboard = onCloseKeyboard,
                isEditing = isShortcutsOpen && shortcutDraft.isEditing
            )

            // Keyboard rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 3.dp, end = 3.dp, top = 4.dp, bottom = 16.dp)
            ) {
                when (keyboardState.layer) {
                    KeyboardLayer.LETTERS -> LettersLayout(
                        rowHeight = rowHeight,
                        shiftState = keyboardState.shiftState,
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onShiftClick = onShiftClick,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onToggleEmoji = onToggleEmoji,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onCaptureScreen = onCaptureContext,
                        onAction = onAction
                    )

                    KeyboardLayer.SYMBOLS -> SymbolsLayout(
                        rowHeight = rowHeight,
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onToggleMoreSymbols = onToggleMoreSymbols,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onToggleEmoji = onToggleEmoji,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onCaptureScreen = onCaptureContext,
                        onAction = onAction
                    )

                    KeyboardLayer.MORE_SYMBOLS -> MoreSymbolsLayout(
                        rowHeight = rowHeight,
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onToggleMoreSymbols = onToggleMoreSymbols,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onToggleEmoji = onToggleEmoji,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onCaptureScreen = onCaptureContext,
                        onAction = onAction
                    )

                    KeyboardLayer.EMOJI -> EmojiLayout(
                        rowHeight = rowHeight,
                        recent = if (editorContext.isIncognito) emptyList() else recentEmoji,
                        onEmojiPicked = { emoji ->
                            onCharTyped(emoji.text)
                            if (!editorContext.isIncognito) {
                                recentEmoji.remove(emoji)
                                recentEmoji.add(0, emoji)
                                if (recentEmoji.size > 36) recentEmoji.removeAt(recentEmoji.lastIndex)
                            }
                        },
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onBackspace = onBackspace,
                        onToggleLetters = onToggleEmoji,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onCaptureScreen = onCaptureContext,
                        onAction = onAction
                    )
                }
            }
        }
    }
}

@Composable
private fun LettersLayout(
    rowHeight: Dp,
    shiftState: ShiftState,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onShiftClick: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAction: () -> Unit
) {
    val isUpper = shiftState.isUppercase
    val row1 = listOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
    )
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    // Row 1 (Q..P with numbers 1..0 hints above; long press types the number)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row1.forEach { (char, numHint) ->
            val display = char.uppercase()
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                subText = numHint,
                onLongClick = { onCharTyped(numHint) },
                onClick = { onCharTyped(if (isUpper) display else char) }
            )
        }
    }

    // Row 2 (A..L with side padding)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        Spacer(modifier = Modifier.weight(0.5f))
        row2.forEach { char ->
            val display = char.uppercase()
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                onClick = { onCharTyped(if (isUpper) display else char) }
            )
        }
        Spacer(modifier = Modifier.weight(0.5f))
    }

    // Row 3 (Shift, Z..M, Backspace)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        val (shiftText, isShiftActive) = when (shiftState) {
            ShiftState.OFF -> "↑" to false
            ShiftState.ONCE -> "⬆" to true
            ShiftState.CAPS_LOCK -> "⇪" to true
        }
        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = shiftText,
            glyph = Glyph.SHIFT,
            fontSize = 20.sp,
            isSpecial = true,
            isActive = isShiftActive,
            onClick = onShiftClick
        )

        row3.forEach { char ->
            val display = char.uppercase()
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                onClick = { onCharTyped(if (isUpper) display else char) }
            )
        }

        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "⌫",
            glyph = Glyph.BACKSPACE,
            fontSize = 18.sp,
            isSpecial = true,
            onClick = onBackspace
        )
    }

    // Row 4 (Bottom Bar matching Paper reference)
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "?123",
        editorContext = editorContext,
        onModeClick = onToggleSymbols,
        onEmojiClick = onToggleEmoji,
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
        onCaptureScreen = onCaptureScreen,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun SymbolsLayout(
    rowHeight: Dp,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onToggleMoreSymbols: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAction: () -> Unit
) {
    val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    val row3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    // Row 1
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row1.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }
    }

    // Row 2
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row2.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }
    }

    // Row 3
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "=\\<",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            isSpecial = true,
            onClick = onToggleMoreSymbols
        )

        row3.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }

        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "⌫",
            glyph = Glyph.BACKSPACE,
            fontSize = 18.sp,
            isSpecial = true,
            onClick = onBackspace
        )
    }

    // Row 4
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleSymbols,
        onEmojiClick = onToggleEmoji,
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
        onCaptureScreen = onCaptureScreen,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun MoreSymbolsLayout(
    rowHeight: Dp,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onToggleMoreSymbols: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAction: () -> Unit
) {
    val row1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
    val row2 = listOf("£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\")
    val row3 = listOf("%", "_", "[", "]", "©", "®", "™")

    // Row 1
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row1.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }
    }

    // Row 2
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row2.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }
    }

    // Row 3
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "123",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            isSpecial = true,
            onClick = onToggleMoreSymbols
        )

        row3.forEach { char ->
            KeyButton(
                modifier = Modifier.weight(1f),
                text = char,
                onClick = { onCharTyped(char) }
            )
        }

        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "⌫",
            glyph = Glyph.BACKSPACE,
            fontSize = 18.sp,
            isSpecial = true,
            onClick = onBackspace
        )
    }

    // Row 4
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleSymbols,
        onEmojiClick = onToggleEmoji,
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
        onCaptureScreen = onCaptureScreen,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun EmojiLayout(
    rowHeight: Dp,
    recent: List<Emoji>,
    onEmojiPicked: (Emoji) -> Unit,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleLetters: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAction: () -> Unit
) {
    EmojiPicker(height = rowHeight * 3 + 8.dp, recent = recent, onPick = onEmojiPicked)
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleLetters,
        onEmojiClick = onToggleLetters,
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
        onCaptureScreen = onCaptureScreen,
        onAction = onAction,
        onCharTyped = onCharTyped,
        onEmojiBackspace = onBackspace
    )
}

@Composable
private fun BottomRow(
    rowHeight: Dp,
    modeLabel: String,
    editorContext: EditorContext,
    onModeClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAction: () -> Unit,
    onCharTyped: (String) -> Unit,
    onEmojiBackspace: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings = androidx.compose.runtime.remember(context) { com.notokens.monolog.data.SettingsStore(context.applicationContext) }
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight + 4.dp)) {
        KeyButton(Modifier.weight(1.35f), text = modeLabel, fontSize = 17.sp, onClick = onModeClick)
        KeyButton(
            Modifier.weight(.8f),
            glyph = if (onEmojiBackspace == null) Glyph.SMILE else Glyph.BACKSPACE,
            label = if (onEmojiBackspace == null) "Emoji" else "Backspace",
            onClick = onEmojiBackspace ?: onEmojiClick
        )
        KeyButton(Modifier.weight(.8f), glyph = Glyph.GLOBE, onClick = onSwitchKeyboard)
        KeyButton(
            Modifier.weight(4.1f),
            isPill = true,
            label = "Space",
            longClickLabel = "Capture screen",
            onLongClick = onCaptureScreen.takeUnless { editorContext.isIncognito },
            onClick = onSpace
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Text("English", color = PaperKeyText, fontSize = 15.sp)
                if (!editorContext.isIncognito) MonologIcon(Glyph.CAPTURE, Modifier.size(17.dp))
            }
        }
        // Combined Incognito & Dot button:
        // Always displays the detective hat + dot underneath (matching the concept mockup)
        // Click = types "."
        // Hold / Long-press = toggles incognito mode with tactile haptic feedback
        KeyButton(
            modifier = Modifier.weight(1f),
            isActive = editorContext.isIncognito,
            onLongClick = {
                scope.launch {
                    val next = !editorContext.isUserIncognito
                    settings.setIncognito(next)
                    android.widget.Toast.makeText(
                        context,
                        if (next) "Incognito mode activated" else "Incognito mode deactivated",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onClick = {
                onCharTyped(".")
            }
        ) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                MonologIcon(
                    Glyph.PRIVACY,
                    Modifier.size(19.dp),
                    color = if (editorContext.isIncognito) com.notokens.monolog.ui.theme.MonologIncognitoShield else PaperKeyText
                )
                Spacer(Modifier.height(1.dp))
                MonologIcon(
                    Glyph.DOT,
                    Modifier.size(8.dp),
                    color = if (editorContext.isIncognito) com.notokens.monolog.ui.theme.MonologIncognitoShield else PaperKeyText
                )
            }
        }
        KeyButton(
            Modifier.weight(1.35f),
            glyph = when (editorContext.action) {
                KeyboardAction.SEARCH -> Glyph.SEARCH
                KeyboardAction.GO, KeyboardAction.SEND, KeyboardAction.NEXT -> Glyph.ARROW
                else -> Glyph.ENTER
            },
            onClick = onAction
        )
    }
}
