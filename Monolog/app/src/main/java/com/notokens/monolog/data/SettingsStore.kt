package com.notokens.monolog.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

private val Context.monologSettings by preferencesDataStore(name = "monolog_settings")

enum class ThemeMode {
    INK, PAPER, IRIS, OCEAN, TERRACOTTA, PHOTO, SYSTEM
}

data class Appearance(val theme: ThemeMode = ThemeMode.INK, val reduceTransparency: Boolean = false,
    val wallpaperRevision: Long = 0L)

enum class QuickShortcut { REMINDER, ALARM, JOURNAL }

enum class VoiceInputEngine(val id: String, val label: String) {
    NATIVE("native", "Native real-time streaming"),
    MOONSHINE("moonshine", "VOX AI English"),
    REGIONAL("regional", "VOX AI India")
}

class SettingsStore(private val context: Context) {
    private val floatingContextKey = booleanPreferencesKey("floating_context_dock")
    val floatingContextDock = context.monologSettings.data.map { it[floatingContextKey] ?: false }.distinctUntilChanged()
    suspend fun setFloatingContextDock(enabled: Boolean) {
        context.monologSettings.edit { it[floatingContextKey] = enabled }
    }
    private val autocorrectKey = booleanPreferencesKey("autocorrect")
    private val systemSpellCheckerKey = booleanPreferencesKey("system_spell_checker")
    val systemSpellChecker = context.monologSettings.data.map { it[systemSpellCheckerKey] ?: false }.distinctUntilChanged()
    suspend fun setSystemSpellChecker(value: Boolean) { context.monologSettings.edit { it[systemSpellCheckerKey] = value } }
    val autocorrect = context.monologSettings.data.map { it[autocorrectKey] ?: true }.distinctUntilChanged()
    suspend fun setAutocorrect(value: Boolean) { context.monologSettings.edit { it[autocorrectKey] = value } }
    private val swipeTypingKey = booleanPreferencesKey("swipe_typing")
    private val longPressCharactersKey = booleanPreferencesKey("long_press_characters")
    val swipeTyping = context.monologSettings.data.map { it[swipeTypingKey] ?: true }.distinctUntilChanged()
    val longPressCharacters = context.monologSettings.data.map { it[longPressCharactersKey] ?: true }.distinctUntilChanged()
    suspend fun setSwipeTyping(value: Boolean) { context.monologSettings.edit { it[swipeTypingKey] = value } }
    suspend fun setLongPressCharacters(value: Boolean) { context.monologSettings.edit { it[longPressCharactersKey] = value } }
    private val shortcutHoldKey = intPreferencesKey("shortcut_hold_ms")
    private val shortcutHapticsKey = booleanPreferencesKey("shortcut_haptics")
    private val captureBorderColorKey = stringPreferencesKey("capture_border_color")
    val shortcutHoldMs: Flow<Int> = context.monologSettings.data.map { (it[shortcutHoldKey] ?: 500).coerceIn(250, 1500) }.distinctUntilChanged()
    val shortcutHaptics: Flow<Boolean> = context.monologSettings.data.map { it[shortcutHapticsKey] ?: true }.distinctUntilChanged()
    val captureBorderColor: Flow<String> = context.monologSettings.data.map { it[captureBorderColorKey] ?: "#FFB800" }.distinctUntilChanged()
    suspend fun setShortcutHoldMs(value: Int) { context.monologSettings.edit { it[shortcutHoldKey] = value.coerceIn(250, 1500) } }
    suspend fun setShortcutHaptics(value: Boolean) { context.monologSettings.edit { it[shortcutHapticsKey] = value } }
    suspend fun setCaptureBorderColor(value: String) { context.monologSettings.edit { it[captureBorderColorKey] = value } }

    private val incognitoKey = booleanPreferencesKey("incognito")
    private val incognitoUntilKey = longPreferencesKey("incognito_until")
    private val webQuestionSuggestionsKey = booleanPreferencesKey("web_question_suggestions")
    private val webSuggestionsKey = booleanPreferencesKey("web_suggestions")
    private val weatherCityKey = stringPreferencesKey("weather_city")
    private val keyboardLanguageKey = stringPreferencesKey("keyboard_language")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val reduceTransparencyKey = booleanPreferencesKey("reduce_transparency")
    private val wallpaperRevisionKey = longPreferencesKey("theme_wallpaper_revision")
    private val locationWeatherKey = booleanPreferencesKey("location_weather")
    val locationWeather = context.monologSettings.data.map { it[locationWeatherKey] ?: false }.distinctUntilChanged()
    private val voxPauseKey = intPreferencesKey("vox_pause_ms")
    val voxPause = context.monologSettings.data.map { it[voxPauseKey] ?: 3200 }.distinctUntilChanged()
    suspend fun setVoxPause(ms: Int) { context.monologSettings.edit { it[voxPauseKey] = ms.coerceIn(1800, 6000) } }
    suspend fun setLocationWeather(value: Boolean) { context.monologSettings.edit { it[locationWeatherKey] = value } }
    suspend fun activateWallpaper() {
        context.monologSettings.edit {
            it[wallpaperRevisionKey] = System.currentTimeMillis()
            it[themeModeKey] = ThemeMode.PHOTO.name
        }
    }
    private val quickShortcutOrderKey = stringPreferencesKey("quick_shortcut_order")
    private val preferences = context.monologSettings.data
    private val smartToneKey = booleanPreferencesKey("smart_tone")
    private val clipboardKey = booleanPreferencesKey("keyboard_clipboard")
    private val phoneActionsKey = booleanPreferencesKey("keyboard_phone_actions")
    val phoneActions = preferences.map { it[phoneActionsKey] ?: true }.distinctUntilChanged()
    suspend fun setPhoneActions(value: Boolean) { context.monologSettings.edit { it[phoneActionsKey] = value } }
    private val defaultVoxKey = booleanPreferencesKey("keyboard_default_vox")
    private val smartFilterKey = booleanPreferencesKey("vox_smart_filter")
    private val keyBordersKey = booleanPreferencesKey("keyboard_key_borders")
    private val keyPopupsKey = booleanPreferencesKey("keyboard_key_popups")
    private val keyPopupMsKey = intPreferencesKey("keyboard_key_popup_ms")
    private val keyHeightKey = intPreferencesKey("keyboard_height_percent")
    private val sensitiveWordsKey = stringPreferencesKey("keyboard_sensitive_words")
    private val clipboardPinsKey = stringPreferencesKey("keyboard_clipboard_pins")
    private val dashboardExtrasKey = stringSetPreferencesKey("keyboard_dashboard_extras")
    val dashboardExtras = preferences.map { it[dashboardExtrasKey].orEmpty() }.distinctUntilChanged()
    val clipboardPins = preferences.map { decodePins(it[clipboardPinsKey]) }.distinctUntilChanged()
    suspend fun setDashboardExtra(id: String, visible: Boolean) {
        context.monologSettings.edit { values ->
            val items = values[dashboardExtrasKey].orEmpty()
            if (!visible || items.size < 12) values[dashboardExtrasKey] = if (visible) items + id else items - id
        }
    }
    suspend fun setClipboardPin(text: String, pinned: Boolean) {
        context.monologSettings.edit { values ->
            if (values[clipboardKey] != true) return@edit
            val previous = decodePins(values[clipboardPinsKey]).filterNot { it == text }
            val next = if (pinned) (listOf(text) + previous).take(24) else previous
            if (next.isEmpty()) values.remove(clipboardPinsKey)
            else values[clipboardPinsKey] = JournalCipher.seal(org.json.JSONArray(next).toString(), "clipboard-pins")
        }
    }
    private fun decodePins(value: String?): List<String> {
        if (value == null) return emptyList()
        return runCatching {
            val items = org.json.JSONArray(JournalCipher.open(value, "clipboard-pins"))
            List(items.length()) { items.getString(it) }
        }.getOrDefault(emptyList())
    }
    val defaultVox = preferences.map { it[defaultVoxKey] ?: false }.distinctUntilChanged()
    val smartVoxFilter = preferences.map { it[smartFilterKey] ?: true }.distinctUntilChanged()
    val keyBorders = preferences.map { it[keyBordersKey] ?: false }.distinctUntilChanged()
    val keyPopups = preferences.map { it[keyPopupsKey] ?: false }.distinctUntilChanged()
    val keyPopupMs = preferences.map { it[keyPopupMsKey] ?: 70 }.distinctUntilChanged()
    val keyHeightPercent = preferences.map { it[keyHeightKey] ?: 100 }.distinctUntilChanged()
    val sensitiveWords = preferences.map { it[sensitiveWordsKey].orEmpty() }.distinctUntilChanged()
    suspend fun setDefaultVox(value: Boolean) { context.monologSettings.edit { it[defaultVoxKey] = value } }
    suspend fun setSmartVoxFilter(value: Boolean) { context.monologSettings.edit { it[smartFilterKey] = value } }
    suspend fun setKeyBorders(value: Boolean) { context.monologSettings.edit { it[keyBordersKey] = value } }
    suspend fun setKeyPopups(value: Boolean) { context.monologSettings.edit { it[keyPopupsKey] = value } }
    suspend fun setKeyPopupMs(value: Int) { context.monologSettings.edit { it[keyPopupMsKey] = value.coerceIn(0, 250) } }
    suspend fun setKeyHeightPercent(value: Int) { context.monologSettings.edit { it[keyHeightKey] = value.coerceIn(75, 125) } }
    suspend fun setSensitiveWords(value: String) { context.monologSettings.edit { it[sensitiveWordsKey] = value.take(1500) } }
    private val hiddenDashboardKey = stringSetPreferencesKey("hidden_dashboard_tiles")
    val keyboardClipboard = preferences.map { it[clipboardKey] ?: false }.distinctUntilChanged()
    val hiddenDashboardTiles = preferences.map { it[hiddenDashboardKey].orEmpty() }.distinctUntilChanged()
    val keyboardIncognitoUntil = preferences.map { it[incognitoUntilKey] ?: 0L }.distinctUntilChanged()

    suspend fun setKeyboardClipboard(enabled: Boolean) {
        context.monologSettings.edit { it[clipboardKey] = enabled }
    }

    suspend fun setDashboardTileVisible(id: String, visible: Boolean) {
        context.monologSettings.edit {
            val hidden = it[hiddenDashboardKey].orEmpty()
            it[hiddenDashboardKey] = if (visible) hidden - id else hidden + id
        }
    }

    private val personalWordsKey = stringSetPreferencesKey("personal_dictionary")
    private val swapVoxAndAiKey = booleanPreferencesKey("swap_vox_and_ai")
    val swapVoxAndAi = preferences.map { it[swapVoxAndAiKey] ?: false }.distinctUntilChanged()

    suspend fun setSwapVoxAndAi(enabled: Boolean) {
        context.monologSettings.edit { it[swapVoxAndAiKey] = enabled }
    }

    private val voiceInputEngineKey = stringPreferencesKey("voice_input_engine")
    val voiceInputEngine: Flow<VoiceInputEngine> = preferences.map {
        when (it[voiceInputEngineKey]) {
            VoiceInputEngine.MOONSHINE.id, "whisper" -> VoiceInputEngine.MOONSHINE
            VoiceInputEngine.REGIONAL.id -> VoiceInputEngine.REGIONAL
            else -> VoiceInputEngine.NATIVE
        }
    }.distinctUntilChanged()

    suspend fun setVoiceInputEngine(engine: VoiceInputEngine) {
        context.monologSettings.edit { it[voiceInputEngineKey] = engine.id }
    }

    val smartTone = preferences.map { it[smartToneKey] ?: false }.distinctUntilChanged()
    val personalWords = preferences.map { it[personalWordsKey].orEmpty() }.distinctUntilChanged()

    suspend fun setSmartTone(value: Boolean) {
        context.monologSettings.edit { it[smartToneKey] = value }
    }

    suspend fun addPersonalWord(input: String): Boolean {
        val word = input.trim()
        if (word.length !in 2..24 || !Regex("""[\p{L}\p{M}]+(?:['’-][\p{L}\p{M}]+)*""").matches(word)) return false
        var accepted = false
        context.monologSettings.edit {
            val words = it[personalWordsKey].orEmpty()
            val existing = words.firstOrNull { item -> item.equals(word, ignoreCase = true) }
            if (existing != null || words.size < 500) {
                it[personalWordsKey] = (words - setOfNotNull(existing)) + word
                accepted = true
            }
        }
        return accepted
    }

    suspend fun removePersonalWord(word: String) {
        context.monologSettings.edit { it[personalWordsKey] = it[personalWordsKey].orEmpty() - word }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val incognito: Flow<Boolean> = preferences.map {
        (it[incognitoKey] == true) to (it[incognitoUntilKey] ?: 0L)
    }.distinctUntilChanged().transformLatest { (enabled, until) ->
        val remaining = until - System.currentTimeMillis()
        emit(enabled || remaining > 0)
        if (!enabled && remaining > 0) {
            delay(remaining)
            emit(false)
        }
    }.distinctUntilChanged()
    val webQuestionSuggestions = preferences.map { it[webQuestionSuggestionsKey] ?: false }.distinctUntilChanged()
    val webSuggestions = preferences.map { it[webSuggestionsKey] ?: false }.distinctUntilChanged()
    val weatherCity = preferences.map { it[weatherCityKey].orEmpty() }.distinctUntilChanged()
    val keyboardLanguage = preferences.map { it[keyboardLanguageKey] ?: "en" }.distinctUntilChanged()

    suspend fun setWebQuestionSuggestions(value: Boolean) { context.monologSettings.edit { it[webQuestionSuggestionsKey] = value } }
    suspend fun setWebSuggestions(value: Boolean) { context.monologSettings.edit { it[webSuggestionsKey] = value } }
    suspend fun setWeatherCity(value: String) { context.monologSettings.edit { it[weatherCityKey] = value.trim().take(80) } }
    suspend fun setKeyboardLanguage(value: String) { context.monologSettings.edit { it[keyboardLanguageKey] = value } }

    val appearance: Flow<Appearance> = preferences.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map {
        val theme = when (it[themeModeKey]) {
            "LIGHT" -> ThemeMode.PAPER
            else -> ThemeMode.entries.firstOrNull { mode -> mode.name == it[themeModeKey] } ?: ThemeMode.INK
        }
        Appearance(theme, it[reduceTransparencyKey] ?: false, it[wallpaperRevisionKey] ?: 0L)
    }.distinctUntilChanged()

    val quickShortcutOrder: Flow<List<QuickShortcut>> = preferences.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { readQuickShortcutOrder(it[quickShortcutOrderKey]) }.distinctUntilChanged()

    suspend fun moveQuickShortcut(shortcut: QuickShortcut, offset: Int) {
        context.monologSettings.edit { preferences ->
            val order = readQuickShortcutOrder(preferences[quickShortcutOrderKey]).toMutableList()
            val from = order.indexOf(shortcut)
            val to = from + offset
            if (to in order.indices) {
                order.removeAt(from)
                order.add(to, shortcut)
                preferences[quickShortcutOrderKey] = order.joinToString(",") { it.name }
            }
        }
    }

    private fun readQuickShortcutOrder(saved: String?): List<QuickShortcut> {
        val shortcuts = QuickShortcut.entries
        val ordered = saved.orEmpty().split(',').mapNotNull { name -> shortcuts.find { it.name == name } }
        return (ordered + shortcuts).distinct()
    }

    suspend fun setIncognito(value: Boolean) {
        context.monologSettings.edit {
            it[incognitoKey] = value
            it.remove(incognitoUntilKey)
        }
    }

    suspend fun setKeyboardIncognito(value: Boolean) {
        context.monologSettings.edit {
            it[incognitoKey] = false
            if (value) it[incognitoUntilKey] = System.currentTimeMillis() + 15 * 60_000L
            else it.remove(incognitoUntilKey)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.monologSettings.edit { it[themeModeKey] = mode.name }
    }

    suspend fun setReduceTransparency(value: Boolean) {
        context.monologSettings.edit { it[reduceTransparencyKey] = value }
    }
}
