package com.notokens.monolog.ime.ui

import kotlinx.coroutines.launch

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.data.SettingsStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
    layoutSession: Int,
    editorContext: EditorContext,
    editorActive: Boolean,
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
    onRequestPermission: () -> Unit,
    onCharTyped: (String) -> Unit,
    onPasteImage: (ClipboardImage) -> Boolean,
    onLetterTyped: (String) -> Unit,
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
    val recentEmoji = remember { mutableStateListOf<Emoji>() }
    var numberPadOpen by remember(layoutSession, keyboardState.layer) { mutableStateOf(false) }
    val showSymbolTools = keyboardState.layer == KeyboardLayer.SYMBOLS ||
        keyboardState.layer == KeyboardLayer.MORE_SYMBOLS
    val density = LocalDensity.current
    var keyboardHeightPx by remember { mutableIntStateOf(0) }
    val isAiActive = searchQuery != null
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context.applicationContext) }
    val swapVoxAndAi by settings.swapVoxAndAi.collectAsState(initial = false)
    val heightPercent by settings.keyHeightPercent.collectAsState(initial = 100)
    val keyBorders by settings.keyBorders.collectAsState(initial = false)
    val keyPopups by settings.keyPopups.collectAsState(initial = false)
    val keyPopupMs by settings.keyPopupMs.collectAsState(initial = 70)
    val maxRowHeight = ((configuration.screenHeightDp.dp - 170.dp) / 4).coerceAtLeast(32.dp)
    val rowHeight = ((if (isLandscape) 38.dp else 54.dp) * (heightPercent / 100f))
        .coerceIn(30.dp, maxRowHeight)
    val bodyHeight = rowHeight * 4 + 24.dp
    val smartFilter by settings.smartVoxFilter.collectAsState(initial = true)
    val keyboardScope = androidx.compose.runtime.rememberCoroutineScope()
    val onHoldSpace = if (swapVoxAndAi) onEnterVox else onOpenSearch
    val privateMode = editorContext.isIncognito
    val clips = rememberKeyboardClips(settings, privateMode, editorActive)
    val clipboardEnabled by settings.keyboardClipboard.collectAsState(false)
    val images = rememberClipboardImages(clipboardEnabled, privateMode, editorActive)
    val phoneActions by settings.phoneActions.collectAsState(false)
    val customTriggers by settings.sensitiveWords.collectAsState("")
    val copiedActions = com.notokens.monolog.ime.rememberClipboardActions(
        phoneActions && editorActive && !privateMode && ai.journalAllowed, customTriggers)
    fun pickEmoji(emoji: Emoji) {
        onCharTyped(emoji.text)
        if (!privateMode) {
            recentEmoji.remove(emoji)
            recentEmoji.add(0, emoji)
            if (recentEmoji.size > 36) recentEmoji.removeAt(recentEmoji.lastIndex)
        }
    }
    var dashboardOpen by remember(layoutSession) { mutableStateOf(false) }
    LaunchedEffect(isShortcutsOpen, isVoxOpen, isAiActive) {
        if (isShortcutsOpen || isVoxOpen || isAiActive) dashboardOpen = false
    }
    val overlayHeight = (configuration.screenHeightDp.dp - with(density) { keyboardHeightPx.toDp() })
        .coerceAtLeast(100.dp)

    KeyPreviewHost(keyPopups && !dashboardOpen && !isVoxOpen) {
    androidx.compose.runtime.CompositionLocalProvider(LocalKeyAppearance provides KeyAppearance(keyBorders, keyPopups, keyPopupMs)) {
    Column(modifier.fillMaxWidth().monologBackdrop(keyboard = true).navigationBarsPadding()
        .onSizeChanged { keyboardHeightPx = it.height }) {
        // If Vox mode is open, render the full Vox Panel
        if (isVoxOpen) {
            VoxPanel(
                voxState = voxState,
                onStartListening = onStartListening,
                onPauseListening = onPauseListening,
                onClearTranscript = onClearTranscript,
                smartFilter = smartFilter,
                onToggleSmartFilter = { keyboardScope.launch { settings.setSmartVoxFilter(!smartFilter) } },
                contextEnabled = !privateMode,
                contextActive = ScreenCaptureService.isReady,
                onContext = onCaptureContext,
                onContextHold = onToggleShortcuts,
                onCancel = onExitVox,
                onRequestPermission = onRequestPermission,
                onOpenModels = onOpenSettings
            )
            if (aiAllowed) ai.toolbar?.let { AiResultCard(it, ai::apply) }
        } else {
            if (privateMode) IncognitoStrip(editorContext.isUserIncognito, settings)
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
                    onToggleDashboard = { dashboardOpen = !dashboardOpen },
                    dashboardOpen = dashboardOpen,
                    onModeAction = if (swapVoxAndAi) onOpenSearch else onEnterVox,
                    toolbarAi = swapVoxAndAi,
                    isContextActive = !editorContext.isIncognito && (isShortcutsOpen || ScreenCaptureService.isReady),
                    suggestions = {
                        if (!dashboardOpen && !isShortcutsOpen && searchQuery == null) {
                            if (showSymbolTools) EmojiShortcutStrip(recentEmoji, ::pickEmoji)
                            else if (aiAllowed) KeyboardSuggestionStrip(ai, language, onSuggestedShortcut, copiedActions)
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

            AnimatedContent(
                dashboardOpen,
                modifier = Modifier.fillMaxWidth().height(bodyHeight),
                transitionSpec = { fadeIn(tween(100)) togetherWith fadeOut(tween(100)) },
                label = "Keyboard dashboard"
            ) { shown ->
                if (shown) KeyboardDashboard(
                    settings = settings,
                    privateMode = privateMode,
                    clips = clips,
                    images = images,
                    onPasteImage = { if (onPasteImage(it)) dashboardOpen = false },
                    rowHeight = rowHeight,
                    onSettings = onOpenSettings,
                    onIncognito = onToggleIncognito,
                    onHide = onCloseKeyboard,
                    onVox = onEnterVox,
                    onPaste = { value -> onCharTyped(value); dashboardOpen = false }
                )
                else Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 3.dp, end = 3.dp, top = 4.dp, bottom = 16.dp)
                ) {
                    when (keyboardState.layer) {
                        KeyboardLayer.LETTERS -> LettersLayout(
                            rowHeight = rowHeight,
                            shiftState = keyboardState.shiftState,
                            editorContext = editorContext,
                            language = language,
                            onCharTyped = onCharTyped,
                            onLetterTyped = onLetterTyped,
                            swipeEnabled = swipeAllowed && language.supportsLatinSwipe && (!isShortcutsOpen || shortcutDraft.isEditing),
                            longPressCharacters = longPressCharacters,
                            onSwipe = onSwipe,
                            onShiftClick = onShiftClick,
                            onCapsLock = onCapsLock,
                            onBackspace = onBackspace,
                            onToggleSymbols = onToggleSymbols,
                            onToggleEmoji = onToggleEmoji,
                            onSpace = onSpace,
                            onHoldSpace = onHoldSpace,
                            spacebarVox = swapVoxAndAi,
                            isAiActive = isAiActive,
                            onToggleIncognito = onToggleIncognito,
                            onAction = onAction
                        )

                        KeyboardLayer.SYMBOLS, KeyboardLayer.MORE_SYMBOLS -> SymbolsLayout(
                            moreSymbols = keyboardState.layer == KeyboardLayer.MORE_SYMBOLS,
                            rowHeight = rowHeight,
                            numberPadOpen = numberPadOpen,
                            onNumberPad = { numberPadOpen = !numberPadOpen },
                            editorContext = editorContext,
                            onCharTyped = onCharTyped,
                            onToggleMoreSymbols = onToggleMoreSymbols,
                            onBackspace = onBackspace,
                            onToggleSymbols = onToggleSymbols,
                            onToggleEmoji = onToggleEmoji,
                            onSpace = onSpace,
                            onHoldSpace = onHoldSpace,
                            spacebarVox = swapVoxAndAi,
                            isAiActive = isAiActive,
                            onToggleIncognito = onToggleIncognito,
                            onAction = onAction
                        )

                        KeyboardLayer.EMOJI -> EmojiLayout(
                            rowHeight = rowHeight,
                            recent = if (editorContext.isIncognito) emptyList() else recentEmoji,
                            onEmojiPicked = ::pickEmoji,
                            editorContext = editorContext,
                            onCharTyped = onCharTyped,
                            onBackspace = onBackspace,
                            onToggleLetters = onToggleEmoji,
                            onSpace = onSpace,
                            onHoldSpace = onHoldSpace,
                            spacebarVox = swapVoxAndAi,
                            isAiActive = isAiActive,
                            onToggleIncognito = onToggleIncognito,
                            onAction = onAction
                        )
                    }
                }
            }
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
    onLetterTyped: (String) -> Unit,
    swipeEnabled: Boolean,
    longPressCharacters: Boolean,
    onSwipe: (List<SwipePoint>) -> Unit,
    onShiftClick: () -> Unit,
    onCapsLock: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSpace: () -> Unit,
    onHoldSpace: () -> Unit,
    spacebarVox: Boolean,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit
) {
    val isUpper = shiftState.isUppercase
    val typeLetterOrSymbol: (String) -> Unit = { value ->
        if (value.any(Char::isLetter)) onLetterTyped(value) else onCharTyped(value)
    }
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
        val rows = language.letterRows ?: QwertyLayout.rows
        val homeInset = (QwertyLayout.width - rows[1].length) / 2f
        Column(Modifier.fillMaxWidth().then(swipeTypingModifier(swipeEnabled, rows, onSwipe))) {
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                rows[0].forEachIndexed { index, letter ->
                    LetterKey(Modifier.weight(1f), letter, isUpper, longPressCharacters,
                        language.alternateLetters[letter], "1234567890"[index], typeLetterOrSymbol)
                }
            }
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                if (homeInset > 0) Spacer(Modifier.weight(homeInset))
                rows[1].forEachIndexed { index, letter ->
                    LetterKey(Modifier.weight(1f), letter, isUpper, longPressCharacters,
                        language.alternateLetters[letter], "@#\u00a3_&-+()"[index], typeLetterOrSymbol)
                }
                if (homeInset > 0) Spacer(Modifier.weight(homeInset))
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
                rows[2].forEachIndexed { index, letter ->
                    LetterKey(Modifier.weight(1f), letter, isUpper, longPressCharacters,
                        language.alternateLetters[letter], "*\"':;!?"[index], typeLetterOrSymbol)
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
        onHoldSpace = onHoldSpace,
        spacebarVox = spacebarVox,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun LetterKey(
    modifier: Modifier,
    letter: Char,
    uppercase: Boolean,
    longPressCharacters: Boolean,
    accents: String?,
    alternate: Char,
    onType: (String) -> Unit
) {
    fun display(value: Char) = if (!uppercase) value else if (value == 'ß') 'ẞ' else value.uppercaseChar()
    val normal = display(letter).toString()
    if (accents == null) {
        val symbol = alternate.toString()
        KeyButton(modifier, text = normal, subText = symbol.takeIf { longPressCharacters },
            longClickLabel = "Type $symbol", onLongClick = if (longPressCharacters) ({ onType(symbol) }) else null,
            onClick = { onType(normal) })
        return
    }
    val options = remember(accents, uppercase, alternate) {
        accents.map { display(it).toString() } + alternate.toString()
    }
    var expanded by remember(letter) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        KeyButton(Modifier.fillMaxSize(), text = normal,
            subText = options.first().takeIf { longPressCharacters },
            longClickLabel = "Choose alternate character",
            onLongClick = if (longPressCharacters) ({ expanded = true }) else null,
            onClick = { onType(normal) })
        DropdownMenu(expanded && longPressCharacters, onDismissRequest = { expanded = false }) {
            options.forEach { symbol ->
                DropdownMenuItem(text = { Text(symbol) }, onClick = {
                    expanded = false
                    onType(symbol)
                })
            }
        }
    }
}

@Composable
private fun SymbolsLayout(
    rowHeight: Dp,
    moreSymbols: Boolean,
    numberPadOpen: Boolean,
    onNumberPad: () -> Unit,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onToggleMoreSymbols: () -> Unit,
    onBackspace: () -> Unit,
    onToggleSymbols: () -> Unit,
    onToggleEmoji: () -> Unit,
    onSpace: () -> Unit,
    onHoldSpace: () -> Unit,
    spacebarVox: Boolean,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit
) {
    val row1 = if (moreSymbols) listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
        else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = if (moreSymbols) listOf("$", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\")
        else listOf("@", "#", "£", "_", "&", "-", "+", "(", ")", "/")
    val row3 = if (moreSymbols) listOf("%", "_", "[", "]", "©", "®", "™")
        else listOf("*", "\"", "'", ":", ";", "!", "?")

    if (numberPadOpen) {
        DigitsGrid(rowHeight, onCharTyped, onBackspace)
    } else {
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
                text = if (moreSymbols) "123" else "=\\<",
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

    }

    // Row 4
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleSymbols,
        onEmojiClick = onToggleEmoji,
        onNumberPad = onNumberPad,
        numberPadOpen = numberPadOpen,
        onSpace = onSpace,
        onHoldSpace = onHoldSpace,
        spacebarVox = spacebarVox,
        isAiActive = isAiActive,
        onToggleIncognito = onToggleIncognito,
        onAction = onAction,
        onCharTyped = onCharTyped
    )
}

@Composable
private fun DigitsGrid(
    rowHeight: Dp,
    onCharTyped: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(listOf("1", "2", "3", "+"), listOf("4", "5", "6", "-"), listOf("7", "8", "9", "0"))
    rows.forEachIndexed { index, row ->
        Row(Modifier.fillMaxWidth().height(rowHeight)) {
            row.forEach { digit ->
                KeyButton(Modifier.weight(1f), text = digit, onClick = { onCharTyped(digit) })
            }
            when (index) {
                0 -> KeyButton(Modifier.weight(1f), text = "/", onClick = { onCharTyped("/") })
                1 -> KeyButton(Modifier.weight(1f), glyph = Glyph.BACKSPACE, isSpecial = true, onClick = onBackspace)
                else -> KeyButton(Modifier.weight(1f), text = "=", onClick = { onCharTyped("=") })
            }
        }
    }
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
    onHoldSpace: () -> Unit,
    spacebarVox: Boolean,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit
) {
    EmojiPicker(height = rowHeight * 3, recent = recent, onPick = onEmojiPicked)
    BottomRow(
        rowHeight = rowHeight,
        modeLabel = "ABC",
        editorContext = editorContext,
        onModeClick = onToggleLetters,
        onEmojiClick = onToggleLetters,
        onSpace = onSpace,
        onHoldSpace = onHoldSpace,
        spacebarVox = spacebarVox,
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
    onHoldSpace: () -> Unit,
    spacebarVox: Boolean,
    isAiActive: Boolean,
    onToggleIncognito: () -> Unit,
    onAction: () -> Unit,
    onCharTyped: (String) -> Unit,
    onEmojiBackspace: (() -> Unit)? = null,
    onNumberPad: (() -> Unit)? = null,
    numberPadOpen: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.notokens.monolog.data.SettingsStore(context.applicationContext) }
    val languageId by settings.keyboardLanguage.collectAsState(initial = "en")
    val language = KeyboardLanguage.of(languageId)
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight + 4.dp)) {
        KeyButton(Modifier.weight(1.4f), text = modeLabel, fontSize = 16.sp, onClick = onModeClick)
        KeyButton(
            Modifier.weight(1.15f),
            label = "Comma",
            previewText = ",",
            longClickLabel = if (onEmojiBackspace == null) "Open emoji picker" else "Return to letters",
            onLongClick = onEmojiClick,
            onClick = { onCharTyped(",") }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MonologIcon(Glyph.SMILE, Modifier.size(15.dp))
                Text(",", color = PaperKeyText, fontSize = 20.sp, lineHeight = 18.sp)
            }
        }
        if (onNumberPad != null) {
            KeyButton(
                Modifier.weight(1f),
                isActive = numberPadOpen,
                label = if (numberPadOpen) "Return to symbols" else "Open number pad",
                onClick = onNumberPad
            ) {
                Text("1 2\n3 4", color = PaperKeyText, fontSize = 10.sp,
                    lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        KeyButton(
            Modifier.weight(when {
                onNumberPad != null -> 3.9f
                onEmojiBackspace != null -> 4f
                else -> 4.9f
            }),
            isPill = true,
            isActive = isAiActive,
            glow = false,
            tracing = isAiActive,
            label = if (isAiActive) "Space, Monolog AI active" else "Space",
            longClickLabel = when {
                spacebarVox -> "Open Vox"
                isAiActive -> "Close Monolog AI"
                else -> "Open Monolog AI search"
            },
            onLongClick = onHoldSpace.takeUnless { editorContext.isRestrictedField || editorContext.isNumber },
            onClick = onSpace
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Text(if (isAiActive) "AI Ask" else language.label.substringBefore('·').trim(),
                    color = if (isAiActive) androidx.compose.material3.MaterialTheme.colorScheme.primary else PaperKeyText,
                    fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!editorContext.isIncognito) MonologIcon(if (spacebarVox) Glyph.MIC else Glyph.BOLT, Modifier.size(17.dp))
            }
        }
        KeyButton(
            Modifier.weight(1.1f),
            previewText = ".",
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
            Modifier.weight(1.4f),
            glyph = if (isAiActive) Glyph.ARROW else when (editorContext.action) {
                KeyboardAction.SEARCH -> Glyph.SEARCH
                KeyboardAction.GO, KeyboardAction.SEND, KeyboardAction.NEXT -> Glyph.ARROW
                else -> Glyph.ENTER
            },
            onClick = onAction
        )
    }
}
