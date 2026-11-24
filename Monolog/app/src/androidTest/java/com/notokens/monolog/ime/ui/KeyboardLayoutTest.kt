package com.notokens.monolog.ime.ui

import android.graphics.Bitmap
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.ThemeMode
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ime.EnglishSuggestions
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ime.KeyboardState
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestion
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.MonologTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun suggestionsPunctuationAndPrivateModeRemainUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val privateMode = mutableStateOf(false)
        val shortcut = mutableStateOf<ShortcutDraft?>(null)
        val typed = mutableListOf<String>()
        val input = object : BaseInputConnection(View(context), true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = "Remind me to call Sam at 5 pm".takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence? = null
        }
        val ai = ImeAi(context, scope, SettingsStore(context), EnglishSuggestions(context), { input }, { !privateMode.value })
        try {
            compose.setContent {
                MonologTheme(Appearance(theme = if (privateMode.value) ThemeMode.INK else ThemeMode.PAPER)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        MonologKeyboardView(
                            layoutSession = 0, editorActive = true,
                            voxLanguage = com.notokens.monolog.ime.vox.VoxLanguage.resolve(null, "en-IN"),
                            voxLanguages = emptyList(), onVoxLanguage = {},
                            cursorDrag = CursorDrag({}, {}, {}), cursorActive = false, cursorCharacter = null,
                            onAppearance = {}, onEditorCommand = {}, onFloatingBounds = {},
                            translation = remember { KeyboardTranslation(context, scope, { input }) { false } },
                            onOpenVoiceSettings = {}, onEmojiSuggestion = { _, _ -> false }, onPasteImage = { false }, onPasteMedia = { false },
                            onLetterTyped = { typed.add(it) },
                            editorContext = EditorContext.default().copy(isUserIncognito = privateMode.value),
                            keyboardState = KeyboardState(), voxState = VoxState(),
                            isShortcutsOpen = shortcut.value != null, isVoxOpen = false,
                            shortcutDraft = shortcut.value ?: ShortcutDraft(), onDraftChange = { shortcut.value = it },
                            onSuggestedShortcut = { suggestion: ShortcutSuggestion ->
                                shortcut.value = ShortcutDraft(page = suggestion.page,
                                    title = TextFieldValue(suggestion.title, TextRange(suggestion.title.length)), dueAt = suggestion.dueAt)
                            },
                            onToggleShortcuts = { shortcut.value = null }, onCaptureContext = {}, onDismissShortcuts = {},
                            searchQuery = null, onSearchQueryChange = {}, onOpenSearch = {}, onCloseSearch = {},
                            ai = ai, aiAllowed = !privateMode.value, swipeAllowed = !privateMode.value,
                            language = KeyboardLanguage.of("en"), onOpenSettings = {}, onEnterVox = {}, onExitVox = {},
                            onStartListening = {}, onPauseListening = {}, onDeleteVoxWord = {},
                            onRequestPermission = {}, onCharTyped = { typed.add(it) }, onSwipe = {}, onBackspace = {},
                            onSpace = {}, onAction = {}, onShiftClick = {}, onCapsLock = {}, onToggleSymbols = {},
                            onToggleMoreSymbols = {}, onToggleEmoji = {}, onCloseKeyboard = {},
                            onToggleIncognito = { privateMode.value = !privateMode.value }, modifier = Modifier.testTag("keyboard")
                        )
                    }
                }
            }
            compose.runOnIdle { ai.refreshDraft() }
            compose.waitUntil(3_000) { ai.shortcutSuggestion != null }
            compose.onNodeWithText("Set reminder").assertIsDisplayed()
            compose.onNodeWithText("Rewrite").assertIsDisplayed()
            compose.onNodeWithText("Quick shortcuts").assertDoesNotExist()
            compose.onNodeWithContentDescription("Comma").performTouchInput { click() }
            compose.onNodeWithContentDescription("Open emoji picker").assertIsDisplayed()
            compose.onNodeWithContentDescription("Period, incognito off").performTouchInput { click() }
            compose.runOnIdle { assertEquals(listOf(",", "."), typed) }
            capture("keyboard-normal.png", context.getExternalFilesDir(null)!!)
            compose.onNodeWithText("Set reminder").performClick()
            compose.onNodeWithText("New reminder").assertIsDisplayed()
            compose.onNodeWithText("call Sam at 5 pm").assertIsDisplayed()
            compose.onNodeWithContentDescription("Cancel draft").performClick()
            compose.onNodeWithContentDescription("Period, incognito off").performTouchInput { longClick() }
            compose.onNodeWithText("Incognito is on").assertIsDisplayed()
            compose.onNodeWithText("Set reminder").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(",", "."), typed) }
            capture("keyboard-incognito.png", context.getExternalFilesDir(null)!!)
        } finally { scope.cancel() }
    }

    private fun capture(name: String, directory: File) {
        val bitmap = compose.onNodeWithTag("keyboard").captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
