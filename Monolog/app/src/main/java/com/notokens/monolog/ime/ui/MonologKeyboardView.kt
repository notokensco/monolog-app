package com.notokens.monolog.ime.ui

import kotlinx.coroutines.launch
import android.content.Intent
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ime.ChatAssist
import com.notokens.monolog.ime.ChatAssistService
import com.notokens.monolog.ime.ChatAssistSetupActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.notokens.monolog.data.KeyboardPlacement

import android.content.res.Configuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.KeyboardOption
import com.notokens.monolog.data.KeyboardTypeface
import com.notokens.monolog.data.KeyTapSound
import com.notokens.monolog.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.notokens.monolog.ime.SensitiveDraft
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
    voxLanguage: com.notokens.monolog.ime.vox.VoxLanguage,
    voxLanguages: List<com.notokens.monolog.ime.vox.VoxLanguage>,
    onVoxLanguage: (com.notokens.monolog.ime.vox.VoxLanguage) -> Unit,
    cursorDrag: CursorDrag,
    cursorActive: Boolean,
    cursorCharacter: String?,
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
    systemAutofillSuggestions: List<android.view.inputmethod.InlineSuggestion> = emptyList(),
    swipeAllowed: Boolean,
    language: KeyboardLanguage,
    onOpenSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onAppearance: () -> Unit,
    onEditorCommand: (EditorCommand) -> Unit,
    onFloatingBounds: (android.graphics.Rect?) -> Unit,
    translation: KeyboardTranslation,
    onEnterVox: () -> Unit,
    onExitVox: () -> Unit,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onDeleteVoxWord: () -> Unit,
    onRequestPermission: () -> Unit,
    onCharTyped: (String) -> Unit,
    onPasteText: (String) -> Unit = onCharTyped,
    onEmojiSuggestion: (String, String) -> Boolean,
    onPasteImage: (ClipboardImage) -> Boolean,
    onPasteMedia: (KeyboardMedia.Item) -> Boolean,
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
    modifier: Modifier = Modifier,
    voxLanguageLabel: String = voxLanguage.title,
    rescueHint: Boolean = false,
    onConsumeRescueHint: () -> Unit = {},
    onDeleteWords: ((Int) -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var numberPadOpen by remember(layoutSession, keyboardState.layer, editorContext.isNumber) {
        mutableStateOf(editorContext.isNumber)
    }
    val showSymbolTools = keyboardState.layer == KeyboardLayer.SYMBOLS ||
        keyboardState.layer == KeyboardLayer.MORE_SYMBOLS
    val density = LocalDensity.current
    var keyboardHeightPx by remember { mutableIntStateOf(0) }
    val isAiActive = searchQuery != null
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context.applicationContext) }
    val options by settings.keyboardOptions.collectAsState(KeyboardOption.entries.toSet())
    val typeface by settings.keyboardTypeface.collectAsState(KeyboardTypeface.MONOLOG)
    val fontPercent by settings.keyboardFontSize.collectAsState(100)
    val sound by settings.keyTapSound.collectAsState(KeyTapSound.OFF)
    val soundRevision by settings.keyTapSoundRevision.collectAsState(0L)
    val playSound = rememberKeySound(sound, soundRevision)
    val font = remember(typeface) { when (typeface) {
        KeyboardTypeface.MONOLOG -> null
        KeyboardTypeface.GEIST -> FontFamily(Font(R.font.geist))
        KeyboardTypeface.SYSTEM -> FontFamily.Default
    } }
    val savedEmoji by settings.recentEmoji.collectAsState("")
    val recentEmoji = remember(savedEmoji) {
        savedEmoji.lineSequence().mapNotNull { row ->
            val fields = row.split('\t', limit = 2)
            if (fields.size == 2) Emoji(fields[0], fields[1]) else null
        }.toList()
    }
    val swapVoxAndAi by settings.swapVoxAndAi.collectAsState(initial = false)
    val heightPercent by settings.keyHeightPercent.collectAsState(initial = 100)
    val cursorOnSpace by settings.cursorOnSpace.collectAsState(false)
    val placement by settings.keyboardPlacement.collectAsState(KeyboardPlacement.FULL)
    val compactKeys by settings.compactKeys.collectAsState(false)
    val keyBorders by settings.keyBorders.collectAsState(initial = false)
    val keyPopups by settings.keyPopups.collectAsState(initial = true)
    val keyPopupMs by settings.keyPopupMs.collectAsState(initial = 70)
    val vibrationHaptics by settings.vibrationHaptics.collectAsState(initial = true)
    val keyLongPressMs by settings.keyLongPressMs.collectAsState(initial = 350)
    val swipeTrail by settings.swipeTrail.collectAsState(initial = 120)
    val numberRow = if (editorContext.isPassword) KeyboardOption.PASSWORD_NUMBERS in options
        else KeyboardOption.NUMBERS in options
    val maxRowHeight = ((configuration.screenHeightDp.dp - 170.dp) / if (numberRow) 5 else 4).coerceAtLeast(32.dp)
    val preferredRowHeight = ((if (isLandscape) 38.dp else 54.dp) * (heightPercent / 100f))
        .coerceIn(30.dp, maxRowHeight)
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
        phoneActions && editorActive && !privateMode && KeyboardOption.COPIED in options, customTriggers)
    val clipboardText = rememberClipboardSuggestion(
        editorActive && !privateMode && KeyboardOption.COPIED in options, customTriggers)
    val input = ai.toolbarInput
    val hasInput = ai.hasDraft || input.isNotBlank()
    val shortcode = emojiShortcode(input).takeUnless { editorContext.isRestrictedField }
    val nameMatches by androidx.compose.runtime.produceState<Pair<String, EmojiMatches?>?>(null, input, editorContext.isRestrictedField) {
        value = if ((KeyboardOption.EMOJI !in options && shortcode == null) ||
            (input.lastOrNull()?.isLetter() != true && shortcode == null) || editorContext.isRestrictedField) null else {
            input to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                matchEmojiName(context.assets, input)
            }
        }
    }
    val emojiMatches = nameMatches?.takeIf { it.first == input }?.second
    fun pickEmoji(emoji: Emoji) {
        onCharTyped(emoji.text)
        if (!privateMode) keyboardScope.launch { settings.rememberEmoji(emoji.text, emoji.label) }
    }
    fun replaceEmoji(typed: String, emoji: Emoji) {
        if (onEmojiSuggestion(typed, emoji.text) && !privateMode) {
            keyboardScope.launch { settings.rememberEmoji(emoji.text, emoji.label) }
        }
    }
    var dashboardOpen by remember(layoutSession) { mutableStateOf(false) }
    var dashboardClips by remember(layoutSession) { mutableStateOf(false) }
    var panel by remember(layoutSession) { mutableStateOf<KeyboardPanel?>(null) }
    var chatOpen by remember(layoutSession) { mutableStateOf(false) }
    val chatSession by ChatAssist.session.collectAsState()
    val chatError by ChatAssist.failure.collectAsState()
    val chatAllowed = !privateMode && !editorContext.isRestrictedField && !editorContext.isNumber && !editorContext.isEmail &&
        !editorContext.packageName.isNullOrBlank() && editorContext.packageName != context.packageName
    val chatHere = chatSession?.takeIf { chatAllowed && it.target == editorContext.packageName }
    val chatModel = remember(context) { ModelStore.get(context) }
    val chatModelActive by chatModel.active.collectAsState()
    val chatModelState by chatModel.state.collectAsState()
    val canStartChat = chatSession == null && chatModelActive?.isGguf == true && chatModelState is ModelState.Ready
    fun startChatAssist() {
        val target = editorContext.packageName ?: return
        if (!chatAllowed || !canStartChat) return
        chatOpen = false
        runCatching {
            context.startActivity(Intent(context, ChatAssistSetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ChatAssistService.TARGET, target))
        }.onFailure {
            ChatAssist.stop("Couldn’t open Android’s sharing setup.")
            chatOpen = true
        }
    }
    val rescueAllowed = !privateMode && !editorContext.isRestrictedField
    val rescueBadge by com.notokens.monolog.ime.DraftRescue.hasBadge.collectAsState()
    var rescuePreview by remember(layoutSession) { mutableStateOf<com.notokens.monolog.ime.RescueDraft?>(null) }
    fun openRescue() {
        if (!rescueAllowed) return
        dashboardOpen = false
        panel = KeyboardPanel.DRAFT_RESCUE
        onConsumeRescueHint()
    }
    LaunchedEffect(rescueHint) {
        if (rescueHint) {
            kotlinx.coroutines.delay(6000)
            onConsumeRescueHint()
        }
    }
    LaunchedEffect(rescueAllowed, panel) {
        if (!rescueAllowed && panel == KeyboardPanel.DRAFT_RESCUE) panel = null
        if (!rescueAllowed || panel != KeyboardPanel.DRAFT_RESCUE) rescuePreview = null
    }
    var showTranslation by remember(layoutSession) { mutableStateOf(false) }
    fun closeTranslation() {
        if (!showTranslation) return
        translation.close()
        showTranslation = false
    }
    var mediaTab by remember(layoutSession) { mutableStateOf(KeyboardMediaTab.EMOJI) }
    val mediaOptIn by settings.onlineMedia.collectAsState(false)
    var mediaSearch by remember(layoutSession) { mutableStateOf(false) }
    var mediaQuery by remember(layoutSession, mediaTab) { mutableStateOf("") }
    var submittedMediaQuery by remember(layoutSession, mediaTab) { mutableStateOf("") }
    var mediaSearchLayer by remember(layoutSession) { mutableStateOf(KeyboardLayer.LETTERS) }
    var mediaNumberPad by remember(layoutSession) { mutableStateOf(false) }
    var mediaShift by remember(layoutSession) { mutableStateOf(ShiftState.OFF) }
    fun typeMediaQuery(value: String) { mediaQuery = (mediaQuery + value).take(80) }
    var userPinned by remember(layoutSession) { mutableStateOf(false) }
    var placementMenu by remember(layoutSession) { mutableStateOf(false) }
    val mediaAllowed = !privateMode && !editorContext.isRestrictedField
    val mediaWorkspace = keyboardState.layer == KeyboardLayer.EMOJI &&
        !dashboardOpen && !placementMenu && panel == null && !isShortcutsOpen && !isVoxOpen && !isAiActive
    val canSearchMedia = !mediaTab.online || mediaAllowed && mediaOptIn && KeyboardMedia.configured
    val searchingMedia = mediaWorkspace && mediaSearch && canSearchMedia
    val sensitiveMediaSearch = remember(customTriggers) { SensitiveDraft().apply { setWords(customTriggers) } }
    fun showMediaResults(query: String) {
        submittedMediaQuery = query.trim()
        mediaQuery = submittedMediaQuery
        mediaSearch = false
    }
    fun submitMediaSearch(query: String) {
        if (!canSearchMedia) return
        val term = query.trim()
        if (mediaTab.online && term.isNotEmpty() && !sensitiveMediaSearch.matches(term)) {
            val stickers = mediaTab == KeyboardMediaTab.STICKERS
            keyboardScope.launch { settings.rememberMediaSearch(stickers, term) }
        }
        showMediaResults(term)
    }
    fun finishMediaSearch() = submitMediaSearch(mediaQuery)
    fun editMediaSearch() {
        mediaQuery = submittedMediaQuery
        mediaSearchLayer = KeyboardLayer.LETTERS
        mediaNumberPad = false
        mediaShift = ShiftState.OFF
        mediaSearch = true
    }
    val tiles = visibleDashboardTiles(settings)
    val toolbarPins by settings.dashboardPins.collectAsState(emptyList())
    val pinnedTiles = toolbarPins.mapNotNull { id ->
        tiles.firstOrNull { it.name == id && it.allowed(privateMode, editorContext.isRestrictedField) }
    }
    fun activate(tile: DashboardTile) {
        if (!tile.allowed(privateMode, editorContext.isRestrictedField)) return
        userPinned = false
        when (tile) {
            DashboardTile.SETTINGS -> onOpenSettings()
            DashboardTile.PRIVATE -> onToggleIncognito()
            DashboardTile.HIDE -> onCloseKeyboard()
            DashboardTile.VOX -> onEnterVox()
            DashboardTile.CLIPBOARD -> { panel = null; dashboardClips = true; dashboardOpen = true }
            DashboardTile.EDIT_TEXT -> { dashboardOpen = false; panel = KeyboardPanel.EDIT_TEXT }
            DashboardTile.LANGUAGE -> { dashboardOpen = false; panel = KeyboardPanel.LANGUAGES }
            DashboardTile.CHAT_ASSIST -> {
                dashboardOpen = false
                if (chatAllowed) {
                    panel = null
                    if (canStartChat) startChatAssist() else chatOpen = true
                }
            }
            DashboardTile.APPEARANCE -> onAppearance()
            DashboardTile.TRANSLATE -> {
                if (editorContext.isNumber) return
                dashboardOpen = false
                panel = null
                numberPadOpen = false
                if (keyboardState.layer != KeyboardLayer.LETTERS) onToggleSymbols()
                showTranslation = true
            }
            DashboardTile.GIFS, DashboardTile.STICKERS -> {
                dashboardOpen = false
                mediaSearch = false
                mediaTab = if (tile == DashboardTile.GIFS) KeyboardMediaTab.GIFS else KeyboardMediaTab.STICKERS
                if (keyboardState.layer != KeyboardLayer.EMOJI) onToggleEmoji()
            }
            DashboardTile.UNDO -> onEditorCommand(EditorCommand.UNDO)
            DashboardTile.REDO -> onEditorCommand(EditorCommand.REDO)
            DashboardTile.AUTOFILL -> {
                dashboardOpen = false
                closeTranslation()
                onEditorCommand(EditorCommand.AUTOFILL)
            }
            else -> keyboardScope.launch { toggleDashboardSetting(tile, settings) }
        }
    }
    LaunchedEffect(input, ai.inlineBusy, isShortcutsOpen, isVoxOpen, isAiActive) {
        userPinned = false
    }
    LaunchedEffect(privateMode, editorContext.isRestrictedField) {
        if (privateMode || editorContext.isRestrictedField) {
            mediaSearch = false
            mediaQuery = ""
            submittedMediaQuery = ""
            closeTranslation()
            if (mediaTab.online) mediaTab = KeyboardMediaTab.EMOJI
        }
    }
    LaunchedEffect(isShortcutsOpen, isVoxOpen, isAiActive) {
        if (isShortcutsOpen || isVoxOpen || isAiActive) {
            dashboardOpen = false
            placementMenu = false
            panel = null
            closeTranslation()
        }
    }
    val overlayHeight = (configuration.screenHeightDp.dp - with(density) { keyboardHeightPx.toDp() })
        .coerceAtLeast(100.dp)

    val activePlacement = if (isVoxOpen || isAiActive || isShortcutsOpen ||
        showTranslation && placement == KeyboardPlacement.FLOATING) KeyboardPlacement.FULL else placement
    val mediaBrowsing = mediaWorkspace && !searchingMedia
    // Leave room between the picker controls and the system keyboard controls.
    val mediaBottomPadding = if (activePlacement == KeyboardPlacement.FLOATING) 6.dp else 20.dp
    val mediaMaxHeight = if (mediaBrowsing) (if (mediaTab.online) 86.dp else 58.dp) + mediaBottomPadding + 3.dp else 0.dp
    val mediaHeightLimit = configuration.screenHeightDp.dp * .63f - 49.dp
    val extraNumberHeight = if (numberRow && keyboardState.layer == KeyboardLayer.LETTERS && !numberPadOpen) .69f else 0f
    KeyboardPosition(activePlacement, preferredRowHeight * (4f + extraNumberHeight) + 24.dp + mediaMaxHeight, onFloatingBounds,
        onDock = { keyboardScope.launch { settings.setKeyboardPlacement(KeyboardPlacement.FULL) } }
    ) { positionModifier, scale, placementControls ->
    val rowHeight = (preferredRowHeight * scale).coerceAtLeast(30.dp)
    val standardBodyHeight = rowHeight * (4f + extraNumberHeight) + 24.dp
    val bodyHeight = standardBodyHeight + (mediaHeightLimit - standardBodyHeight).coerceIn(0.dp, mediaMaxHeight)
    val displayedLayer = if (searchingMedia) mediaSearchLayer else keyboardState.layer
    val displayedEditor = if (searchingMedia) editorContext.copy(action = KeyboardAction.SEARCH) else editorContext
    val typeCharacter: (String) -> Unit = if (searchingMedia) ::typeMediaQuery else ({ userPinned = false; onCharTyped(it) })
    val backspaceAction: () -> Unit = if (searchingMedia) ({
        val end = Character.offsetByCodePoints(mediaQuery, mediaQuery.length, if (mediaQuery.isEmpty()) 0 else -1)
        mediaQuery = mediaQuery.take(end)
    }) else ({ userPinned = false; onBackspace() })
    val spaceAction: () -> Unit = if (searchingMedia) ({ typeMediaQuery(" ") }) else ({ userPinned = false; onSpace() })
    val enterAction: () -> Unit = if (searchingMedia) ::finishMediaSearch else ({ userPinned = false; onAction() })
    val symbolAction: () -> Unit = if (searchingMedia) ({
        mediaSearchLayer = if (mediaSearchLayer == KeyboardLayer.LETTERS) KeyboardLayer.SYMBOLS else KeyboardLayer.LETTERS
    }) else onToggleSymbols
    KeyPreviewHost(!dashboardOpen && !isVoxOpen && !placementMenu && panel != KeyboardPanel.EDIT_TEXT) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontPercent / 100f),
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.let {
            if (font == null) it else it.copy(fontFamily = font)
        },
        LocalKeySound provides playSound,
        LocalGlideDelete provides onDeleteWords.takeIf {
            KeyboardOption.GLIDE_DELETE in options && !editorContext.isRestrictedField &&
                !isAiActive && !isShortcutsOpen && !isVoxOpen && !mediaWorkspace && !showTranslation
        },
        LocalKeyAppearance provides KeyAppearance(keyBorders, keyPopups, keyPopupMs, vibrationHaptics, keyLongPressMs, swipeTrail, compactKeys),
        LocalVibrationHaptics provides vibrationHaptics,
        LocalCursorKeys provides CursorKeys(cursorDrag.takeIf { cursorOnSpace && !searchingMedia }, cursorActive && !searchingMedia, cursorCharacter),
        LocalActiveKeyboardLanguage provides language,
        LocalKeyOptions provides options,
        LocalKeyLanguageMenu provides (if (searchingMedia) null else ({
            panel = KeyboardPanel.LANGUAGES
            dashboardOpen = false
        })),
        LocalKeyboardLayoutMenu provides (if (searchingMedia) null else ({
            placementMenu = !placementMenu
            panel = null
            dashboardOpen = false
        })),
        LocalSplitKeys provides (activePlacement == KeyboardPlacement.SPLIT)
    ) {
    Column(positionModifier.then(modifier).fillMaxWidth()
        .then(if (isVoxOpen) Modifier else Modifier.monologBackdrop(keyboard = true))
        .then(if (activePlacement == KeyboardPlacement.FLOATING) Modifier else Modifier.navigationBarsPadding())
        .onSizeChanged { keyboardHeightPx = it.height }) {
        placementControls()
        // If Vox mode is open, render the full Vox Panel
        if (isVoxOpen) {
            VoxPanel(
                voxState = voxState,
                language = voxLanguageLabel,
                languages = voxLanguages,
                onLanguage = onVoxLanguage,
                onStartListening = onStartListening,
                onPauseListening = onPauseListening,
                onDeleteWord = onDeleteVoxWord,
                smartFilter = smartFilter,
                onToggleSmartFilter = { keyboardScope.launch { settings.setSmartVoxFilter(!smartFilter) } },
                contextEnabled = !privateMode,
                contextActive = ScreenCaptureService.isReady,
                onContext = onCaptureContext,
                onContextHold = onToggleShortcuts,
                onCancel = onExitVox,
                onRequestPermission = onRequestPermission,
                onMiniBounds = onFloatingBounds,
                onOpenModels = onOpenVoiceSettings
            )
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

            if (aiAllowed && !isShortcutsOpen && searchQuery == null && !mediaWorkspace) {
                KeyboardAiControls(ai, language)
            }

            if (chatOpen && chatAllowed && searchQuery == null && !isShortcutsOpen && !mediaWorkspace) {
                ChatAssistPanel(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), chatHere, canStartChat,
                    if (chatSession != null && chatHere == null) "Chat Assist is sharing a different app." else chatError,
                    onStart = ::startChatAssist,
                    onPause = ChatAssist::pause, onStop = {
                        ChatAssist.stop()
                        context.stopService(Intent(context, ChatAssistService::class.java))
                        chatOpen = false
                    }, onClose = { chatOpen = false }, onInsert = { text ->
                        val active = ChatAssist.session.value
                        if (text.isNotBlank() && active != null && !active.busy && !active.paused &&
                            active.target == editorContext.packageName && ChatAssist.editor == active.target && active.reply == text) {
                            onPasteText(text)
                            chatOpen = false
                        }
                    }
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (mediaWorkspace) {
                    val stickers = mediaTab == KeyboardMediaTab.STICKERS
                    val recentSearches = if (mediaTab.online && canSearchMedia) androidx.compose.runtime.key(mediaTab) {
                        val history = remember(settings) { settings.recentMediaSearches(stickers) }
                        history.collectAsState(emptyList()).value
                    } else emptyList()
                    KeyboardMediaSearchBar(
                        tab = mediaTab,
                        query = if (searchingMedia) mediaQuery else submittedMediaQuery,
                        editing = searchingMedia,
                        enabled = canSearchMedia,
                        recent = recentSearches,
                        onSearch = ::editMediaSearch,
                        onClear = { if (searchingMedia) mediaQuery = "" else showMediaResults("") },
                        onCancel = { showMediaResults(submittedMediaQuery) },
                        onSubmit = ::finishMediaSearch,
                        onRepeatSearch = ::submitMediaSearch,
                        onClearHistory = { keyboardScope.launch { settings.clearRecentMediaSearches(stickers) } }
                    )
                } else KeyboardHeader(
                    editorContext = editorContext,
                    onToggleShortcuts = onToggleShortcuts,
                    onCaptureContext = onCaptureContext,
                    onToggleDashboard = {
                        chatOpen = false
                        dashboardOpen = !dashboardOpen
                        dashboardClips = false
                        userPinned = false
                        placementMenu = false
                        closeTranslation()
                        panel = null
                    },
                    onHoldDashboard = if (pinnedTiles.isEmpty() || dashboardOpen || searchQuery != null || isShortcutsOpen) null
                        else ({ userPinned = !userPinned }),
                    dashboardChevron = pinnedTiles.isNotEmpty() && (hasInput || userPinned),
                    dashboardOpen = dashboardOpen,
                    onModeAction = if (swapVoxAndAi) onOpenSearch else onEnterVox,
                    toolbarAi = swapVoxAndAi,
                    isContextActive = !editorContext.isIncognito && (isShortcutsOpen || ScreenCaptureService.isReady),
                    leading = {
                        if (chatHere != null) ChatAssistToolbarButton(chatHere) { chatOpen = !chatOpen }
                    },
                    suggestions = {
                        if (!dashboardOpen && !isShortcutsOpen && !showTranslation && searchQuery == null) {
                            val localActions = ai.inlineActions.ifEmpty { if (!hasInput) copiedActions else emptyList() }
                            val match = emojiMatches
                            val showWords = KeyboardOption.WORD_STRIP in options
                            val showPolish = KeyboardOption.GRAMMAR in options
                            val predictionsVisible = aiAllowed && (showWords && ai.suggestions.isNotEmpty() || ai.shortcutSuggestion != null)
                            val mediaVisible = KeyboardOption.EMOJI in options &&
                                showSymbolTools && !hasInput && !editorContext.isRestrictedField
                            val mediaShortcut = when {
                                !mediaAllowed || !mediaOptIn || !KeyboardMedia.configured -> null
                                KeyboardOption.GIF in options && shortcode.equals(":gif", true) -> KeyboardMediaTab.GIFS
                                KeyboardOption.STICKER in options && shortcode.equals(":sticker", true) -> KeyboardMediaTab.STICKERS
                                else -> null
                            }
                            val idle = !ai.inlineBusy && ai.toolbar == null && !ai.rewriteOpen &&
                                localActions.isEmpty() && !predictionsVisible && !mediaVisible && match?.entries.isNullOrEmpty()
                            when {
                                systemAutofillSuggestions.isNotEmpty() ->
                                    SystemAutofillStrip(systemAutofillSuggestions)
                                rescueHint && rescueAllowed && !hasInput && panel == null ->
                                    DraftRescueButton(::openRescue)
                                userPinned || (idle && !hasInput && shortcode == null && clipboardText == null && pinnedTiles.isNotEmpty()) ->
                                    PinnedKeyboardWidgets(pinnedTiles, if (userPinned) ({ userPinned = false }) else null, ::activate)
                                aiAllowed && ai.dictionaryActionsAllowed &&
                                    (ai.selectedDictionaryWord != null || ai.suggestionToRemove != null) ->
                                    KeyboardSuggestionStrip(ai, language, onSuggestedShortcut, showWords = showWords, showPolish = showPolish)
                                mediaShortcut != null -> androidx.compose.material3.TextButton(onClick = {
                                    activate(if (mediaShortcut == KeyboardMediaTab.GIFS) DashboardTile.GIFS else DashboardTile.STICKERS)
                                }) { Text(if (mediaShortcut == KeyboardMediaTab.GIFS) "Find GIFs" else "Find stickers") }
                                shortcode != null -> EmojiShortcutStrip(
                                    if (privateMode) emptyList() else recentEmoji,
                                    if (shortcode == ":") null else match?.entries.orEmpty()
                                ) { emoji -> replaceEmoji(shortcode, emoji) }
                                aiAllowed && (ai.shortcutSuggestion != null || ai.inlineBusy || ai.toolbar != null || ai.rewriteOpen) ->
                                    KeyboardSuggestionStrip(ai, language, onSuggestedShortcut, copiedActions,
                                        showWords = showWords, showPolish = showPolish)
                                localActions.isNotEmpty() -> Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                                    DeviceActionButtons(localActions)
                                }
                                mediaVisible ->
                                    EmojiShortcutStrip(if (privateMode) emptyList() else recentEmoji, onPick = ::pickEmoji)
                                idle && !hasInput && clipboardText != null ->
                                    androidx.compose.material3.TextButton(onClick = { onPasteText(clipboardText) }) {
                                        Text("Paste " + clipboardText.orEmpty().lineSequence().first().take(24), maxLines = 1)
                                    }
                                aiAllowed -> KeyboardSuggestionStrip(ai, language, onSuggestedShortcut,
                                    copiedActions.takeUnless { hasInput }.orEmpty(),
                                    emojis = match?.entries.orEmpty(),
                                    onEmoji = { if (match != null) replaceEmoji(match.typed, it) },
                                    showWords = showWords, showPolish = showPolish)
                            }
                        }
                    }
                )
                rescuePreview?.takeIf { rescueAllowed && panel == KeyboardPanel.DRAFT_RESCUE }?.let { draft ->
                    Popup(AboveKeyboardHeader, onDismissRequest = { rescuePreview = null },
                        properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                        DraftRescuePreview(draft, Modifier.width(maxWidth).height(overlayHeight)) { rescuePreview = null }
                    }
                }
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

            if (showTranslation) KeyboardTranslationBar(translation, ::closeTranslation)

            Box(Modifier.fillMaxWidth().height(bodyHeight)) {
                Crossfade(dashboardOpen, modifier = Modifier.fillMaxSize(), animationSpec = tween(105),
                    label = "Keyboard dashboard") { showDashboard ->
                    val workspace = panel
                    if (showDashboard) {
                        KeyboardDashboard(
                            settings = settings,
                            privateMode = privateMode,
                            clips = clips,
                            images = images,
                            onPasteImage = { if (onPasteImage(it)) dashboardOpen = false },
                            rowHeight = rowHeight,
                            tiles = tiles,
                            toolbarPins = toolbarPins,
                            restricted = editorContext.isRestrictedField,
                            showClips = dashboardClips,
                            onShowClips = { dashboardClips = it },
                            onActivate = ::activate,
                            onPaste = { value -> onPasteText(value); dashboardOpen = false },
                            onRescue = if (rescueAllowed) ::openRescue else null,
                            rescueBadge = rescueBadge
                        )
                    } else if (placementMenu) {
                        KeyboardPlacementPicker(placement, onClose = { placementMenu = false }) {
                            keyboardScope.launch { settings.setKeyboardPlacement(it); placementMenu = false }
                        }
                    } else if (workspace != null) {
                        fun closePanel() { panel = null }
                        when (workspace) {
                            KeyboardPanel.DRAFT_RESCUE -> if (rescueAllowed) {
                                DraftRescuePanel(settings, ::closePanel, { rescuePreview = it }) { value ->
                                    onPasteText(value)
                                    closePanel()
                                }
                            }
                            KeyboardPanel.EDIT_TEXT -> KeyboardEditorTools(onEditorCommand, ::closePanel)
                            KeyboardPanel.LANGUAGES -> KeyboardLanguagePicker(language, {
                                keyboardScope.launch { settings.setKeyboardLanguage(it.id); closePanel() }
                            }, ::closePanel)
                        }
                    } else Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 3.dp, end = 3.dp, top = 4.dp,
                                bottom = if (mediaBrowsing) mediaBottomPadding else 16.dp)
                    ) {
                        when (displayedLayer) {
                            KeyboardLayer.LETTERS -> LettersLayout(
                                rowHeight = rowHeight,
                                shiftState = if (searchingMedia) mediaShift else keyboardState.shiftState,
                                editorContext = displayedEditor,
                                language = language,
                                onCharTyped = typeCharacter,
                                onLetterTyped = if (searchingMedia) ({ letter ->
                                    typeMediaQuery(if (mediaShift.isUppercase) letter.uppercase() else letter.lowercase())
                                    if (mediaShift == ShiftState.ONCE) mediaShift = ShiftState.OFF
                                }) else ({ userPinned = false; onLetterTyped(it) }),
                                swipeEnabled = !searchingMedia && swipeAllowed && !showTranslation &&
                                    language.supportsLatinSwipe && (!isShortcutsOpen || shortcutDraft.isEditing),
                                longPressCharacters = longPressCharacters,
                                onSwipe = { userPinned = false; onSwipe(it) },
                                onShiftClick = if (searchingMedia) ({
                                    mediaShift = if (mediaShift == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
                                }) else onShiftClick,
                                onCapsLock = if (searchingMedia) ({ mediaShift = ShiftState.CAPS_LOCK }) else onCapsLock,
                                onBackspace = backspaceAction,
                                onToggleSymbols = symbolAction,
                                onToggleEmoji = if (searchingMedia) ::finishMediaSearch else onToggleEmoji,
                                onSpace = spaceAction,
                                onHoldSpace = if (searchingMedia) ({}) else onHoldSpace,
                                spacebarVox = swapVoxAndAi,
                                isAiActive = isAiActive,
                                onToggleIncognito = onToggleIncognito,
                                onAction = enterAction
                            )

                            KeyboardLayer.SYMBOLS, KeyboardLayer.MORE_SYMBOLS -> SymbolsLayout(
                                moreSymbols = displayedLayer == KeyboardLayer.MORE_SYMBOLS,
                                rowHeight = rowHeight,
                                numberPadOpen = if (searchingMedia) mediaNumberPad else numberPadOpen,
                                onNumberPad = { if (searchingMedia) mediaNumberPad = !mediaNumberPad else numberPadOpen = !numberPadOpen },
                                longPressCharacters = longPressCharacters,
                                editorContext = displayedEditor,
                                onCharTyped = typeCharacter,
                                onToggleMoreSymbols = if (searchingMedia) ({
                                    mediaSearchLayer = if (displayedLayer == KeyboardLayer.SYMBOLS) {
                                        KeyboardLayer.MORE_SYMBOLS
                                    } else KeyboardLayer.SYMBOLS
                                }) else onToggleMoreSymbols,
                                onBackspace = backspaceAction,
                                onToggleSymbols = symbolAction,
                                onToggleEmoji = if (searchingMedia) ::finishMediaSearch else onToggleEmoji,
                                onSpace = spaceAction,
                                onHoldSpace = if (searchingMedia) ({}) else onHoldSpace,
                                spacebarVox = swapVoxAndAi,
                                isAiActive = isAiActive,
                                onToggleIncognito = onToggleIncognito,
                                onAction = enterAction
                            )

                            KeyboardLayer.EMOJI -> KeyboardMediaPicker(
                                tab = mediaTab,
                                onTab = { mediaTab = it; mediaSearch = false },
                                mediaAllowed = mediaAllowed,
                                mediaOptIn = mediaOptIn,
                                query = submittedMediaQuery,
                                onClearRecentEmoji = if (privateMode) null else ({ keyboardScope.launch { settings.clearRecentEmoji() } }),
                                onSearch = ::showMediaResults,
                                onEnable = { keyboardScope.launch { settings.setOnlineMedia(true) } },
                                onMedia = onPasteMedia,
                                recent = if (privateMode) emptyList() else recentEmoji,
                                onEmoji = ::pickEmoji,
                                onText = typeCharacter,
                                onBackspace = backspaceAction,
                                onLetters = { mediaSearch = false; onToggleEmoji() }
                            )
                        }
                    }
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

private val LocalKeyOptions = androidx.compose.runtime.staticCompositionLocalOf { KeyboardOption.entries.toSet() }
private val LocalKeyLanguageMenu = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

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
    val split = LocalSplitKeys.current
    val nativeKeys = remember(language) { language.nativeKeys() }
    var page by remember(language) { mutableIntStateOf(0) }
    val options = LocalKeyOptions.current
    val numbers = if (editorContext.isPassword) KeyboardOption.PASSWORD_NUMBERS in options else KeyboardOption.NUMBERS in options
    if (numbers) {
        Row(Modifier.fillMaxWidth().height(rowHeight * .69f)) {
            for (digit in "1234567890") {
                if (split && digit == '6') Spacer(Modifier.width(SplitKeyboardGap))
                KeyButton(Modifier.weight(1f), text = digit.toString(), fontSize = 16.sp,
                    onClick = { onCharTyped(digit.toString()) })
            }
        }
    }

    if (nativeKeys.isNotEmpty()) {
        val pages = remember(nativeKeys) { nativeKeys.chunked(24) }
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                repeat(8) { column ->
                    if (split && column == 4) Spacer(Modifier.width(SplitKeyboardGap))
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
        Column(Modifier.fillMaxWidth().then(swipeTypingModifier(swipeEnabled, rows, split = split, onSwipe = onSwipe))) {
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                rows[0].forEachIndexed { index, letter ->
                    if (split && index == (rows[0].length + 1) / 2) Spacer(Modifier.width(SplitKeyboardGap))
                    LetterKey(Modifier.weight(1f), letter, isUpper, longPressCharacters,
                        language.alternateLetters[letter], "1234567890"[index], typeLetterOrSymbol)
                }
            }
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                rows[1].forEachIndexed { index, letter ->
                    if (split && index == (rows[1].length + 1) / 2) Spacer(Modifier.width(SplitKeyboardGap))
                    val edge = index == 0 || index == rows[1].lastIndex
                    LetterKey(Modifier.weight(if (edge) 1 + homeInset else 1f), letter, isUpper, longPressCharacters,
                        language.alternateLetters[letter], "@#\u00a3_&-+()"[index], typeLetterOrSymbol,
                        faceStart = if (index == 0) 7.dp * homeInset else 0.dp,
                        faceEnd = if (index == rows[1].lastIndex) 7.dp * homeInset else 0.dp)
                }
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
                    if (split && index == (rows[2].length + 1) / 2) Spacer(Modifier.width(SplitKeyboardGap))
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
internal fun KeyboardThemePreview(settings: SettingsStore, borders: Boolean) {
    val languageId by settings.keyboardLanguage.collectAsState("en")
    val allowHold by settings.longPressCharacters.collectAsState(true)
    val cursor by settings.cursorOnSpace.collectAsState(false)
    val swapped by settings.swapVoxAndAi.collectAsState(false)
    val height by settings.keyHeightPercent.collectAsState(100)
    val options by settings.keyboardOptions.collectAsState(KeyboardOption.entries.toSet())
    val typeface by settings.keyboardTypeface.collectAsState(KeyboardTypeface.MONOLOG)
    val fontPercent by settings.keyboardFontSize.collectAsState(100)
    val density = LocalDensity.current
    val font = remember(typeface) { when (typeface) {
        KeyboardTypeface.MONOLOG -> null
        KeyboardTypeface.GEIST -> FontFamily(Font(R.font.geist))
        KeyboardTypeface.SYSTEM -> FontFamily.Default
    } }
    val rowHeight = (43.dp * (height / 100f)).coerceIn(30.dp, 53.dp)
    val previewCursor = remember { CursorDrag({}, {}, {}) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPreviewOnly provides true,
        LocalKeyOptions provides options,
        LocalActiveKeyboardLanguage provides KeyboardLanguage.of(languageId),
        LocalDensity provides Density(density.density, density.fontScale * fontPercent / 100f),
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.let {
            if (font == null) it else it.copy(fontFamily = font)
        },
        LocalKeyAppearance provides KeyAppearance(borders = borders),
        LocalCursorKeys provides CursorKeys(previewCursor.takeIf { cursor })
    ) {
        Column(Modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .monologBackdrop(keyboard = true).padding(3.dp)
            .clearAndSetSemantics { contentDescription = "Read-only keyboard theme preview" }) {
            LettersLayout(rowHeight = rowHeight, shiftState = ShiftState.OFF,
                editorContext = EditorContext.default(), language = KeyboardLanguage.of(languageId),
                onCharTyped = {}, onLetterTyped = {}, swipeEnabled = false, longPressCharacters = allowHold,
                onSwipe = {}, onShiftClick = {}, onCapsLock = {}, onBackspace = {}, onToggleSymbols = {},
                onToggleEmoji = {}, onSpace = {}, onHoldSpace = {}, spacebarVox = swapped,
                isAiActive = false, onToggleIncognito = {}, onAction = {})
        }
    }
}

@Composable
private fun LetterKey(
    modifier: Modifier,
    letter: Char,
    uppercase: Boolean,
    longPressCharacters: Boolean,
    accents: String?,
    alternate: Char,
    onType: (String) -> Unit,
    faceStart: Dp = 0.dp,
    faceEnd: Dp = 0.dp
) {
    fun display(value: Char) = if (!uppercase) value else if (value == 'ß') 'ẞ' else value.uppercaseChar()
    val normal = display(letter).toString()
    val options = remember(accents, uppercase, alternate, longPressCharacters) {
        if (longPressCharacters) accents.orEmpty().map { display(it).toString() } + alternate.toString()
        else emptyList()
    }
    KeyButton(modifier, text = normal, subText = options.firstOrNull(),
        faceStart = faceStart, faceEnd = faceEnd,
        alternates = options, onAlternate = onType, onClick = { onType(normal) })
}

@Composable
private fun SymbolsLayout(
    rowHeight: Dp,
    moreSymbols: Boolean,
    numberPadOpen: Boolean,
    onNumberPad: () -> Unit,
    longPressCharacters: Boolean,
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

    val canHold = longPressCharacters && !editorContext.isRestrictedField
    if (numberPadOpen) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            DigitsGrid(rowHeight, canHold, editorContext, onCharTyped, onBackspace, onNumberPad,
                onToggleSymbols, onSpace, onAction)
        }
        return
    }
    // Row 1
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row1.forEach { char ->
            SymbolKey(Modifier.weight(1f), char, canHold, onCharTyped)
        }
    }

    // Row 2
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        row2.forEach { char ->
            SymbolKey(Modifier.weight(1f), char, canHold, onCharTyped)
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
            SymbolKey(Modifier.weight(1f), char, canHold, onCharTyped)
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
        onNumberPad = onNumberPad,
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
    canHold: Boolean,
    editorContext: EditorContext,
    onCharTyped: (String) -> Unit,
    onBackspace: () -> Unit,
    onSymbols: () -> Unit,
    onLetters: () -> Unit,
    onSpace: () -> Unit,
    onAction: () -> Unit
) {
    val digitRows = listOf("123", "456", "789")
    val t9 = listOf("", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
    val left = listOf("+", "-", ".")
    val right = listOf("/", "⌫", "=")
    digitRows.forEachIndexed { row, digits ->
        Row(Modifier.fillMaxWidth().height(rowHeight)) {
            SymbolKey(Modifier.weight(.8f), left[row], canHold, onCharTyped)
            digits.forEach { digit ->
                val value = digit.toString()
                val letters = if (canHold && !editorContext.isNumber) t9[digit.digitToInt()] else ""
                val options = if (canHold) letters.map(Char::toString) + symbolAlternates[value].orEmpty()
                    else emptyList()
                KeyButton(Modifier.weight(1.15f), previewText = value,
                    label = listOf(value, letters).filter(String::isNotEmpty).joinToString(" "),
                    alternates = options, onAlternate = onCharTyped, onClick = { onCharTyped(value) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, fontSize = 22.sp, lineHeight = 22.sp, color = PaperKeyText)
                        if (letters.isNotEmpty()) Text(letters, fontSize = 9.sp, lineHeight = 10.sp,
                            color = com.notokens.monolog.ui.theme.PaperKeySubText, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (row == 1) {
                KeyButton(Modifier.weight(.8f), glyph = Glyph.BACKSPACE, label = "Backspace", onClick = onBackspace)
            } else {
                SymbolKey(Modifier.weight(.8f), right[row], canHold, onCharTyped)
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(rowHeight + 4.dp)) {
        KeyButton(Modifier.weight(.8f), text = "?123", fontSize = 14.sp, label = "Return to symbols", onClick = onSymbols)
        KeyButton(Modifier.weight(1.15f), text = "ABC", fontSize = 14.sp, label = "Return to letters", onClick = onLetters)
        SymbolKey(Modifier.weight(1.15f), "0", canHold, onCharTyped)
        KeyButton(Modifier.weight(1.15f), text = "␣", fontSize = 19.sp, label = "Space", onClick = onSpace)
        KeyButton(Modifier.weight(.8f), glyph = when (editorContext.action) {
            KeyboardAction.SEARCH -> Glyph.SEARCH
            KeyboardAction.GO, KeyboardAction.SEND, KeyboardAction.NEXT -> Glyph.ARROW
            else -> Glyph.ENTER
        }, onClick = onAction)
    }
}

private val symbolAlternates = mapOf(
    "1" to listOf("¹", "½", "⅓", "¼"), "2" to listOf("²", "⅔"), "3" to listOf("³", "¾"),
    "4" to listOf("⁴"), "5" to listOf("⁵"), "6" to listOf("⁶"), "7" to listOf("⁷"),
    "8" to listOf("⁸"), "9" to listOf("⁹"), "0" to listOf("+", "⁰", "°"),
    "+" to listOf("±", "⊕"), "-" to listOf("−", "–", "—", "±"),
    "/" to listOf("÷", "\\"), "*" to listOf("×", "·", "★"), "=" to listOf("≠", "≈", "≡"),
    "÷" to listOf("/", "⁄"), "×" to listOf("*", "·"), "√" to listOf("∛", "∜"),
    "%" to listOf("‰", "‱"), "<" to listOf("≤", "«"), ">" to listOf("≥", "»"),
    "(" to listOf("[", "{", "<"), ")" to listOf("]", "}", ">"),
    "[" to listOf("(", "{", "«"), "]" to listOf(")", "}", "»"),
    "{" to listOf("[", "("), "}" to listOf("]", ")"),
    "?" to listOf("¿", "‽"), "!" to listOf("¡", "‽"), ":" to listOf(";", "∶"),
    "'" to listOf("’", "‘", "′"), "\"" to listOf("“", "”", "″", "«", "»"),
    "$" to listOf("₹", "€", "£", "¥", "¢"), "£" to listOf("₹", "€", "$"),
    "€" to listOf("₹", "£", "$"), "¥" to listOf("₹", "₩", "$"), "¢" to listOf("₹", "$"),
    "@" to listOf("©", "®", "™"), "#" to listOf("№", "♪"), "&" to listOf("§", "¶"),
    "." to listOf("…", "·"), "_" to listOf("-", "‒"), "~" to listOf("≈", "≃"),
    "°" to listOf("℃", "℉"), "|" to listOf("¦", "‖"), "^" to listOf("↑", "↓"),
    "`" to listOf("‘", "’", "′"), "\\" to listOf("/", "⧵"),
    "π" to listOf("τ", "φ"), "∆" to listOf("∇", "δ"), "¶" to listOf("§", "†"),
    "•" to listOf("◦", "·"), "©" to listOf("®", "℗"), "®" to listOf("©", "™"),
    "™" to listOf("℠", "®"), ";" to listOf(":", ";")
)

@Composable
private fun SymbolKey(modifier: Modifier, symbol: String, canHold: Boolean, onType: (String) -> Unit) {
    val options = if (canHold) symbolAlternates[symbol].orEmpty() else emptyList()
    KeyButton(modifier, text = symbol, subText = options.firstOrNull(),
        alternates = options, onAlternate = onType, onClick = { onType(symbol) })
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
    onNumberPad: (() -> Unit)? = null
) {
    val language = LocalActiveKeyboardLanguage.current
    val options = LocalKeyOptions.current
    val languageMenu = LocalKeyLanguageMenu.current
    val layoutMenu = LocalKeyboardLayoutMenu.current.takeIf { modeLabel == "?123" }
    val split = LocalSplitKeys.current
    val cursorDrag = LocalCursorKeys.current.drag
    val smartHold = onHoldSpace.takeUnless { editorContext.isRestrictedField || editorContext.isNumber }
    val smartLabel = when {
        spacebarVox -> "Open Vox"
        isAiActive -> "Close Monolog AI"
        else -> "Open Monolog AI search"
    }
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight + 4.dp)) {
        KeyButton(Modifier.weight(1.4f), text = modeLabel,
            subText = if (layoutMenu != null) "⋯" else null,
            fontSize = 16.sp, onClick = onModeClick,
            longClickLabel = if (layoutMenu != null) "Keyboard layout options" else null,
            onLongClick = layoutMenu)
        if (KeyboardOption.COMMA in options) KeyButton(
            Modifier.weight(1.15f),
            label = "Comma",
            previewText = ",",
            longClickLabel = "Open emoji picker or switch language",
            alternates = if (languageMenu != null) listOf("☺", "🌐") else emptyList(),
            onAlternate = { symbol ->
                when (symbol) {
                    "☺" -> onEmojiClick()
                    "🌐" -> languageMenu?.invoke()
                }
            },
            onLongClick = if (languageMenu == null) onEmojiClick else null,
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
                label = "Open number pad",
                onClick = onNumberPad
            ) {
                Text("1 2\n3 4", color = PaperKeyText, fontSize = 10.sp,
                    lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (split) Spacer(Modifier.width(13.dp))
        KeyButton(
            Modifier.weight(if (onNumberPad != null) 3.9f else 4.9f),
            isPill = true,
            isActive = isAiActive,
            glow = false,
            tracing = isAiActive,
            label = if (isAiActive) "Space, Monolog AI active" else "Space",
            longClickLabel = if (cursorDrag != null) "Hold and drag to move the cursor" else smartLabel,
            onLongClick = smartHold.takeIf { cursorDrag == null },
            cursorDrag = cursorDrag,
            onClick = onSpace
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Text(if (isAiActive) "AI Ask" else language.label.substringBefore('·').trim(),
                    color = if (isAiActive) androidx.compose.material3.MaterialTheme.colorScheme.primary else PaperKeyText,
                    fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!editorContext.isIncognito && cursorDrag == null) MonologIcon(if (spacebarVox) Glyph.MIC else Glyph.BOLT, Modifier.size(17.dp))
            }
        }
        if (split) Spacer(Modifier.width(13.dp))
        if (KeyboardOption.PERIOD in options) KeyButton(
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
        KeyButton(
            Modifier.weight(1.4f),
            glyph = if (isAiActive) Glyph.ARROW else when (editorContext.action) {
                KeyboardAction.SEARCH -> Glyph.SEARCH
                KeyboardAction.GO, KeyboardAction.SEND, KeyboardAction.NEXT -> Glyph.ARROW
                else -> Glyph.ENTER
            },
            secondaryGlyph = if (cursorDrag != null && smartHold != null) {
                if (spacebarVox) Glyph.MIC else Glyph.BOLT
            } else null,
            longClickLabel = smartLabel.takeIf { cursorDrag != null && smartHold != null },
            onLongClick = smartHold.takeIf { cursorDrag != null },
            onClick = onAction
        )
    }
}
