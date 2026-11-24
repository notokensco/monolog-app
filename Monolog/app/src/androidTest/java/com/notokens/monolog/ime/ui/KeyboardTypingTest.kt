package com.notokens.monolog.ime.ui

import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ime.*
import com.notokens.monolog.ime.shortcuts.ShortcutDraft
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardTypingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glidesAndAlternateCharactersRespectTogglesWithoutBreakingTaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settings = SettingsStore(context)
        val dictionary = EnglishSuggestions(context)
        val decoder = dictionary.swipeDecoder
        val input = BaseInputConnection(View(context), true)
        val ai = ImeAi(context, scope, settings, dictionary, { input }, { false })
        val alternate = mutableStateOf(true)
        val glide = mutableStateOf(true)
        val typed = mutableListOf<String>()
        val words = mutableListOf<String?>()
        try {
            compose.setContent {
                MonologTheme {
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
                            editorContext = EditorContext.default(), keyboardState = KeyboardState(), voxState = VoxState(),
                            isShortcutsOpen = false, isVoxOpen = false, shortcutDraft = ShortcutDraft(),
                            onDraftChange = {}, onSuggestedShortcut = {}, onToggleShortcuts = {}, onCaptureContext = {},
                            onDismissShortcuts = {}, searchQuery = null, onSearchQueryChange = {}, onOpenSearch = {}, onCloseSearch = {},
                            ai = ai, aiAllowed = false, swipeAllowed = glide.value, language = KeyboardLanguage.of("en"),
                            onOpenSettings = {}, onEnterVox = {}, onExitVox = {}, onStartListening = {}, onPauseListening = {},
                            onDeleteVoxWord = {}, onRequestPermission = {},
                            onCharTyped = { typed.add(it) }, onSwipe = { words.add(decoder.decode(it)) },
                            onBackspace = {}, onSpace = {}, onAction = {}, onShiftClick = {}, onCapsLock = {},
                            onToggleSymbols = {}, onToggleMoreSymbols = {}, onToggleEmoji = {}, onCloseKeyboard = {}, onToggleIncognito = {},
                            longPressCharacters = alternate.value, modifier = Modifier.testTag("keyboard")
                        )
                    }
                }
            }
            compose.onNodeWithText("q").performTouchInput { longClick() }
            compose.onNodeWithText("a").performTouchInput { longClick() }
            compose.onNodeWithText("m").performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(listOf("1", "@", "'"), typed); typed.clear() }
            fun hello() {
                val bounds = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot
                val path = "helo".map { compose.onNodeWithText(it.toString()).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft }
                compose.onNodeWithTag("keyboard").performTouchInput {
                    down(path.first())
                    path.drop(1).forEach { moveTo(it, 100) }
                    up()
                }
            }
            hello()
            compose.runOnIdle { assertEquals(listOf("hello"), words); assertTrue(typed.isEmpty()) }
            compose.runOnIdle { alternate.value = false; glide.value = false }
            compose.onNodeWithText("q").performTouchInput { longClick() }
            hello()
            compose.runOnIdle { assertEquals(listOf("hello"), words); assertFalse(typed.contains("1")); typed.clear() }
            compose.onNodeWithText("a").performTouchInput { click() }
            compose.runOnIdle { assertEquals(listOf("a"), typed) }
            compose.onNodeWithContentDescription("Open emoji picker").assertIsDisplayed()
            compose.onNodeWithContentDescription("Period, incognito off").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test fun typingPreferencesPersistAcrossStoreInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val oldGlide = settings.swipeTyping.first()
        val oldAlternate = settings.longPressCharacters.first()
        try {
            for (enabled in listOf(false, true)) {
                settings.setSwipeTyping(enabled)
                settings.setLongPressCharacters(enabled)
                val reopened = SettingsStore(context)
                assertEquals(enabled, reopened.swipeTyping.first())
                assertEquals(enabled, reopened.longPressCharacters.first())
            }
        } finally {
            settings.setSwipeTyping(oldGlide)
            settings.setLongPressCharacters(oldAlternate)
        }
    }
}
