package com.notokens.monolog.ime.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ai.KeyboardLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import com.notokens.monolog.ime.KeyboardAction
import com.notokens.monolog.ime.KeyboardLayer
import com.notokens.monolog.ime.KeyboardState
import com.notokens.monolog.ime.QwertyLayout
import com.notokens.monolog.ime.ShiftState
import com.notokens.monolog.ime.SwipePoint
import com.notokens.monolog.ime.shortcuts.QuickShortcutsMenu
import com.notokens.monolog.ime.shortcuts.QuickShortcutsPanel
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestion
import com.notokens.monolog.capture.ScreenCaptureService
import com.notokens.monolog.ui.*
import com.notokens.monolog.ime.vox.VoxPanel
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun MonologKeyboardView(
    editorContext: EditorContext,
    keyboardState: KeyboardState,
    voxState: VoxState,
    isShortcutsOpen: Boolean,
    isVoxOpen: Boolean,
    shortcutDraft: ShortcutDraft,
    onDraftChange: (ShortcutDraft) -> Unit,
    onSuggestedShortcut: (ShortcutSuggestion) -> Unit,
    onToggleShortcuts: () -> Unit,
    onCaptureContext: () -> Unit,
    onDismissShortcuts: () -> Unit,
    searchQuery: TextFieldValue?,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    ai: ImeAi,
    aiAllowed: Boolean,
    swipeAllowed: Boolean,
    language: KeyboardLanguage,
    onOpenSettings: () -> Unit,
    onEnterVox: () -> Unit,
    onExitVox: () -> Unit,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onClearTranscript: () -> Unit,
    onCommitTranscript: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onCharTyped: (String) -> Unit,
    onSwipe: (List<SwipePoint>) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onAction: () -> Unit,
    onShiftClick: () -> Unit,
    onCapsLock: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleMoreSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onCloseKeyboard: () -> Unit,
    onToggleIncognito: () -> Unit,
    longPressCharacters: Boolean = true,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowHeight = if (isLandscape) 38.dp else 54.dp
    val recentEmoji = remember { mutableStateListOf<Emoji>() }
    val density = LocalDensity.current
    var keyboardHeightPx by remember { mutableIntStateOf(0) }
    val isAiActive = searchQuery != null
    val overlayHeight = (configuration.screenHeightDp.dp - with(density) { keyboardHeightPx.toDp() })
        .coerceAtLeast(100.dp)

    Column(modifier.fillMaxWidth().monologBackdrop(keyboard = true).navigationBarsPadding()
        .onSizeChanged { keyboardHeightPx = it.height }) {
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
            if (aiAllowed) ai.toolbar?.let { AiResultCard(it, ai::apply) }
        } else {
            if (editorContext.isIncognito) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MonologIcon(Glyph.PRIVACY, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("Incognito is on", color = PaperKeyText, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        androidx.compose.material3.Text("Nothing saved", color = PaperKeyText.copy(alpha = .7f), fontSize = 11.sp)
                    }
                    androidx.compose.material3.Text(if (editorContext.isUserIncognito) "15 minutes" else "Automatic",
                        color = PaperKeyText.copy(alpha = .7f), fontSize = 11.sp)
                }
            }
            if (searchQuery == null && isShortcutsOpen && shortcutDraft.isEditing) {
                QuickShortcutsPanel(
                    draft = shortcutDraft,
                    onDraftChange = onDraftChange,
                    editorContext = editorContext,
                    onClose = onToggleShortcuts
                )
            }

            if (aiAllowed && !isShortcutsOpen && searchQuery == null) {
                KeyboardAiControls(ai, language)
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                KeyboardHeader(
                    editorContext = editorContext,
                    onToggleShortcuts = onToggleShortcuts,
                    onCaptureContext = onCaptureContext,
                    onOpenSettings = onOpenSettings,
                    onEnterVox = onEnterVox,
                    onCloseKeyboard = onCloseKeyboard,
                    onToggleIncognito = onToggleIncognito,
                    isContextActive = !editorContext.isIncognito && (isShortcutsOpen || ScreenCaptureService.isReady),
                    suggestions = {
                        if (aiAllowed && !isShortcutsOpen && searchQuery == null) {
                            KeyboardSuggestionStrip(ai, language, onSuggestedShortcut)
                        }
                    }
                )
                if (searchQuery != null) {
                    Popup(
                        popupPositionProvider = AboveKeyboardHeader,
                        properties = PopupProperties(focusable = false, clippingEnabled = false)
                    ) {
                        Box(Modifier.width(maxWidth).height(overlayHeight)) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
                                .clickable(onClickLabel = "Close Monolog AI", onClick = onCloseSearch))
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                KeyboardSearchPanel(searchQuery, onSearchQueryChange, onCloseSearch, ai, overlayHeight)
                            }
                        }
                    }
                }
                if (isShortcutsOpen && !shortcutDraft.isEditing && searchQuery == null) {
                    val keyboardHeight = if (isLandscape) 244 else 312
                    val menuHeight = (configuration.screenHeightDp - keyboardHeight).coerceIn(80, 290).dp
                    Popup(
                        popupPositionProvider = AboveKeyboardHeader,
                        onDismissRequest = onDismissShortcuts,
                        properties = PopupProperties(
                            focusable = false, dismissOnClickOutside = true, clippingEnabled = false
                        )
                    ) {
                        QuickShortcutsMenu(
                            panelHeight = menuHeight,
                            onDraftChange = onDraftChange,
                            onClose = onToggleShortcuts,
                            modifier = Modifier.width(minOf(maxWidth, 312.dp))
                        )
                    }
                }
            }

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
                        language = language,
                        onCharTyped = onCharTyped,
                        swipeEnabled = swipeAllowed && language.supportsLatinSwipe && (!isShortcutsOpen || shortcutDraft.isEditing),
                        longPressCharacters = longPressCharacters,
                        onSwipe = onSwipe,
                        onShiftClick = onShiftClick,
                        onCapsLock = onCapsLock,
                        onBackspace = onBackspace,
                        onToggleSymbols = onToggleSymbols,
                        onToggleEmoji = onToggleEmoji,
                        onSpace = onSpace,
                        onOpenSearch = onOpenSearch,
                        isAiActive = isAiActive,
                        onToggleIncognito = onToggleIncognito,
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
                        onSpace = onSpace,
                        onOpenSearch = onOpenSearch,
                        isAiActive = isAiActive,
                        onToggleIncognito = onToggleIncognito,
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
                        onSpace = onSpace,
                        onOpenSearch = onOpenSearch,
                        isAiActive = isAiActive,
                        onToggleIncognito = onToggleIncognito,
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
                        onSpace = onSpace,
                        onOpenSearch = onOpenSearch,
                        isAiActive = isAiActive,
                        onToggleIncognito = onToggleIncognito,
                        onAction = onAction
                    )
                }
            }
        }
    }
}

private object AboveKeyboardHeader : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        // The IME window starts at the keyboard, so the callout sits at a negative Y offset.
        return IntOffset(x, anchorBounds.top - popupContentSize.height)
    }
}

@Composable
private fun LettersLayout(
    rowHeight: Dp,
    shiftState: ShiftState,
    editorContext: EditorContext,
    language: KeyboardLanguage,
    onCharTyped: (String) -> Unit,
    swipeEnabled: Boolean,
    longPressCharacters: Boolean,
    onSwipe: (List<SwipePoint>) -> Unit,
    onShiftClick: () -> Unit,
    onCapsLock: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSpace: () -> Unit,
    onOpenSearch: () -> Unit,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit
) {
    val isUpper = shiftState.isUppercase
    val nativeKeys = remember(language) { language.nativeKeys() }
    var page by remember(language) { mutableIntStateOf(0) }

    if (nativeKeys.isNotEmpty()) {
        val pages = remember(nativeKeys) { nativeKeys.chunked(24) }
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                repeat(8) { column ->
                    val key = pages[page].getOrNull(row * 8 + column)
                    if (key == null) Spacer(Modifier.weight(1f))
                    else KeyButton(Modifier.weight(1f), text = key, onClick = { onCharTyped(key) })
                }
                if (row == 1) KeyButton(Modifier.weight(1.5f), text = "⇥", onClick = { page = (page + 1) % pages.size },
                    label = "Next character page")
                if (row == 2) KeyButton(Modifier.weight(1.5f), text = "⌫", glyph = Glyph.BACKSPACE, onClick = onBackspace)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().then(swipeTypingModifier(swipeEnabled, onSwipe))) {
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                QwertyLayout.rows[0].forEachIndexed { index, letter ->
                    val display = (if (isUpper) letter.uppercaseChar() else letter).toString()
                    val number = "1234567890"[index].toString()
                    KeyButton(Modifier.weight(1f), text = display, subText = number.takeIf { longPressCharacters },
                        longClickLabel = "Type $number",
                        onLongClick = if (longPressCharacters) ({ onCharTyped(number) }) else null,
                        onClick = { onCharTyped(display) })
                }
            }
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                Spacer(Modifier.weight(QwertyLayout.homeRowInset))
                QwertyLayout.rows[1].forEachIndexed { index, letter ->
                    val display = (if (isUpper) letter.uppercaseChar() else letter).toString()
                    val symbol = "@#\u00a3_&-+()"[index].toString()
                    KeyButton(Modifier.weight(1f), text = display, subText = symbol.takeIf { longPressCharacters },
                        longClickLabel = "Type $symbol",
                        onLongClick = if (longPressCharacters) ({ onCharTyped(symbol) }) else null,
                        onClick = { onCharTyped(display) })
                }
                Spacer(Modifier.weight(QwertyLayout.homeRowInset))
            }
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                KeyButton(
                    modifier = Modifier.weight(QwertyLayout.shiftWeight),
                    glyph = Glyph.SHIFT,
                    isSpecial = true,
                    isActive = isUpper,
                    label = when (shiftState) {
                        ShiftState.OFF -> "Shift off"
                        ShiftState.ONCE -> "Shift on"
                        ShiftState.CAPS_LOCK -> "Caps lock on"
                    },
                    longClickLabel = "Toggle caps lock",
                    onLongClick = onCapsLock,
                    onClick = onShiftClick
                )
                QwertyLayout.rows[2].forEachIndexed { index, letter ->
                    val display = (if (isUpper) letter.uppercaseChar() else letter).toString()
                    val symbol = "*/:;!?'"[index].toString()
                    KeyButton(Modifier.weight(1f), text = display, subText = symbol.takeIf { longPressCharacters },
                        longClickLabel = "Type $symbol",
                        onLongClick = if (longPressCharacters) ({ onCharTyped(symbol) }) else null,
                        onClick = { onCharTyped(display) })
                }
                KeyButton(Modifier.weight(QwertyLayout.shiftWeight), glyph = Glyph.BACKSPACE,
                    isSpecial = true, onClick = onBackspace)
            }
        }
    }
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "?123",
        editorContext = editorContext,
        onModeClick = onToggleSymbols,
        onEmojiClick = onToggleEmoji,
        onSpace = onSpace,
        onOpenSearch = onOpenSearch,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
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
    onSpace: () -> Unit,
    onOpenSearch: () -> Unit,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
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
        onSpace = onSpace,
        onOpenSearch = onOpenSearch,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
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
    onSpace: () -> Unit,
    onOpenSearch: () -> Unit,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
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
        onSpace = onSpace,
        onOpenSearch = onOpenSearch,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
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
    onSpace: () -> Unit,
    onOpenSearch: () -> Unit,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit
) {
    EmojiPicker(height = rowHeight * 3 + 8.dp, recent = recent, onPick = onEmojiPicked)
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleLetters,
        onEmojiClick = onToggleLetters,
        onSpace = onSpace,
        onOpenSearch = onOpenSearch,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
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
    onSpace: () -> Unit,
    onOpenSearch: () -> Unit,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit,
    onCharTyped: (String) -> Unit,
    onEmojiBackspace: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.notokens.monolog.data.SettingsStore(context.applicationContext) }
    val languageId by settings.keyboardLanguage.collectAsState(initial = "en")
    val language = KeyboardLanguage.of(languageId)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight + 4.dp)) {
        KeyButton(Modifier.weight(1.2f), text = modeLabel, fontSize = 16.sp, onClick = onModeClick)
        KeyButton(Modifier.weight(.7f), text = ",", label = "Comma", onClick = { onCharTyped(",") })
        KeyButton(Modifier.weight(.8f), glyph = Glyph.SMILE,
            label = if (onEmojiBackspace == null) "Open emoji picker" else "Return to letters", onClick = onEmojiClick)
        KeyButton(
            Modifier.weight(if (onEmojiBackspace == null) 4.1f else 3.2f),
            isPill = true,
            isActive = isAiActive,
            glow = isAiActive,
            label = if (isAiActive) "Space, Monolog AI active" else "Space",
            longClickLabel = if (isAiActive) "Close Monolog AI" else "Open Monolog AI search",
            onLongClick = onOpenSearch.takeUnless { editorContext.isIncognito },
            onClick = onSpace
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Text(if (isAiActive) "Monolog AI" else language.label.substringBefore('·').trim(),
                    color = if (isAiActive) androidx.compose.material3.MaterialTheme.colorScheme.primary else PaperKeyText,
                    fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!editorContext.isIncognito) MonologIcon(Glyph.BOLT, Modifier.size(17.dp))
            }
        }
        KeyButton(
            Modifier.weight(1f),
            label = if (editorContext.isIncognito) "Period, incognito on" else "Period, incognito off",
            longClickLabel = when {
                editorContext.isUserIncognito -> "Turn off incognito"
                editorContext.isIncognito -> "Incognito is automatic for this private field"
                else -> "Turn on incognito for 15 minutes"
            },
            onLongClick = onToggleIncognito,
            glow = editorContext.isIncognito,
            onClick = { onCharTyped(".") }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MonologIcon(Glyph.PRIVACY, Modifier.size(18.dp))
                androidx.compose.material3.Text(".", color = PaperKeyText, fontSize = 17.sp, lineHeight = 16.sp)
            }
        }
        if (onEmojiBackspace != null) {
            KeyButton(Modifier.weight(.9f), glyph = Glyph.BACKSPACE, label = "Backspace", onClick = onEmojiBackspace)
        }
        KeyButton(
            Modifier.weight(1.35f),
            glyph = if (isAiActive) Glyph.ARROW else when (editorContext.action) {
                KeyboardAction.SEARCH -> Glyph.SEARCH
                KeyboardAction.GO, KeyboardAction.SEND, KeyboardAction.NEXT -> Glyph.ARROW
                else -> Glyph.ENTER
            },
            onClick = onAction
        )
    }
}
