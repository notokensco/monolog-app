package com.notokens.monolog.ime

import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.drawable.ColorDrawable
import android.icu.text.BreakIterator
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.KeyEvent
import android.view.Display
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.autofill.AutofillManager
import android.widget.inline.InlinePresentationSpec
import android.widget.Toast
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
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
import com.notokens.monolog.data.RememberLanguage
import com.notokens.monolog.data.RememberNotebook
import com.notokens.monolog.data.TypingMetric
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ime.ui.ClipboardImage
import com.notokens.monolog.ime.ui.KeyboardMedia
import com.notokens.monolog.ime.ui.KeyboardMediaProvider
import com.notokens.monolog.ime.ui.EditorCommand
import com.notokens.monolog.ime.ui.KeyboardTranslation
import com.notokens.monolog.ime.ui.CursorDrag
import com.notokens.monolog.ime.vox.OfflineMoonshine
import com.notokens.monolog.ime.vox.RegionalVox
import com.notokens.monolog.ime.vox.VoxLanguage
import com.notokens.monolog.ime.vox.VoxStatus
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var userIncognito = false
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val translation by lazy {
        KeyboardTranslation(applicationContext, settingsScope, { currentInputConnection }) {
            editorActive && !editorContext.isIncognito && !editorContext.isRestrictedField
        }
    }
    private var floatingBounds: android.graphics.Rect? = null
    private var editingSelection = false
    private var systemAutofillSuggestions by mutableStateOf(emptyList<InlineSuggestion>())

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val density = resources.displayMetrics.density
        val style = UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()
        val size = InlinePresentationSpec.Builder(Size(0, (32 * density).roundToInt()),
            Size((240 * density).roundToInt(), (44 * density).roundToInt())).setStyle(style).build()
        return InlineSuggestionsRequest.Builder(listOf(size)).setMaxSuggestionCount(4).build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        systemAutofillSuggestions = response.inlineSuggestions.take(4)
        return true
    }

    private fun requestSystemAutofill(field: InputConnection) {
        val autofill = getSystemService(AutofillManager::class.java)
        when {
            autofill?.isAutofillSupported != true ->
                Toast.makeText(this, "Android Autofill is unavailable on this phone", Toast.LENGTH_SHORT).show()
            !autofill.hasEnabledAutofillServices() -> {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "Choose your preferred Autofill service in Android Settings", Toast.LENGTH_LONG).show()
                }
            }
            systemAutofillSuggestions.isEmpty() && !field.performContextMenuAction(android.R.id.autofill) ->
                Toast.makeText(this, "Autofill is unavailable in this field", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val bounds = floatingBounds ?: return
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(bounds)
        val windowBottom = maxOf(bounds.bottom, window?.window?.decorView?.height ?: 0)
        outInsets.contentTopInsets = windowBottom
        outInsets.visibleTopInsets = windowBottom
    }

    private fun updateFloatingBounds(value: android.graphics.Rect?) {
        if (value == floatingBounds) return
        floatingBounds = value
        window?.window?.decorView?.requestLayout()
    }

    private fun editorCommand(command: EditorCommand) {
        if (!editorActive) return
        val field = currentInputConnection ?: return
        if (command == EditorCommand.AUTOFILL) {
            requestSystemAutofill(field)
            return
        }
        val action = when (command) {
            EditorCommand.ALL -> android.R.id.selectAll
            EditorCommand.CUT -> android.R.id.cut
            EditorCommand.COPY -> android.R.id.copy
            EditorCommand.PASTE -> android.R.id.paste
            EditorCommand.UNDO -> android.R.id.undo
            EditorCommand.REDO -> android.R.id.redo
            else -> null
        }
        if (action != null) {
            translation.close()
            if (field.performContextMenuAction(action)) {
                memoryRecorder.clear()
                ai.refreshDraft()
                return
            }
            when (command) {
                EditorCommand.UNDO -> sendEditingKeys(field, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                EditorCommand.REDO -> sendEditingKeys(field, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                else -> Unit
            }
            return
        }
        if (command == EditorCommand.SELECT) {
            editingSelection = !editingSelection
            return
        }
        translation.close()
        val key = when (command) {
            EditorCommand.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorCommand.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            EditorCommand.UP -> KeyEvent.KEYCODE_DPAD_UP
            EditorCommand.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> return
        }
        sendEditingKeys(field, key, if (editingSelection) KeyEvent.META_SHIFT_ON else 0)
    }

    private fun sendEditingKeys(field: InputConnection, key: Int, meta: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        field.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
        field.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
    }

    private var privacyReady by mutableStateOf(false)
    private var swipeTypingEnabled by mutableStateOf(true)
    private var autocorrectEnabled = true
    private var autoCapitalize = false
    private val typingSession = TypingSession()
    private var learnTypingPatterns = true
    private var rescueEnabled = false
    private var rescueEdited = false
    private var rescueSnapshot: String? = null
    private var rescueReadJob: Job? = null
    private var rescueHint by mutableStateOf(false)
    private data class CorrectionUndo(val original: String, val inserted: String,
        val connection: android.view.inputmethod.InputConnection, val end: Int)
    private var autocorrectUndo: CorrectionUndo? = null
    private var phoneActionsEnabled by mutableStateOf(true)
    private var editorActive by mutableStateOf(false)
    private var defaultVox = false
    private var keyboardSession = 0
    private var layoutSession by mutableStateOf(0)
    private data class EditorIdentity(val app: String?, val field: Int, val name: String?, val inputType: Int)
    private var visibleEditor: EditorIdentity? = null
    private val sensitiveDraft = SensitiveDraft()
    private var voxComposition: android.view.inputmethod.InputConnection? = null
    private var voxPrefix = ""
    private var voxResumeAfterDelete: Job? = null
    private var suppressDraftMemory = false
    private var memoryPrivacyReady = false
    private var language by mutableStateOf(KeyboardLanguage.of("en"))
    private val dictionary by lazy { EnglishSuggestions(applicationContext) }
    private var swipeJob: Job? = null
    private val afterGlide = ArrayDeque<() -> Unit>()
    private var selectionVersion = 0L
    private var lastKeyEdit = 0L
    private var regionalLanguage by mutableStateOf<String?>(null)
    private var voiceEngine by mutableStateOf(VoiceInputEngine.NATIVE)
    private var voxLanguages by mutableStateOf(emptyList<VoxLanguage>())
    private var voxLanguageChange: Job? = null
    private val keyboardModelsVisible = MutableStateFlow(false)

    private data class CursorDragSession(val connection: InputConnection, val text: CharSequence,
        val start: Int, val breaks: BreakIterator, var position: Int)
    private var cursorSession: CursorDragSession? = null
    private var cursorDragging by mutableStateOf(false)
    private var cursorCharacter by mutableStateOf<String?>(null)
    private val cursorDrag = CursorDrag(::startCursorDrag, ::moveCursor, ::stopCursorDrag)
    private fun voxLanguage() = VoxLanguage.resolve(regionalLanguage, language.speechTag)
    private var lastShiftTap: Long? = null
    private val memoryRecorder by lazy { com.notokens.monolog.memory.DraftMemoryRecorder(applicationContext, settingsScope) }
    private val ai by lazy {
        ImeAi(this, settingsScope, settingsStore, dictionary, { currentInputConnection }, ::aiAllowed,
            canShowLocalToolbar = { privacyReady && editorActive && !editorContext.isRestrictedField },
            typedActionsAllowed = {
                !editorContext.isIncognito && !suppressDraftMemory &&
                    editorContext.inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_DATETIME
            },
            proactiveAllowed = { editorContext.packageName != packageName },
            automaticNetworkAllowed = { !editorContext.isUri && !editorContext.isIncognito },
            personalizationAllowed = ::memoryAllowed,
            typingProfileAllowed = ::typingProfileAllowed,
            onSuggestionAccepted = {
                if (typingProfileAllowed()) {
                    typingSession.resetWord()
                    typingSession.increment(TypingMetric.SUGGESTIONS)
                }
            },
            personalSearchAllowed = ::privacyCheckAllowed,
            checkDraftPrivacy = ::checkDraftPrivacy,
            phoneActionsEnabled = { phoneActionsEnabled },
            activePackage = { editorContext.packageName.orEmpty() }) { old, text ->
            markTyped(text)
            memoryRecorder.replace(old, text, memoryAllowed())
        }
    }

    private fun aiAllowed(): Boolean = privacyReady && editorActive && !editorContext.isRestrictedField &&
        !editorContext.isNumber && !editorContext.isEmail
    private fun privacyCheckAllowed(): Boolean = aiAllowed() && !editorContext.isIncognito && !suppressDraftMemory
    private fun memoryAllowed(): Boolean = memoryPrivacyReady && privacyCheckAllowed()

    private fun typingProfileAllowed(): Boolean = memoryPrivacyReady && privacyCheckAllowed() &&
        !editorContext.isEmail && !editorContext.isUri && !editorContext.isNumber

    private fun saveTypingSession(includeCurrent: Boolean, verify: Boolean = true) {
        if (!typingSession.hasUpdates) return
        if (verify && typingProfileAllowed()) verifyEditorPrivacy()
        if (!typingProfileAllowed()) {
            typingSession.clear()
            return
        }
        if (learnTypingPatterns && typingSession.hasPendingCorrection) {
            currentInputConnection?.let { confirmTypingCorrection(it) }
            typingSession.finishWord()
        } else if (includeCurrent) typingSession.finishWord()
        val (words, counts, transitions) = typingSession.take()
        settingsScope.launch(Dispatchers.Default) { settingsStore.recordTyping(words, counts, transitions) }
    }

    private fun confirmTypingCorrection(connection: InputConnection, delimiter: String = "") {
        if (!learnTypingPatterns || !typingSession.hasPendingCorrection) return
        val before = connection.getTextBeforeCursor(96, 0)?.toString() ?: return
        if (before.endsWith(delimiter)) typingSession.confirmCorrection(before.dropLast(delimiter.length))
    }

    private fun applySentenceCapitalization() {
        if (!autoCapitalize || keyboardState.shiftState == ShiftState.CAPS_LOCK ||
            editorContext.isEmail || editorContext.isUri || editorContext.isNumber || editorContext.isRestrictedField) return
        val sentence = android.text.TextUtils.CAP_MODE_SENTENCES
        val mode = currentInputConnection?.getCursorCapsMode(sentence) ?: return
        if ((mode and sentence) != 0) keyboardState = keyboardState.copy(shiftState = ShiftState.ONCE)
    }

    private fun markTyped(text: String) {
        if (!aiAllowed() || suppressDraftMemory) return
        if (sensitiveDraft.append(text)) enterSensitiveMode()
        if (rescueEnabled && privacyCheckAllowed() && !editorContext.isUri) {
            val connection = currentInputConnection ?: return
            val session = keyboardSession
            rescueEdited = true
            rescueReadJob?.cancel()
            rescueReadJob = settingsScope.launch {
                delay(450)
                val draft = withContext(editorReadDispatcher) { readRescueDraft(connection) }
                if (session == keyboardSession && connection === currentInputConnection &&
                    privacyCheckAllowed() && rescueEnabled && draft != null && !sensitiveDraft.matches(draft)) {
                    rescueSnapshot = draft
                }
            }
        }
    }

    private fun checkDraftPrivacy(text: String) {
        if (!suppressDraftMemory && sensitiveDraft.matches(text)) enterSensitiveMode()
    }

    private fun enterSensitiveMode() {
        suppressDraftMemory = true
        clearRescueEditor()
        typingSession.clear()
        memoryRecorder.clear()
        sensitiveDraft.clear()
        editorContext = editorContext.copy(sensitiveDraft = true)
        ai.disableWebSuggestions()
        ai.abortAction()

    }

    // Address bars are text editors too. Local gesture decoding does not need AI permission.
    private fun swipeAllowed(): Boolean = swipeTypingEnabled && privacyReady && editorActive &&
        !editorContext.isRestrictedField && !editorContext.isNumber

    override fun onCreate() {
        super.onCreate()
        settingsScope.launch {
            settingsStore.draftRescue.collect { enabled ->
                rescueEnabled = enabled
                if (!enabled) {
                    clearRescueEditor()
                    DraftRescue.clear()
                }
            }
        }
        settingsScope.launch(Dispatchers.IO) {
            com.notokens.monolog.ime.vox.OfflineMoonshine.removeRetiredModels(applicationContext)
        }
        settingsScope.launch(Dispatchers.Default) {
            delay(350)
            preloadEmojiCatalog(assets)
            NativeTyping.available
        }
        settingsScope.launch {
            settingsStore.incognito.collect { enabled ->
                userIncognito = enabled
                editorContext = editorContext.copy(isUserIncognito = enabled)
                privacyReady = true
                if (enabled) {
                    clearRescueEditor()
                    typingSession.clear()
                    memoryRecorder.clear()
                    ai.reset()
                    LocalAi.release()
                    searchQuery = null
                    isShortcutsOpen = false
                    shortcutDraft = ShortcutDraft()
                } else if (editorActive) {
                    inspectEditorPrivacy()
                    ai.refreshDraft()
                }
            }
        }
        settingsScope.launch {
            settingsStore.keyboardLanguage.collect {
                language = KeyboardLanguage.of(it)
                settingsScope.launch(Dispatchers.Default) {
                    NativeTyping.warm(applicationContext, it)
                    dictionary.warm(KeyboardLanguage.of(it))
                }
            }
        }
        settingsScope.launch { settingsStore.phoneActions.collect { phoneActionsEnabled = it; ai.onPhoneActionsChanged() } }
        observeModelWarmup()
        settingsScope.launch { settingsStore.defaultVox.collect { defaultVox = it } }
        settingsScope.launch { settingsStore.smartVoxFilter.collect { voxController.smartFilter = it } }
        settingsScope.launch { settingsStore.sensitiveWords.collect(sensitiveDraft::setWords) }
        settingsScope.launch {
            combine(settingsStore.personalWords, settingsStore.typingProfile, settingsStore.learnWords) { words, profile, learn ->
                Triple(words, profile, learn)
            }.collect { (words, profile, learn) ->
                learnTypingPatterns = learn
                withContext(Dispatchers.Default) { dictionary.setTypingProfile(words, profile, learn) }
            }
        }
        settingsScope.launch {
            settingsStore.autoCapitalize.collect { autoCapitalize = it }
        }
        settingsScope.launch {
            settingsStore.autocorrect.collect { autocorrectEnabled = it; autocorrectUndo = null }
        }
        settingsScope.launch {
            settingsStore.swipeTyping.collect {
                swipeTypingEnabled = it
                if (!it) swipeJob?.cancel()
            }
        }
        settingsScope.launch {
            settingsStore.webSuggestions.collect { if (!it) ai.disableWebSuggestions() }
        }
        settingsScope.launch {
            settingsStore.webQuestionSuggestions.collect { if (!it) ai.disableWebSuggestions() }
        }
    }

    private fun observeModelWarmup() {
        settingsScope.launch {
            keyboardModelsVisible.dropWhile { !it }.collectLatest { visible ->
                if (!visible) {
                    // App switches often close the IME briefly. Reopening cancels eviction.
                    delay(15_000)
                    val stillHidden = { !keyboardModelsVisible.value }
                    LocalAi.release(stillHidden)
                    RegionalVox.release(stillHidden)
                    OfflineMoonshine.release(stillHidden)
                }
            }
        }
        val installedPack = RegionalVox.installation.map { (it as? RegionalVox.Install.Ready)?.pack }
            .distinctUntilChanged()
        settingsScope.launch {
            combine(settingsStore.voiceInputEngine, settingsStore.voxLanguage, installedPack) { engine, code, _ ->
                voiceEngine = engine
                regionalLanguage = code
                voxLanguages = when (engine) {
                    VoiceInputEngine.REGIONAL -> VoxLanguage.entries.filter { RegionalVox.ready(applicationContext, it) }
                    VoiceInputEngine.MOONSHINE -> listOf(VoxLanguage.ENGLISH)
                    VoiceInputEngine.NATIVE -> emptyList()
                }
            }.collect()
        }
    }

    private var voxPersonal by mutableStateOf(false)
    private var voxAudio by mutableStateOf(false)
    private var voxNotePreview by mutableStateOf(false)
    private var voxRememberPreview by mutableStateOf(false)
    private var savingVoxAudio by mutableStateOf(false)
    private var spokenNote: String? = null
    private var spokenRemember = false
    private var spokenQuestion = ""
    private var voxManualPause = false

    private val voxController by lazy {
        VoxController(this, settingsScope) { state ->
            onVoxUpdate(state)
        }
    }

    private fun onVoxUpdate(state: VoxState) {
        val previous = voxState
        val noteListening = voxNotePreview
        voxState = state
        if (!isVoxOpen) return
        if (!voxAudio && state.finalSegment.isNotBlank() && state.transcript != previous.transcript) {
            handleCommitTranscript(state.finalSegment)
        } else if (!voxAudio && state.partialTranscript != previous.partialTranscript) {
            updateVoxComposition(state.partialTranscript)
        }
        val ended = state.status in setOf(VoxStatus.PAUSED, VoxStatus.ERROR, VoxStatus.UNAVAILABLE)
        if (!ended || state.status == previous.status) return
        when {
            voxAudio -> finishVoxAudio()
            spokenNote != null -> {
                if (!noteListening && !voxManualPause && state.status == VoxStatus.PAUSED) {
                    startVoxListening()
                    return
                }
                handleExitVox()
            }
            voxPersonal && spokenQuestion.isNotBlank() -> {
                val question = spokenQuestion.take(300)
                val allowed = ai.personalAllowed
                handleExitVox()
                if (allowed) {
                    handleOpenSearch()
                    searchQuery = TextFieldValue(question, TextRange(question.length))
                    if (!ai.personal) ai.togglePersonal()
                    ai.ask(question)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@MonologInputMethodService)
            )
            setContent {
                val appearance by settingsStore.appearance.collectAsState(initial = Appearance())
                val longPressCharacters by settingsStore.longPressCharacters.collectAsState(initial = true)
                MonologTheme(appearance = if (editorContext.isIncognito) appearance.copy(theme = ThemeMode.INK) else appearance) {
                    val lightBars = !LocalMonologDark.current
                    SideEffect {
                        window?.window?.let { imeWindow ->
                            WindowCompat.getInsetsController(imeWindow, imeWindow.decorView)
                                .isAppearanceLightNavigationBars = lightBars
                        }
                    }
                    MonologKeyboardView(
                        layoutSession = layoutSession,
                        editorContext = editorContext,
                        editorActive = editorActive,
                        keyboardState = keyboardState,
                        voxState = voxState,
                        voxLanguage = voxLanguage(),
                        voxLanguageLabel = when (voiceEngine) {
                            VoiceInputEngine.REGIONAL -> voxLanguage().title
                            VoiceInputEngine.MOONSHINE -> VoxLanguage.ENGLISH.title
                            VoiceInputEngine.NATIVE -> language.label.substringBefore('·').trim()
                        },
                        voxLanguages = voxLanguages,
                        onVoxLanguage = ::changeVoxLanguage,
                        cursorDrag = cursorDrag,
                        cursorActive = cursorDragging,
                        cursorCharacter = cursorCharacter,
                        isShortcutsOpen = isShortcutsOpen,
                        isVoxOpen = isVoxOpen,
                        shortcutDraft = shortcutDraft,
                        onDraftChange = { shortcutDraft = it },
                        onSuggestedShortcut = ::handleSuggestedShortcut,
                        onToggleShortcuts = ::handleToggleShortcuts,
                        onCaptureContext = ::handleCaptureContext,
                        rescueHint = rescueHint,
                        onConsumeRescueHint = { rescueHint = false },
                        onDeleteWords = ::handleGlideDelete,
                        onDismissShortcuts = ::dismissShortcuts,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { ai.abortAction(); searchQuery = it },
                        onOpenSearch = ::handleOpenSearch,
                        onCloseSearch = ::handleCloseSearch,
                        ai = ai,
                        aiAllowed = aiAllowed(),
                        systemAutofillSuggestions = systemAutofillSuggestions,
                        swipeAllowed = swipeAllowed(),
                        longPressCharacters = longPressCharacters,
                        language = language,
                        onOpenSettings = { handleOpenSettings() },
                        onAppearance = { handleOpenSettings(MainActivity.ACTION_OPEN_APPEARANCE) },
                        onEditorCommand = ::editorCommand,
                        onFloatingBounds = ::updateFloatingBounds,
                        translation = translation,
                        onOpenVoiceSettings = { handleOpenSettings(MainActivity.ACTION_OPEN_VOICE) },
                        onEnterVox = ::handleEnterVox,
                        onExitVox = ::handleExitVox,
                        onStartListening = ::startVoxListening,
                        onPauseListening = {
                            voxManualPause = true
                            voxController.pauseOrStopListening()
                        },
                        voxPersonal = voxPersonal,
                        voxAudio = voxAudio,
                        voxNotePreview = voxNotePreview,
                        voxRememberPreview = voxRememberPreview,
                        voxPrivateActions = privacyCheckAllowed() && !savingVoxAudio,
                        onToggleVoxPersonal = ::toggleVoxPersonal,
                        onVoxAudio = ::toggleVoxAudio,
                        onDeleteVoxWord = ::deleteVoxWord,
                        onRequestPermission = ::handleRequestMicPermission,
                        onCharTyped = { handleCharTyped(it, manualKey = it.codePointCount(0, it.length) == 1) },
                        onPasteText = { handleCharTyped(it, manualKey = false) },
                        onEmojiSuggestion = ::replaceWithEmoji,
                        onPasteImage = ::pasteImage,
                        onPasteMedia = ::pasteMedia,
                        onLetterTyped = ::handleLetterTyped,
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

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        systemAutofillSuggestions = emptyList()
        super.onStartInput(info, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        afterGlide.clear()
        super.onStartInputView(info, restarting)
        autocorrectUndo = null
        swipeJob?.cancel()
        keyboardSession++
        val identity = info?.let { EditorIdentity(it.packageName, it.fieldId, it.fieldName, it.inputType) }
        val sameEditor = restarting && identity != null && identity == visibleEditor
        visibleEditor = identity
        if (sameEditor) {
            saveRescueDraft(useCached = true, readEditor = false)
            finishJournalDraft()
            saveTypingSession(includeCurrent = false, verify = false)
        } else typingSession.clear()
        clearRescueEditor()
        sensitiveDraft.clear()
        suppressDraftMemory = false
        memoryPrivacyReady = false
        val resolved = EditorContext.fromEditorInfo(info)
        memoryRecorder.start(info?.packageName.orEmpty())
        editorContext = resolved.copy(isUserIncognito = userIncognito)
        editorActive = info != null
        rescueHint = rescueEnabled && privacyCheckAllowed() && DraftRescue.takeHint()
        if (isInputViewShown) keyboardModelsVisible.value = editorActive && !resolved.isRestrictedField
        stopCursorDrag()
        ai.reset(info?.takeIf { it.initialSelStart == it.initialSelEnd }?.initialSelEnd ?: -1)
        inspectEditorPrivacy()
        ai.refreshDraft()

        // Always close any active private panels when switching editors
        searchQuery = null
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        if (isVoxOpen) {
            handleExitVox()
        }

        // Sending a message can restart the same editor while the IME remains visible.
        if (sameEditor) return
        translation.close()
        layoutSession++
        keyboardState = if (resolved.isNumber) {
            KeyboardState(layer = KeyboardLayer.SYMBOLS, shiftState = ShiftState.OFF)
        } else {
            KeyboardState(layer = KeyboardLayer.LETTERS)
        }
        applySentenceCapitalization()
        if (defaultVox && aiAllowed()) {
            voxState = VoxState()
            isVoxOpen = true
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (rescueEnabled) DraftRescue.refresh()
        keyboardModelsVisible.value = !editorContext.isRestrictedField
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if ((memoryAllowed() && memoryRecorder.hasPendingText) ||
            (typingProfileAllowed() && typingSession.hasUpdates)) verifyEditorPrivacy()
        saveTypingSession(includeCurrent = false, verify = false)
        saveRescueDraft()
        translation.close()
        updateFloatingBounds(null)
        afterGlide.clear()
        finishJournalDraft(verify = false)
        if (isVoxOpen) handleExitVox()
        editorActive = false
        stopCursorDrag()
        swipeJob?.cancel()
        ai.reset()
        super.onFinishInputView(finishingInput)
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        searchQuery = null
    }

    override fun onWindowHidden() {
        systemAutofillSuggestions = emptyList()
        finishJournalDraft()
        saveRescueDraft()
        saveTypingSession(includeCurrent = false)
        editorActive = false
        translation.close()
        updateFloatingBounds(null)
        afterGlide.clear()
        super.onWindowHidden()
        keyboardModelsVisible.value = false
        stopCursorDrag()
        keyboardSession++
        layoutSession++
        visibleEditor = null
        keyboardState = KeyboardState()
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        ai.reset()
        searchQuery = null
        if (isVoxOpen) handleExitVox()
    }

    override fun onFinishInput() {
        systemAutofillSuggestions = emptyList()
        finishJournalDraft()
        saveRescueDraft()
        saveTypingSession(includeCurrent = false)
        afterGlide.clear()
        editorActive = false
        swipeJob?.cancel()
        ai.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsScope.cancel()
        voxController.cleanup()
        RegionalVox.release()
        OfflineMoonshine.release()
        LocalAi.release()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            LocalAi.release()
            if (!isVoxOpen) {
                RegionalVox.release()
                OfflineMoonshine.release()
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) selectionVersion++
        if (oldSelStart > 0 && newSelStart == 0 && newSelEnd == 0 &&
            (memoryRecorder.hasPendingText && memoryAllowed() ||
                rescueEnabled && rescueEdited && rescueSnapshot != null && privacyCheckAllowed())) {
            val connection = currentInputConnection
            val session = keyboardSession
            val selection = selectionVersion
            val externalChange = android.os.SystemClock.uptimeMillis() - lastKeyEdit >= 180
            if (connection != null) settingsScope.launch {
                val editorEmpty = withContext(editorReadDispatcher) {
                    val text = runCatching { connection.getTextAfterCursor(1, 0) }.getOrNull()
                    text?.isEmpty() == true
                }
                if (editorEmpty && session == keyboardSession && selection == selectionVersion &&
                    connection === currentInputConnection) {
                    finishJournalDraft(verify = false)
                    if (externalChange) saveRescueDraft(useCached = true, readEditor = false)
                }
            }
        }
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
        if (editorContext.isIncognito) {
            Toast.makeText(this, "Reminder detected. Leave Incognito to save it.", Toast.LENGTH_SHORT).show()
            return
        }
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
        val displayId = window?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY
        if (!ScreenCaptureService.capture(displayId, hideKeyboard = true)) {
            Toast.makeText(this, "Enable the Quick Capture service in Accessibility settings", Toast.LENGTH_SHORT).show()
        }
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

    private fun handleOpenSettings(destination: String = MainActivity.ACTION_OPEN_SETTINGS) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = destination
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
        LocalAi.release()
        voxController.clearTranscript()
        isVoxOpen = true
        startVoxListening()
    }

    private fun startVoxListening() {
        if (voxAudio || savingVoxAudio) return
        voxManualPause = false
        voxResumeAfterDelete?.cancel()
        voxResumeAfterDelete = null
        voxController.startListening(language.speechTag, language.label, voxLanguage().takeIf {
            voiceEngine == VoiceInputEngine.REGIONAL
        }, extended = spokenNote != null, allowSystemSpellCheck = privacyCheckAllowed())
    }

    private fun changeVoxLanguage(mode: VoxLanguage) {
        if (voxAudio || voxNotePreview || savingVoxAudio) return
        voxResumeAfterDelete?.cancel()
        voxResumeAfterDelete = null
        voxLanguageChange?.cancel()
        voxLanguageChange = settingsScope.launch {
            val resume = voxState.status == VoxStatus.LISTENING || voxState.status == VoxStatus.PREPARING
            if (resume || voxState.status == VoxStatus.PROCESSING) voxController.finishCurrentPhrase()
            if (!isVoxOpen) return@launch
            settingsStore.setVoxLanguage(mode.code)
            regionalLanguage = mode.code
            if (resume) startVoxListening()
            else voxState = voxState.copy(status = VoxStatus.PAUSED, errorMessage = null)
        }
    }

    private fun handleExitVox() {
        voxResumeAfterDelete?.cancel()
        voxResumeAfterDelete = null
        voxLanguageChange?.cancel()
        clearVoxComposition()
        voxController.cleanup()
        isVoxOpen = false
        voxAudio = false
        voxPersonal = false
        voxNotePreview = false
        voxRememberPreview = false
        spokenQuestion = ""
        val pendingNote = spokenNote
        val saveToRemember = spokenRemember
        spokenNote = null
        spokenRemember = false
        voxController.clearTranscript()
        ai.clearDraft()
        if (!pendingNote.isNullOrBlank()) storeVoxNote(pendingNote, saveToRemember)
    }

    private fun toggleVoxPersonal() {
        if (!ai.personalAllowed || voxAudio || spokenNote != null || savingVoxAudio) return
        clearVoxComposition()
        voxController.cleanup()
        voxPersonal = !voxPersonal
        spokenQuestion = ""
        voxNotePreview = false
        voxRememberPreview = false
        voxController.clearTranscript()
        startVoxListening()
    }

    private fun toggleVoxAudio() {
        if (voxAudio) { voxController.pauseOrStopListening(); return }
        if (!privacyCheckAllowed() || savingVoxAudio || spokenNote != null) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            handleRequestMicPermission()
            return
        }
        clearVoxComposition()
        voxController.cleanup()
        voxPersonal = false
        spokenQuestion = ""
        voxNotePreview = false
        voxRememberPreview = false
        voxController.clearTranscript()
        voxAudio = true
        voxController.startNoteRecording()
    }

    private fun finishVoxAudio() {
        voxAudio = false
        savingVoxAudio = true
        val recorder = voxController.takeVoiceSegment()
        handleExitVox()
        settingsScope.launch {
            try {
                val audio = recorder?.finishAudio()
                if (audio == null) Toast.makeText(this@MonologInputMethodService, "No audio was recorded.", Toast.LENGTH_SHORT).show()
                else writeVoxNote("", audio)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@MonologInputMethodService, "Couldn’t save the audio note.", Toast.LENGTH_SHORT).show() }
            finally { recorder?.cancel(); savingVoxAudio = false }
        }
    }

    private fun deleteVoxWord() {
        if (voxAudio || voxPersonal || voxNotePreview) return
        val connection = currentInputConnection ?: return
        if (!isVoxOpen || !editorActive) return
        val listening = voxState.status == VoxStatus.LISTENING || voxState.status == VoxStatus.PREPARING
        val resume = listening || voxResumeAfterDelete?.isActive == true
        voxResumeAfterDelete?.cancel()
        voxResumeAfterDelete = null
        voxLanguageChange?.cancel()

        if (listening || voxState.status == VoxStatus.PROCESSING || voxComposition === connection) {
            val partial = voxState.partialTranscript
            if (voxComposition === connection) {
                if (connection.finishComposingText()) {
                    voxComposition = null
                    markTyped(partial)
                    memoryRecorder.committed(voxPrefix + partial, memoryAllowed())
                } else clearVoxComposition()
            }
            voxController.cleanup()
            voxController.clearTranscript()
            voxState = voxState.copy(status = VoxStatus.PAUSED)
        }
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            if (connection.commitText("", 1)) memoryRecorder.clear()
        } else {
            val text = connection.getTextBeforeCursor(512, 0)?.toString().orEmpty()
            val wordEnd = text.trimEnd().length
            if (wordEnd > 0) {
                val words = BreakIterator.getWordInstance().apply { setText(text) }
                var end = wordEnd
                var start = words.preceding(end)
                while (start != BreakIterator.DONE && text.substring(start, end).none(Char::isLetterOrDigit)) {
                    end = start
                    if (end == 0) break
                    start = words.preceding(end)
                }
                val deleteFrom = if (end == 0 || start == BreakIterator.DONE) {
                    BreakIterator.getCharacterInstance().apply { setText(text) }.preceding(wordEnd).coerceAtLeast(0)
                } else start
                val removed = text.substring(deleteFrom)
                if (connection.deleteSurroundingText(removed.length, 0)) {
                    memoryRecorder.replace(removed, "", memoryAllowed())
                }
            }
        }
        ai.backspaceStarted()
        ai.refreshDraft(typing = true)
        if (resume) {
            voxResumeAfterDelete = settingsScope.launch {
                delay(420)
                voxResumeAfterDelete = null
                if (isVoxOpen && editorActive && connection === currentInputConnection) startVoxListening()
            }
        }
    }

    private fun updateVoxComposition(partial: String) {
        val allowed = privacyCheckAllowed()
        val lead = allowed && com.notokens.monolog.ime.vox.VoxText.noteBody(partial) != null
        voxRememberPreview = spokenRemember || (spokenNote == null && allowed && RememberLanguage.startsCommand(partial))
        voxNotePreview = spokenNote != null || voxRememberPreview || (!voxPersonal && lead)
        voxController.extendedNotePause = voxNotePreview
        if (partial.isBlank() || !aiAllowed() || voxPersonal || voxNotePreview ||
            (privacyCheckAllowed() && com.notokens.monolog.ime.vox.VoxText.isNoteLead(partial))) {
            clearVoxComposition()
            return
        }
        val connection = currentInputConnection ?: return
        if (voxComposition !== connection) voxPrefix = speechPrefix(connection)
        if (connection.setComposingText(voxPrefix + partial, 1)) voxComposition = connection
    }

    private fun clearVoxComposition() {
        val connection = voxComposition ?: return
        voxComposition = null
        if (connection === currentInputConnection) connection.commitText("", 1)
        else connection.finishComposingText()
    }

    private fun speechPrefix(connection: android.view.inputmethod.InputConnection): String =
        if (connection.getTextBeforeCursor(1, 0)?.lastOrNull()?.isWhitespace() == false) " " else ""

    private fun handleCommitTranscript(rawTranscript: String) {
        val transcript = rawTranscript.trim()
        val allowed = privacyCheckAllowed()
        val remember = if (allowed && spokenNote == null) RememberLanguage.spokenBody(transcript) else null
        if (voxPersonal && spokenNote == null && remember == null) {
            spokenQuestion = appendVoxText(spokenQuestion, transcript, 300)
            return
        }
        val command = if (allowed) com.notokens.monolog.ime.vox.VoxText.noteBody(transcript) else null
        if (spokenNote != null || remember != null || command != null) {
            clearVoxComposition()
            spokenRemember = spokenRemember || remember != null
            val limit = if (spokenRemember) RememberLanguage.MAX_LENGTH else 8_000
            spokenNote = appendVoxText(spokenNote.orEmpty(), remember ?: command ?: transcript, limit)
            voxNotePreview = true
            voxRememberPreview = spokenRemember
            voxController.extendedNotePause = true
            if (spokenNote.orEmpty().length >= limit) voxController.pauseOrStopListening()
            return
        }
        voxNotePreview = false
        voxRememberPreview = false
        voxController.extendedNotePause = false
        markTyped(transcript)
        if (transcript.isNotBlank()) {
            if (isShortcutsOpen && shortcutDraft.isEditing) shortcutDraft = shortcutDraft.insert(transcript)
            else currentInputConnection?.let { connection ->
                val prefix = if (voxComposition === connection) voxPrefix else speechPrefix(connection)
                val inserted = prefix + transcript
                if (connection.commitText(inserted, 1)) {
                    voxComposition = null
                    ai.textCommitted(inserted)
                    memoryRecorder.committed(inserted, memoryAllowed())
                }
            }
        }
    }

    private fun appendVoxText(previous: String, next: String, limit: Int): String =
        listOf(previous, next).filter(String::isNotBlank).joinToString(" ").take(limit)

    private fun storeVoxNote(body: String, remember: Boolean) {
        if (!privacyCheckAllowed()) return
        settingsScope.launch {
            try {
                if (remember) {
                    val saved = RememberNotebook(applicationContext).save(body)
                    Toast.makeText(this@MonologInputMethodService,
                        if (saved) "Added to Remember" else "Couldn’t save to Remember.", Toast.LENGTH_SHORT).show()
                } else writeVoxNote(body, null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@MonologInputMethodService, "Couldn’t save the note.", Toast.LENGTH_SHORT).show() }
        }
    }

    private suspend fun writeVoxNote(body: String, audio: String?) {
        if (settingsStore.incognito.first()) return
        if (body.isNotBlank() && sensitiveDraft.matches(body)) {
            Toast.makeText(this, "This note matches a privacy trigger and wasn’t saved.", Toast.LENGTH_SHORT).show()
            return
        }
        val title = body.trim().split(Regex("\\s+")).take(6).joinToString(" ").take(65)
            .ifBlank { "Audio Note" }
        val note = com.notokens.monolog.ime.vox.VoiceNoteBody.attach(body, audio)
        com.notokens.monolog.data.MonologData.repository(applicationContext).saveNote(title, note)
        Toast.makeText(this, if (audio != null) "Audio note saved" else "Note saved", Toast.LENGTH_SHORT).show()
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

    private fun startCursorDrag() {
        if (!editorActive) return
        typingSession.resetContext()
        cursorDragging = true
        cursorCharacter = null
        val draft = searchQuery ?: shortcutDraft.activeValue.takeIf { isShortcutsOpen && shortcutDraft.isEditing }
        if (draft != null) {
            cursorCharacter = cursorLetter(draft.text, draft.selection.end)
            return
        }
        val connection = currentInputConnection ?: return
        if (editorContext.isRestrictedField) return
        val surrounding = connection.getSurroundingText(2048, 2048, 0) ?: return
        val end = surrounding.selectionEnd
        if (surrounding.offset < 0 || end !in 0..surrounding.text.length) return
        val text = surrounding.text.toString()
        val breaks = BreakIterator.getCharacterInstance().apply { setText(text) }
        cursorSession = CursorDragSession(connection, text, surrounding.offset, breaks, end)
        cursorCharacter = cursorLetter(text, end)
        autocorrectUndo = null
    }

    private fun moveCursor(steps: Int) {
        if (!cursorDragging) return
        val draft = searchQuery ?: shortcutDraft.activeValue.takeIf { isShortcutsOpen && shortcutDraft.isEditing }
        if (draft != null) {
            val breaks = BreakIterator.getCharacterInstance().apply { setText(draft.text) }
            val position = cursorStep(breaks, draft.selection.end, steps)
            val moved = draft.copy(selection = TextRange(position), composition = null)
            if (searchQuery != null) searchQuery = moved else shortcutDraft = shortcutDraft.update(moved)
            cursorCharacter = cursorLetter(draft.text, position)
            return
        }
        val connection = currentInputConnection ?: return
        val session = cursorSession?.takeIf { it.connection === connection }
        if (session != null) {
            val position = cursorStep(session.breaks, session.position, steps)
            if (position == session.position) return
            val absolute = position + session.start
            if (connection.setSelection(absolute, absolute)) {
                session.position = position
                cursorCharacter = cursorLetter(session.text, position)
                return
            }
            cursorSession = null
            cursorCharacter = null
        }
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps).coerceAtMost(16)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    private fun cursorStep(breaks: BreakIterator, start: Int, steps: Int): Int {
        var position = start
        repeat(kotlin.math.abs(steps).coerceAtMost(16)) {
            val next = if (steps < 0) breaks.preceding(position) else breaks.following(position)
            if (next == BreakIterator.DONE) return position
            position = next
        }
        return position
    }

    private fun cursorLetter(text: CharSequence, position: Int): String? =
        (text.getOrNull(position - 1)?.takeUnless(Char::isWhitespace) ?: text.getOrNull(position))?.toString()

    private fun stopCursorDrag() {
        cursorDragging = false
        cursorCharacter = null
        cursorSession = null
    }

    private fun deferDuringGlide(input: () -> Unit): Boolean {
        if (swipeJob?.isActive != true) return false
        afterGlide.addLast(input)
        return true
    }

    private fun handleSwipe(points: List<SwipePoint>) {
        if (deferDuringGlide { handleSwipe(points) }) return
        val inputLanguage = language
        if (!swipeAllowed() || !inputLanguage.supportsLatinSwipe || keyboardState.layer != KeyboardLayer.LETTERS) return
        val connection = currentInputConnection ?: return
        val query = searchQuery
        val shortcut = shortcutDraft.takeIf { isShortcutsOpen && it.isEditing }
        val state = keyboardState
        val selection = selectionVersion
        val session = keyboardSession
        val before = when {
            query != null -> query.text.take(query.selection.min).takeLast(96)
            shortcut != null -> shortcut.activeValue.let { it.text.take(it.selection.min).takeLast(96) }
            else -> connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        }
        val personalize = query == null && shortcut == null && typingProfileAllowed()
        swipeJob?.cancel()
        swipeJob = settingsScope.launch {
            try {
                val (ranked, next) = withContext(Dispatchers.Default) {
                    val choices = dictionary.swipe(inputLanguage, points, before, personalized = personalize)
                    choices to choices.firstOrNull()?.let { dictionary.nextAfter(it, inputLanguage, personalize) }.orEmpty()
                }
                val word = ranked.firstOrNull() ?: return@launch
                // A swipe belongs to the editor and cursor that were active when it ended.
                if (!swipeAllowed() || language != inputLanguage || currentInputConnection !== connection ||
                    searchQuery != query || keyboardState != state ||
                    shortcut != shortcutDraft.takeIf { isShortcutsOpen && it.isEditing } ||
                    isShortcutsOpen && shortcut == null ||
                    query == null && shortcut == null && selectionVersion != selection &&
                    (!connection.getSelectedText(0).isNullOrEmpty() ||
                        connection.getTextBeforeCursor(96, 0)?.toString().orEmpty() != before)) return@launch

                val previous = when {
                    query != null -> query.text.getOrNull(query.selection.min - 1)
                    shortcut != null -> shortcut.activeValue.let { it.text.getOrNull(it.selection.min - 1) }
                    else -> connection.getTextBeforeCursor(1, 0)?.lastOrNull()
                }
                swipeJob = null
                val inserted = formatSwipeWord(word, state.shiftState, previous)
                if (query != null || shortcut != null) {
                    handleCharTyped(inserted, manualKey = false)
                } else if (connection.commitText(inserted, 1)) {
                    markTyped(inserted)
                    autocorrectUndo = null
                    ai.textCommitted(inserted)
                    if (typingProfileAllowed()) {
                        typingSession.resetWord()
                        typingSession.increment(TypingMetric.SWIPES)
                        if (learnTypingPatterns) typingSession.type(inserted, keyPress = false)
                    }
                    memoryRecorder.committed(inserted, memoryAllowed())
                    ai.offerSwipeAlternatives(inserted.trimStart(),
                        ranked.drop(1).map { formatSwipeWord(it, state.shiftState, null) },
                        next.takeUnless { personalize && !typingProfileAllowed() }.orEmpty())
                    keyboardState = keyboardState.onLetterTyped()
                }
            } finally {
                if (swipeJob === kotlin.coroutines.coroutineContext[Job]) swipeJob = null
                if (keyboardSession == session && editorActive && settingsScope.coroutineContext[Job]?.isActive == true) {
                    while (swipeJob == null && afterGlide.isNotEmpty()) afterGlide.removeFirst().invoke()
                }
            }
        }
    }

    private fun pasteImage(image: ClipboardImage): Boolean {
        if (!image.file.isFile) return false
        return commitImageContent(image.mime, "Copied image") { image.uri(this) }
    }

    private fun pasteMedia(item: KeyboardMedia.Item): Boolean =
        commitImageContent(item.mime, item.name) { KeyboardMediaProvider.uriFor(this, item) }

    private fun commitImageContent(mime: String, label: String, uri: () -> android.net.Uri): Boolean {
        if (!editorActive || editorContext.isIncognito || editorContext.isRestrictedField) return false
        val accepted = currentInputEditorInfo?.contentMimeTypes.orEmpty().any {
            android.content.ClipDescription.compareMimeTypes(mime, it)
        }
        val inserted = accepted && runCatching {
            val info = android.view.inputmethod.InputContentInfo(uri(),
                android.content.ClipDescription(label, arrayOf(mime)), null)
            currentInputConnection?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true
        }.getOrDefault(false)
        if (!inserted) Toast.makeText(this, "This field does not accept this image", Toast.LENGTH_SHORT).show()
        return inserted
    }

    private fun replaceWithEmoji(typed: String, emoji: String): Boolean {
        if (deferDuringGlide { replaceWithEmoji(typed, emoji) }) return false
        if (!editorActive || editorContext.isRestrictedField || searchQuery != null) return false
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty() || ic.getTextBeforeCursor(typed.length, 0)?.toString() != typed) return false
        autocorrectUndo = null
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(typed.length, 0)) return false
            if (!ic.commitText(emoji, 1)) {
                ic.commitText(typed, 1)
                return false
            }
            memoryRecorder.replace(typed, emoji, memoryAllowed())
            ai.refreshDraft()
            return true
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun handleLetterTyped(text: String) {
        if (deferDuringGlide { handleLetterTyped(text) }) return
        val cased = if (keyboardState.shiftState.isUppercase) {
            if (text == "ß") "ẞ" else text.uppercase(java.util.Locale.ROOT)
        } else text.lowercase(java.util.Locale.ROOT)
        handleCharTyped(cased)
    }

    private fun handleCharTyped(text: String, manualKey: Boolean = true) {
        lastKeyEdit = android.os.SystemClock.uptimeMillis()
        if (deferDuringGlide { handleCharTyped(text, manualKey) }) return
        swipeJob?.cancel()
        lastShiftTap = null
        autocorrectUndo = null
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
        markTyped(text)
        if (translation.append(text)) {
            memoryRecorder.clear()
            keyboardState = keyboardState.onLetterTyped()
            return
        }
        if (manualKey && commitAutoCorrection(text)) {
            keyboardState = keyboardState.onLetterTyped()
            applySentenceCapitalization()
            return
        }
        if (ic.commitText(text, 1)) {
            if (typingProfileAllowed()) {
                if (manualKey) {
                    if (text.length == 1 && (text[0].isWhitespace() || text[0] in ".,!?;:()")) {
                        confirmTypingCorrection(ic, text)
                    }
                    typingSession.type(text)
                } else typingSession.resetWord()
            }
            ai.textCommitted(text)
            memoryRecorder.committed(text, memoryAllowed())
        }
        keyboardState = keyboardState.onLetterTyped()
        if (manualKey && text.lastOrNull() in listOf('.', '!', '?', '\n', ' ')) applySentenceCapitalization()
    }

    private fun handleBackspace() {
        lastKeyEdit = android.os.SystemClock.uptimeMillis()
        if (deferDuringGlide { handleBackspace() }) return
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
        if (translation.backspace()) return
        sensitiveDraft.backspace()
        val canLearn = typingProfileAllowed()
        if (canLearn) typingSession.delete()
        if (undoAutoCorrection()) {
            if (typingProfileAllowed()) typingSession.increment(TypingMetric.UNDONE)
            return
        }
        ai.backspaceStarted()
        val selected = if (editorContext.isIncognito) null else ic.getSelectedText(0)
        if (memoryAllowed()) memoryRecorder.backspace(!selected.isNullOrEmpty())
        if (!selected.isNullOrEmpty()) {
            if (ic.commitText("", 1)) {
                markTyped("")
                if (canLearn && learnTypingPatterns) {
                    ic.getTextBeforeCursor(96, 0)?.toString()?.let(typingSession::trackBackspace)
                }
            }
        } else {
            val previous = if (editorContext.isIncognito) null else ic.getTextBeforeCursor(128, 0)?.toString()
            val deleted = if (!previous.isNullOrEmpty()) {
                val boundary = BreakIterator.getCharacterInstance().apply { setText(previous) }.preceding(previous.length)
                val end = boundary.coerceAtLeast(0)
                ic.deleteSurroundingText(previous.length - end, 0).also { deleted ->
                    if (deleted && canLearn && learnTypingPatterns) typingSession.trackBackspace(previous.substring(0, end))
                }
            } else {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            }
            if (!deleted) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else markTyped("")
        }
        ai.refreshDraft(typing = true)
    }

    private fun handleGlideDelete(count: Int) {
        if (deferDuringGlide { handleGlideDelete(count) }) return
        if (!editorActive || editorContext.isRestrictedField || searchQuery != null || isShortcutsOpen || isVoxOpen ||
            translation.active) return
        val editor = currentInputConnection ?: return
        if (!editor.getSelectedText(0).isNullOrEmpty()) {
            handleBackspace()
            return
        }
        val text = editor.getTextBeforeCursor(480, 0)?.toString().orEmpty()
        val removed = wordDeletion.findAll(text).toList().takeLast(count.coerceIn(1, 6)).firstOrNull()
            ?.let { text.substring(it.range.first) } ?: return
        if (editor.deleteSurroundingText(removed.length, 0)) {
            autocorrectUndo = null
            sensitiveDraft.clear()
            memoryRecorder.replace(removed, "", memoryAllowed())
            if (typingProfileAllowed()) {
                typingSession.delete()
                if (learnTypingPatterns) typingSession.trackBackspace(text.dropLast(removed.length))
            }
            markTyped("")
            ai.backspaceStarted()
            ai.refreshDraft(typing = true)
        }
    }

    private val wordDeletion = Regex("""\S+\s*""")

    private fun handleSpace() {
        lastKeyEdit = android.os.SystemClock.uptimeMillis()
        if (deferDuringGlide { handleSpace() }) return
        swipeJob?.cancel()
        autocorrectUndo = null
        if (searchQuery != null) {
            handleCharTyped(" ")
            return
        }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.insert(" ")
            return
        }
        val ic = currentInputConnection ?: return
        if (translation.append(" ")) return
        markTyped(" ")
        if (commitAutoCorrection(" ")) {
            applySentenceCapitalization()
            return
        }
        if (ic.commitText(" ", 1)) {
            if (typingProfileAllowed()) {
                confirmTypingCorrection(ic, " ")
                typingSession.type(" ")
            }
            ai.textCommitted(" ")
            memoryRecorder.committed(" ", memoryAllowed())
            applySentenceCapitalization()
        }
    }

    private fun handleAction() {
        if (deferDuringGlide { handleAction() }) return
        swipeJob?.cancel()
        autocorrectUndo = null
        searchQuery?.let { ai.ask(it.text); return }
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.enter()
            return
        }
        if (translation.finish { editorContext.performAction(currentInputConnection) }) return
        if (editorContext.action == KeyboardAction.SEND || editorContext.action == KeyboardAction.GO ||
            editorContext.action == KeyboardAction.SEARCH || !editorContext.isMultiline) clearRescueEditor()
        if ((memoryAllowed() && memoryRecorder.hasPendingText) ||
            (typingProfileAllowed() && typingSession.hasUpdates)) verifyEditorPrivacy()
        saveTypingSession(includeCurrent = true, verify = false)
        ai.reset()
        if (memoryAllowed()) memoryRecorder.boundary() else memoryRecorder.clear()
        editorContext.performAction(currentInputConnection)
    }

    private fun inspectEditorPrivacy() {
        if (!privacyCheckAllowed()) return
        val connection = currentInputConnection ?: return
        val editor = keyboardSession
        settingsScope.launch {
            val text = withContext(editorReadDispatcher) { readEditorForPrivacy(connection) }
            if (editor == keyboardSession && connection === currentInputConnection && privacyCheckAllowed()) {
                checkDraftPrivacy(text)
                memoryPrivacyReady = true
            }
        }
    }

    private fun verifyEditorPrivacy() {
        if (!privacyCheckAllowed()) return
        currentInputConnection?.let { checkDraftPrivacy(readEditorForPrivacy(it)) }
    }

    private fun finishJournalDraft(verify: Boolean = true) {
        if (!memoryRecorder.hasPendingText) return
        if (verify && memoryAllowed()) verifyEditorPrivacy()
        if (memoryAllowed()) memoryRecorder.boundary() else memoryRecorder.clear()
    }

    private fun readRescueDraft(connection: InputConnection): String? {
        val request = android.view.inputmethod.ExtractedTextRequest().apply {
            hintMaxChars = DraftRescue.MAX_LENGTH
        }
        return runCatching {
            val extracted = connection.getExtractedText(request, 0)
            if (extracted?.startOffset == 0 && extracted.partialEndOffset < 0) {
                extracted.text?.takeIf { it.length <= DraftRescue.MAX_LENGTH }?.toString()
            } else {
                val limit = DraftRescue.MAX_LENGTH + 1
                connection.getSurroundingText(limit, limit, 0)?.takeIf { it.offset == 0 }
                    ?.text?.takeIf { it.length <= DraftRescue.MAX_LENGTH }?.toString()
            }
        }.getOrNull()
    }

    private fun saveRescueDraft(useCached: Boolean = false, readEditor: Boolean = true) {
        if (!rescueEdited) return
        if (rescueEnabled && privacyCheckAllowed() && !editorContext.isUri && editorContext.packageName != packageName) {
            val cached = rescueSnapshot
            val connection = currentInputConnection.takeIf { readEditor }
            val rescueRevision = DraftRescue.revision
            if (connection == null) {
                if (cached != null && !sensitiveDraft.matches(cached)) DraftRescue.save(cached)
            } else settingsScope.launch {
                val draft = withContext(editorReadDispatcher) { readRescueDraft(connection) }
                val eligible = draft?.takeUnless { useCached && it.isBlank() } ?: cached
                if (rescueRevision == DraftRescue.revision && rescueEnabled && !userIncognito &&
                    eligible != null && !sensitiveDraft.matches(eligible)) {
                    DraftRescue.save(eligible)
                    if (editorActive && currentInputConnection !== connection && privacyCheckAllowed() && DraftRescue.takeHint()) {
                        rescueHint = true
                    }
                }
            }
        }
        clearRescueEditor()
    }

    private fun clearRescueEditor() {
        rescueReadJob?.cancel()
        rescueSnapshot = null
        rescueEdited = false
        rescueHint = false
    }

    private fun readEditorForPrivacy(connection: InputConnection): String {
        val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text
        if (extracted != null) return extracted.toString()
        val surrounding = connection.getSurroundingText(1600, 1600, 0)?.text
        if (surrounding != null) return surrounding.toString()
        return connection.getTextBeforeCursor(1600, 0)?.toString().orEmpty() +
            connection.getSelectedText(0)?.toString().orEmpty() +
            connection.getTextAfterCursor(1600, 0)?.toString().orEmpty()
    }

    private fun cursorEnd(ic: android.view.inputmethod.InputConnection): Int? =
        ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            ?.takeIf { it.selectionStart == it.selectionEnd && it.selectionEnd >= 0 }
            ?.let { it.startOffset + it.selectionEnd }

    private fun commitAutoCorrection(delimiter: String): Boolean {
        val phonetic = language.transliterationTarget != null
        val hinglish = language.id == "hi-Latn"
        val correctionEnabled = autocorrectEnabled && !editorContext.isIncognito && (language.isEnglish || hinglish)
        if (!phonetic && learnTypingPatterns && typingSession.hasPendingCorrection) return false
        if (!(phonetic || correctionEnabled) || !aiAllowed() || editorContext.isUri ||
            delimiter.length != 1 || delimiter[0] !in " .,!?;:" ||
            editorContext.inputType and (android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(128, 0)?.toString() ?: return false
        val correction = (if (phonetic) dictionary.automaticTransliteration(before, language)
            else dictionary.automaticCorrection(before, contractionsOnly = hinglish)) ?: return false
        if (correction.typed == correction.replacement) return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        if (ic.getTextAfterCursor(1, 0)?.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(correction.typed.length, 0)) return false
            val inserted = correction.replacement + delimiter
            if (!ic.commitText(inserted, 1)) {
                ic.commitText(correction.typed, 1)
                return false
            }
            if (typingProfileAllowed()) {
                typingSession.resetWord()
                typingSession.increment(TypingMetric.KEYS)
                typingSession.increment(TypingMetric.CORRECTIONS)
            }
            memoryRecorder.replace(correction.typed, correction.replacement, memoryAllowed())
            memoryRecorder.committed(delimiter, memoryAllowed())
            val end = cursorEnd(ic)
            autocorrectUndo = end?.let { CorrectionUndo(correction.typed, inserted, ic, it) }
            ai.reset(end ?: -1)
            ai.textCommitted("")
            return true
        } finally { ic.endBatchEdit() }
    }

    private fun undoAutoCorrection(): Boolean {
        val undo = autocorrectUndo ?: return false
        autocorrectUndo = null
        val ic = currentInputConnection ?: return false
        if (!autocorrectEnabled || !aiAllowed() || ic !== undo.connection || cursorEnd(ic) != undo.end ||
            !ic.getSelectedText(0).isNullOrEmpty() ||
            ic.getTextBeforeCursor(undo.inserted.length, 0)?.toString() != undo.inserted) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(undo.inserted.length, 0)) return false
            if (!ic.commitText(undo.original, 1)) {
                ic.commitText(undo.inserted, 1)
                return true
            }
            memoryRecorder.replace(undo.inserted, undo.original, memoryAllowed())
            ai.reset(cursorEnd(ic) ?: -1)
            ai.refreshDraft(typing = true)
            return true
        } finally { ic.endBatchEdit() }
    }

    private fun handleShiftClick() {
        val now = android.os.SystemClock.uptimeMillis()
        val previous = lastShiftTap
        if (previous != null && now - previous < android.view.ViewConfiguration.getDoubleTapTimeout()) {
            keyboardState = keyboardState.copy(shiftState = ShiftState.CAPS_LOCK)
            lastShiftTap = null
        } else {
            lastShiftTap = now
            keyboardState = keyboardState.toggleShift()
        }
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
