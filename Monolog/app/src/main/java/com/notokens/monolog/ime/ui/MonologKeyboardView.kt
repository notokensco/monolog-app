package com.notokens.monolog.ime.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.notokens.monolog.ime.vox.VoxPanel
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.PaperBackgroundEnd
import com.notokens.monolog.ui.theme.PaperBackgroundMid
import com.notokens.monolog.ui.theme.PaperBackgroundStart
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
fun MonologKeyboardView(
    editorContext: EditorContext,
    keyboardState: KeyboardState,
    voxState: VoxState,
    isShortcutsOpen: Boolean,
    isVoxOpen: Boolean,
    onToggleShortcuts: () -> Unit,
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
    onSwitchKeyboard: () -> Unit,
    onCloseKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowHeight = if (isLandscape) 38.dp else 52.dp

    val backgroundBrush = Brush.verticalGradient(
        listOf(PaperBackgroundStart, PaperBackgroundMid, PaperBackgroundEnd)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundBrush)
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
            // Top Toolbar / Header
            KeyboardHeader(
                editorContext = editorContext,
                isShortcutsOpen = isShortcutsOpen,
                onToggleShortcuts = onToggleShortcuts,
                onEnterVox = onEnterVox,
                onCloseKeyboard = onCloseKeyboard
            )

            // If Quick shortcuts is open, render the shortcuts panel
            if (isShortcutsOpen) {
                QuickShortcutsPanel(
                    editorContext = editorContext,
                    onClose = onToggleShortcuts
                )
            }

            // Keyboard rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
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
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onAction = onAction
                    )

                    KeyboardLayer.SYMBOLS -> SymbolsLayout(
                        rowHeight = rowHeight,
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onToggleMoreSymbols = onToggleMoreSymbols,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
                        onAction = onAction
                    )

                    KeyboardLayer.MORE_SYMBOLS -> MoreSymbolsLayout(
                        rowHeight = rowHeight,
                        editorContext = editorContext,
                        onCharTyped = onCharTyped,
                        onToggleMoreSymbols = onToggleMoreSymbols,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSpace = onSpace,
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
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onAction: () -> Unit
) {
    val isUpper = shiftState.isUppercase
    val row1 = listOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
    )
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    // Row 1 (Q..P with numbers 1..0 hints above)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row1.forEach { (char, numHint) ->
            val display = if (isUpper) char.uppercase() else char
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                subText = numHint,
                onClick = { onCharTyped(display) }
            )
        }
    }

    // Row 2 (A..L with side padding)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        Spacer(modifier = Modifier.weight(0.5f))
        row2.forEach { char ->
            val display = if (isUpper) char.uppercase() else char
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                onClick = { onCharTyped(display) }
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
            fontSize = 20.sp,
            isSpecial = true,
            isActive = isShiftActive,
            onClick = onShiftClick
        )

        row3.forEach { char ->
            val display = if (isUpper) char.uppercase() else char
            KeyButton(
                modifier = Modifier.weight(1f),
                text = display,
                onClick = { onCharTyped(display) }
            )
        }

        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = "⌫",
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
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
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
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
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
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
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
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
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
        onSwitchKeyboard = onSwitchKeyboard,
        onSpace = onSpace,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun BottomRow(
    rowHeight: Dp,
    modeLabel: String,
    editorContext: EditorContext,
    onModeClick: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSpace: () -> Unit,
    onAction: () -> Unit,
    onCharTyped: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        // Mode toggle (?123 / ABC)
        KeyButton(
            modifier = Modifier.weight(1.3f),
            text = modeLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            isSpecial = true,
            onClick = onModeClick
        )

        // Emoji / Globe toggle
        KeyButton(
            modifier = Modifier.weight(0.9f),
            text = "☺",
            fontSize = 17.sp,
            isSpecial = true,
            onClick = { onCharTyped("😊") }
        )

        // IME switcher
        KeyButton(
            modifier = Modifier.weight(0.9f),
            text = "🌐",
            fontSize = 16.sp,
            isSpecial = true,
            onClick = onSwitchKeyboard
        )

        // Spacebar (Pill style labeled "English")
        KeyButton(
            modifier = Modifier.weight(3.7f),
            text = "English",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            isPill = true,
            onClick = onSpace
        )

        // Quick action symbol
        KeyButton(
            modifier = Modifier.weight(0.9f),
            text = "⚡",
            fontSize = 15.sp,
            isSpecial = true,
            onClick = { onCharTyped("!") }
        )

        // Privacy indicator
        KeyButton(
            modifier = Modifier.weight(0.9f),
            text = "🕶",
            fontSize = 15.sp,
            isSpecial = true,
            onClick = {}
        )

        // Enter / Action
        val (actionSymbol, isAccent) = when (editorContext.action) {
            KeyboardAction.DONE -> "✓" to true
            KeyboardAction.GO -> "➔" to true
            KeyboardAction.SEARCH -> "🔍" to true
            KeyboardAction.SEND -> "➤" to true
            KeyboardAction.NEXT -> "⏭" to true
            KeyboardAction.ENTER -> "↵" to false
        }

        KeyButton(
            modifier = Modifier.weight(1.4f),
            text = actionSymbol,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            isAccent = isAccent,
            isSpecial = !isAccent,
            onClick = onAction
        )
    }
}
