package com.notokens.monolog.ime

import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.capture.ContextCaptureActivity
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.ime.ui.MonologKeyboardView
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ime.vox.VoxController
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.MonologTheme
import com.notokens.monolog.ui.theme.LocalMonologDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var userIncognito = false
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        settingsScope.launch {
            settingsStore.incognito.collect { enabled ->
                userIncognito = enabled
                editorContext = editorContext.copy(isUserIncognito = enabled)
                if (enabled) {
                    isShortcutsOpen = false
                    shortcutDraft = ShortcutDraft()
                    if (isVoxOpen) handleExitVox()
                }
            }
        }
    }

    private val voxController by lazy {
        VoxController(this) { state ->
            voxState = state
        }
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@MonologInputMethodService)
            )
            setContent {
                val appearance by settingsStore.appearance.collectAsState(initial = Appearance())
                MonologTheme(appearance = appearance) {
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
                        onToggleShortcuts = ::handleToggleShortcuts,
                        onCaptureContext = ::handleCaptureContext,
                        onOpenSettings = ::handleOpenSettings,
                        onEnterVox = ::handleEnterVox,
                        onExitVox = ::handleExitVox,
                        onStartListening = { voxController.startListening() },
                        onPauseListening = { voxController.pauseOrStopListening() },
                        onClearTranscript = { voxController.clearTranscript() },
                        onCommitTranscript = ::handleCommitTranscript,
                        onRequestPermission = ::handleRequestMicPermission,
                        onCharTyped = ::handleCharTyped,
                        onBackspace = ::handleBackspace,
                        onSpace = ::handleSpace,
                        onAction = ::handleAction,
                        onShiftClick = ::handleShiftClick,
                        onToggleSymbols = ::handleToggleSymbols,
                        onToggleMoreSymbols = ::handleToggleMoreSymbols,
                        onToggleEmoji = ::handleToggleEmoji,
                        onSwitchKeyboard = ::handleSwitchKeyboard,
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
        val resolved = EditorContext.fromEditorInfo(info)
        editorContext = resolved.copy(isUserIncognito = userIncognito)

        // Always close any active private panels when switching editors
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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        voxController.cleanup()
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        isVoxOpen = false
        editorContext = EditorContext.default().copy(isUserIncognito = userIncognito)
        keyboardState = KeyboardState()
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsScope.cancel()
        voxController.cleanup()
    }

    private fun handleToggleShortcuts() {
        if (editorContext.isIncognito) return
        if (isVoxOpen) {
            handleExitVox()
        }
        isShortcutsOpen = !isShortcutsOpen
        shortcutDraft = ShortcutDraft()
    }

    private fun handleCaptureContext() {
        if (editorContext.isIncognito) {
            Toast.makeText(this, "Context capture disabled in incognito mode", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ContextCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        requestHideSelf(0)
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
        if (editorContext.isIncognito) return
        isVoxOpen = true
        voxController.startListening()
    }

    private fun handleExitVox() {
        voxController.cleanup()
        isVoxOpen = false
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

    private fun handleCharTyped(text: String) {
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.insert(text)
            keyboardState = keyboardState.onLetterTyped()
            return
        }
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        keyboardState = keyboardState.onLetterTyped()
    }

    private fun handleBackspace() {
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.backspace()
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
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
    }

    private fun handleSpace() {
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.insert(" ")
            return
        }
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
    }

    private fun handleAction() {
        if (isShortcutsOpen && shortcutDraft.isEditing) {
            shortcutDraft = shortcutDraft.enter()
            return
        }
        editorContext.performAction(currentInputConnection)
    }

    private fun handleShiftClick() {
        keyboardState = keyboardState.cycleShift()
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

    private fun handleSwitchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        var switched = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                switched = switchToNextInputMethod(false)
            } catch (_: Exception) {
                switched = false
            }
        }
        if (!switched) {
            imm?.showInputMethodPicker()
        }
    }

    private fun handleCloseKeyboard() {
        if (isVoxOpen) {
            handleExitVox()
        }
        isShortcutsOpen = false
        shortcutDraft = ShortcutDraft()
        requestHideSelf(0)
    }
}
