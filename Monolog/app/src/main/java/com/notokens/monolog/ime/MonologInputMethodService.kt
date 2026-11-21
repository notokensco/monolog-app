package com.notokens.monolog.ime

import android.content.Intent
import android.icu.text.BreakIterator
import android.view.KeyEvent
import android.view.Display
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.WindowCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.LocalAi
import com.notokens.monolog.capture.CaptureSetupActivity
import com.notokens.monolog.capture.ScreenCaptureService
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.ThemeMode
import com.notokens.monolog.ime.ui.MonologKeyboardView
import com.notokens.monolog.ime.ui.preloadEmojiCatalog
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestion
import com.notokens.monolog.ime.vox.VoxController
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.MonologTheme
import com.notokens.monolog.ui.theme.LocalMonologDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary InputMethodService for Monolog.
 * Orchestrates user typing with InputConnection, manages shift/symbol/emoji layouts,
 * hosts the Quick Shortcuts (M-key) panel and Vox Voice typing mode,
 * and strictly isolates sensitive/password fields to guarantee zero data collection.
 */
class MonologInputMethodService : LifecycleInputMethodService() {

    private var editorContext by mutableStateOf(EditorContext.default())
    private var keyboardState by mutableStateOf(KeyboardState())
    private var isShortcutsOpen by mutableStateOf(false)
    private var shortcutDraft by mutableStateOf(ShortcutDraft())
    private var isVoxOpen by mutableStateOf(false)
    private var voxState by mutableStateOf(VoxState())
    private var searchQuery by mutableStateOf<TextFieldValue?>(null)
    private var pendingCaptureDisplay: Int? = null
    private var captureAfterHide: Job? = null
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var userIncognito = false
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private var privacyReady by mutableStateOf(false)
    private var editorActive by mutableStateOf(false)
    private var language by mutableStateOf(KeyboardLanguage.of("en"))
    private val dictionary by lazy { EnglishSuggestions(applicationContext) }
    private var swipeJob: Job? = null
    private var selectionVersion = 0L
    private val ai by lazy {
        ImeAi(this, settingsScope, settingsStore, dictionary, { currentInputConnection }, ::aiAllowed)
    }

    private fun aiAllowed(): Boolean = privacyReady && editorActive && !editorContext.isIncognito &&
        !editorContext.isNumber && !editorContext.isEmail && !editorContext.isUri

    // Address bars are text editors too. Local gesture decoding does not need AI permission.
    private fun swipeAllowed(): Boolean = privacyReady && editorActive &&
        !editorContext.isIncognito && !editorContext.isNumber

    override fun onCreate() {
        super.onCreate()
        settingsScope.launch(Dispatchers.Default) {
            delay(350)
            preloadEmojiCatalog(assets)
            dictionary.swipeDecoder
        }
        settingsScope.launch {
            settingsStore.incognito.collect { enabled ->
                userIncognito = enabled
                editorContext = editorContext.copy(isUserIncognito = enabled)
                privacyReady = true
                if (enabled) {
                    ai.reset()
                    LocalAi.release()
                    searchQuery = null
                    pendingCaptureDisplay = null
                    captureAfterHide?.cancel()
                    isShortcutsOpen = false
                    shortcutDraft = ShortcutDraft()
                    if (isVoxOpen) handleExitVox()
                } else if (editorActive) {
                    ai.refreshDraft()
                }
            }
        }
        settingsScope.launch {
            settingsStore.keyboardLanguage.collect { language = KeyboardLanguage.of(it) }
        }
        settingsScope.launch {
            settingsStore.webSuggestions.collect { if (!it) ai.disableWebSuggestions() }
        }
        settingsScope.launch {
            settingsStore.webQuestionSuggestions.collect { if (!it) ai.disableWebSuggestions() }
        }
    }

    private val voxController by lazy {
        VoxController(this) { state ->
            voxState = state
            if (isVoxOpen && state.finalSegment.isNotBlank()) {
                ai.stableSpeech(state.finalSegment)
            }
        }
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@MonologInputMethodService)
            )
            setContent {
                val appearance by settingsStore.appearance.collectAsState(initial = Appearance())
                MonologTheme(appearance = if (editorContext.isIncognito) appearance.copy(theme = ThemeMode.INK) else appearance) {
                    val lightBars = !LocalMonologDark.current
                    SideEffect {
                        window?.window?.let { imeWindow ->
                            WindowCompat.getInsetsController(imeWindow, imeWindow.decorView)
                                .isAppearanceLightNavigationBars = lightBars
                        }
                    }
                    MonologKeyboardView(
                        editorContext = editorContext,
                        keyboardState = keyboardState,
                        voxState = voxState,
                        isShortcutsOpen = isShortcutsOpen,
                        isVoxOpen = isVoxOpen,
                        shortcutDraft = shortcutDraft,
                        onDraftChange = { shortcutDraft = it },
                        onSuggestedShortcut = ::handleSuggestedShortcut,
                        onToggleShortcuts = ::handleToggleShortcuts,
                        onCaptureContext = ::handleCaptureContext,
                        onDismissShortcuts = ::dismissShortcuts,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { ai.abortAction(); searchQuery = it },
                        onOpenSearch = ::handleOpenSearch,
                        onCloseSearch = ::handleCloseSearch,
                        ai = ai,
                        aiAllowed = aiAllowed(),
                        swipeAllowed = swipeAllowed(),
                        language = language,
                        onOpenSettings = ::handleOpenSettings,
                        onEnterVox = ::handleEnterVox,
                        onExitVox = ::handleExitVox,
                        onStartListening = { voxController.startListening(language.speechTag, language.label) },
                        onPauseListening = { voxController.pauseOrStopListening() },
                        onClearTranscript = { voxController.clearTranscript(); ai.clearDraft() },
                        onCommitTranscript = ::handleCommitTranscript,
                        onRequestPermission = ::handleRequestMicPermission,
                        onCharTyped = ::handleCharTyped,
                        onSwipe = ::handleSwipe,
                        onBackspace = ::handleBackspace,
                        onSpace = ::handleSpace,
                        onAction = ::handleAction,
                        onShiftClick = ::handleShiftClick,
                        onCapsLock = { keyboardState = keyboardState.toggleCapsLock() },
                        onToggleSymbols = ::handleToggleSymbols,
                        onToggleMoreSymbols = ::handleToggleMoreSymbols,
                        onToggleEmoji = ::handleToggleEmoji,
                        onToggleIncognito = ::handleToggleIncognito,
                        onCloseKeyboard = ::handleCloseKeyboard
                    )
                }
            }
        }

        setupViewTreeOwners(composeView)
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        swipeJob?.cancel()
        val resolved = EditorContext.fromEditorInfo(info)
        editorContext = resolved.copy(isUserIncognito = userIncognito)
        editorActive = info != null
        ai.reset(info?.takeIf { it.initialSelStart == it.initialSelEnd }?.initialSelEnd ?: -1)
        ai.refreshDraft()

        // Always close any active private panels when switching editors
        searchQuery = null
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        if (isVoxOpen) {
            handleExitVox()
        }

        // If editor is numeric, automatically show symbols/numbers layer
        keyboardState = if (resolved.isNumber) {
            KeyboardState(layer = KeyboardLayer.SYMBOLS, shiftState = ShiftState.OFF)
        } else {
            KeyboardState(layer = KeyboardLayer.LETTERS, shiftState = ShiftState.ONCE)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        pendingCaptureDisplay = null
        captureAfterHide?.cancel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        editorActive = false
        swipeJob?.cancel()
        ai.reset()
        super.onFinishInputView(finishingInput)
        voxController.cleanup()
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        searchQuery = null
        isVoxOpen = false
        editorContext = EditorContext.default().copy(isUserIncognito = userIncognito)
        keyboardState = KeyboardState()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        ai.reset()
        searchQuery = null
        if (isVoxOpen) handleExitVox()
        val displayId = pendingCaptureDisplay ?: return
        pendingCaptureDisplay = null
        captureAfterHide = settingsScope.launch {
            // Android owns the IME window. Let the host redraw after it closes.
            delay(300)
            if (!userIncognito && !ScreenCaptureService.capture(displayId)) {
                Toast.makeText(this@MonologInputMethodService, "Quick capture was turned off", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onFinishInput() {
        editorActive = false
        swipeJob?.cancel()
        ai.reset()
        pendingCaptureDisplay = null
        captureAfterHide?.cancel()
        super.onFinishInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsScope.cancel()
        voxController.cleanup()
        LocalAi.release()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) LocalAi.release()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) selectionVersion++
        ai.selectionChanged(newSelStart, newSelEnd)
    }

    private fun handleToggleShortcuts() {
        if (android.os.SystemClock.uptimeMillis() - shortcutsDismissedAt < 500) return
        if (editorContext.isIncognito) return
        searchQuery = null
        ai.closePanel()
        if (isVoxOpen) {
            handleExitVox()
        }
        isShortcutsOpen = !isShortcutsOpen
        shortcutDraft = ShortcutDraft()
    }

    private fun handleSuggestedShortcut(suggestion: ShortcutSuggestion) {
        if (!aiAllowed() || suggestion != ai.shortcutSuggestion) return
        swipeJob?.cancel()
        searchQuery = null
        ai.closePanel()
        shortcutDraft = ShortcutDraft(page = suggestion.page,
            title = TextFieldValue(suggestion.title, TextRange(suggestion.title.length)),
            body = TextFieldValue(suggestion.body, TextRange(suggestion.body.length)),
            dueAt = suggestion.dueAt)
        isShortcutsOpen = true
        keyboardState = keyboardState.switchToLetters()
    }

    private var shortcutsDismissedAt = 0L
    private fun dismissShortcuts() {
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        // An outside ACTION_DOWN can precede the M click. That same tap must never capture.
        shortcutsDismissedAt = android.os.SystemClock.uptimeMillis()
    }

    private fun handleCaptureContext() {
        if (isShortcutsOpen) { dismissShortcuts(); return }
        if (android.os.SystemClock.uptimeMillis() - shortcutsDismissedAt < 500) return
        if (!privacyReady || editorContext.isIncognito || editorContext.isNumber ||
            editorContext.isEmail || editorContext.isUri) {
            Toast.makeText(this, "Context capture disabled in incognito mode", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ScreenCaptureService.isReady) {
            CaptureSetupActivity.open(this)
            requestHideSelf(0)
            return
        }
        pendingCaptureDisplay = window?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY
        requestHideSelf(0)
    }

    private fun handleOpenSearch() {
        if (!aiAllowed()) return
        if (searchQuery != null) {
            handleCloseSearch()
            return
        }
        ai.beginSearch()
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        val selected = currentInputConnection?.getSelectedText(0)?.toString()?.trim()?.take(240).orEmpty()
        searchQuery = TextFieldValue(selected, selection = TextRange(selected.length))
        keyboardState = keyboardState.switchToLetters()
    }

    private fun handleCloseSearch() {
        searchQuery = null
        ai.closePanel()
        ai.refreshDraft()
    }

    private fun handleOpenSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SETTINGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        requestHideSelf(0)
    }

    private fun handleEnterVox() {
        if (!aiAllowed()) return
        searchQuery = null
        ai.closePanel()
        ai.clearDraft()
        voxController.clearTranscript()
        isVoxOpen = true
        voxController.startListening(language.speechTag, language.label)
    }

    private fun handleExitVox() {
        voxController.cleanup()
        isVoxOpen = false
        voxController.clearTranscript()
        ai.clearDraft()
    }

    private fun handleCommitTranscript(transcript: String) {
        if (transcript.isNotBlank()) {
            if (isShortcutsOpen && shortcutDraft.isEditing) shortcutDraft = shortcutDraft.insert(transcript)
            else currentInputConnection?.commitText(transcript, 1)
        }
        handleExitVox()
    }

    private fun handleRequestMicPermission() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REQUEST_MIC_PERMISSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun handleSwipe(points: List<SwipePoint>) {
        if (!swipeAllowed() || language.id != "en" || keyboardState.layer != KeyboardLayer.LETTERS) return
        val connection = currentInputConnection ?: return
        val query = searchQuery
        val shortcut = shortcutDraft.takeIf { isShortcutsOpen && it.isEditing }
        val state = keyboardState
        val selection = selectionVersion
        swipeJob?.cancel()
        swipeJob = settingsScope.launch {
            val word = withContext(Dispatchers.Default) { dictionary.swipeDecoder.decode(points) } ?: return@launch
            // A swipe belongs to the editor and cursor that were active when it ended.
            if (!swipeAllowed() || language.id != "en" || currentInputConnection !== connection ||
                searchQuery != query || keyboardState != state ||
                shortcut != shortcutDraft.takeIf { isShortcutsOpen && it.isEditing } ||
                isShortcutsOpen && shortcut == null ||
                query == null && shortcut == null && selectionVersion != selection) return@launch

            val previous = when {
                query != null -> query.text.getOrNull(query.selection.min - 1)
                shortcut != null -> shortcut.activeValue.let { it.text.getOrNull(it.selection.min - 1) }
                else -> connection.getTextBeforeCursor(1, 0)?.lastOrNull()
            }
            swipeJob = null
            handleCharTyped(formatSwipeWord(word, state.shiftState, previous))
        }
    }

    private fun handleCharTyped(text: String) {
        swipeJob?.cancel()
        searchQuery?.let { query ->
            ai.abortAction()
            val start = query.selection.min
            val end = query.selection.max
            val replacement = text.take((240 - query.text.length + end - start).coerceAtLeast(0))
            searchQuery = TextFieldValue(query.text.replaceRange(start, end, replacement), TextRange(start + replacement.length))
            keyboardState = keyboardState.onLetterTyped()
            return
        }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.insert(text)
            keyboardState = keyboardState.onLetterTyped()
            return
        }
        val ic = currentInputConnection ?: return
        if (ic.commitText(text, 1)) ai.textCommitted(text)
        keyboardState = keyboardState.onLetterTyped()
    }

    private fun handleBackspace() {
        swipeJob?.cancel()
        searchQuery?.let { query ->
            ai.abortAction()
            val end = query.selection.max
            var start = query.selection.min
            if (start == end && start > 0) {
                start = BreakIterator.getCharacterInstance().apply { setText(query.text) }.preceding(start).coerceAtLeast(0)
            }
            searchQuery = TextFieldValue(query.text.removeRange(start, end), TextRange(start))
            return
        }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.backspace()
            return
        }
        val ic = currentInputConnection ?: return
        ai.closePanel()
        ai.clearDraft()
        val selected = if (editorContext.isIncognito) null else ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val previous = if (editorContext.isIncognito) null else ic.getTextBeforeCursor(128, 0)?.toString()
            val deleted = if (!previous.isNullOrEmpty()) {
                val boundary = BreakIterator.getCharacterInstance().apply { setText(previous) }.preceding(previous.length)
                ic.deleteSurroundingText(previous.length - boundary.coerceAtLeast(0), 0)
            } else {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            }
            if (!deleted) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
        ai.refreshDraft(typing = true)
    }

    private fun handleSpace() {
        swipeJob?.cancel()
        if (searchQuery != null) {
            handleCharTyped(" ")
            return
        }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.insert(" ")
            return
        }
        val ic = currentInputConnection ?: return
        if (ic.commitText(" ", 1)) ai.textCommitted(" ")
    }

    private fun handleAction() {
        swipeJob?.cancel()
        searchQuery?.let { ai.ask(it.text); return }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.enter()
            return
        }
        ai.reset()
        editorContext.performAction(currentInputConnection)
    }

    private fun handleShiftClick() {
        keyboardState = keyboardState.toggleShift()
    }

    private fun handleToggleSymbols() {
        keyboardState = keyboardState.toggleSymbols()
    }

    private fun handleToggleMoreSymbols() {
        keyboardState = keyboardState.toggleMoreSymbols()
    }

    private fun handleToggleEmoji() {
        keyboardState = keyboardState.toggleEmoji()
    }

    private fun handleToggleIncognito() {
        if (editorContext.isIncognito && !editorContext.isUserIncognito) {
            Toast.makeText(this, "Incognito is automatic for this private field", Toast.LENGTH_SHORT).show()
            return
        }
        settingsScope.launch {
            val next = !editorContext.isUserIncognito
            settingsStore.setKeyboardIncognito(next)
            Toast.makeText(this@MonologInputMethodService,
                if (next) "Incognito on for 15 minutes" else "Keyboard incognito off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCloseKeyboard() {
        if (isVoxOpen) {
            handleExitVox()
        }
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        searchQuery = null
        ai.reset()
        requestHideSelf(0)
    }
}
