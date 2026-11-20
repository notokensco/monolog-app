package com.notokens.monolog.ime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.notokens.monolog.MainActivity
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ime.ui.MonologKeyboardView
import com.notokens.monolog.ime.vox.VoxController
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Primary InputMethodService for Monolog.
 * Orchestrates user typing with InputConnection, manages shift/symbol layouts,
 * hosts the Quick Shortcuts (M-key) panel and Vox Voice typing mode,
 * and strictly isolates sensitive/password fields to guarantee zero data collection.
 */
class MonologInputMethodService : LifecycleInputMethodService() {

    private var editorContext by mutableStateOf(EditorContext.default())
    private var keyboardState by mutableStateOf(KeyboardState())
    private var isShortcutsOpen by mutableStateOf(false)
    private var isVoxOpen by mutableStateOf(false)
    private var voxState by mutableStateOf(VoxState())
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var userIncognito = false

    override fun onCreate() {
        super.onCreate()
        settingsScope.launch {
            SettingsStore(applicationContext).incognito.collect { enabled ->
                userIncognito = enabled
                editorContext = editorContext.copy(isUserIncognito = enabled)
                if (enabled) {
                    isShortcutsOpen = false
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
                MonologTheme(darkTheme = false) {
                    MonologKeyboardView(
                        editorContext = editorContext,
                        keyboardState = keyboardState,
                        voxState = voxState,
                        isShortcutsOpen = isShortcutsOpen,
                        isVoxOpen = isVoxOpen,
                        onToggleShortcuts = ::handleToggleShortcuts,
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
        if (isVoxOpen) {
            handleExitVox()
        }

        // If editor is numeric, automatically show symbols/numbers layer
        keyboardState = if (resolved.isNumber) {
            KeyboardState(layer = KeyboardLayer.SYMBOLS, shiftState = ShiftState.OFF)
        } else {
            KeyboardState(layer = KeyboardLayer.LETTERS, shiftState = ShiftState.OFF)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        voxController.cleanup()
        isShortcutsOpen = false
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
    }

    private fun handleEnterVox() {
        if (editorContext.isIncognito) return
        isShortcutsOpen = false
        isVoxOpen = true
        voxController.startListening()
    }

    private fun handleExitVox() {
        voxController.cleanup()
        isVoxOpen = false
    }

    private fun handleCommitTranscript(transcript: String) {
        if (transcript.isNotBlank()) {
            val ic = currentInputConnection
            ic?.commitText(transcript, 1)
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
        val ic = currentInputConnection ?: return
        // Commit typed text directly to host field without any logging, storage, or telemetry
        ic.commitText(text, 1)
        keyboardState = keyboardState.onLetterTyped()
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val deleted = ic.deleteSurroundingText(1, 0)
            if (!deleted) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
    }

    private fun handleAction() {
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
        requestHideSelf(0)
    }
}
