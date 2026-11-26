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
import com.notokens.monolog.ime.ui.DashboardTile
import java.io.IOException
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

enum class KeyboardPlacement(val label: String) {
    FULL("Full width"), LEFT("Left hand"), RIGHT("Right hand"), SPLIT("Split"), FLOATING("Floating")
}

enum class QuickShortcut { REMINDER, ALARM, JOURNAL }

internal enum class KeyboardTypeface(val label: String) {
    MONOLOG("Monolog Default"), GEIST("Geist"), SYSTEM("System default")
}

internal enum class KeyTapSound(val label: String) {
    OFF("Off"), TICK("Tick"), TYPEWRITER("Typewriter"), CUSTOM("Custom sound")
}

internal enum class KeyboardOption(val label: String, val default: Boolean = true) {
    WORD_STRIP("Word suggestions"),
    EMOJI("Emoji suggestions"),
    GIF("GIF shortcuts in suggestions"),
    STICKER("Sticker shortcuts in suggestions"),
    GLIDE_DELETE("Swipe left from Backspace to delete words"),
    COPIED("Show copied items in suggestions"),
    GRAMMAR("Polish suggestions"),
    NUMBERS("Number row"),
    PASSWORD_NUMBERS("Number row in password fields"),
    PERIOD("Show period key"),
    COMMA("Show comma key");

    val key = booleanPreferencesKey("keyboard_option_" + name.lowercase(java.util.Locale.ROOT))
}

enum class VoiceInputEngine(val id: String, val label: String) {
    NATIVE("native", "Native real-time streaming"),
    MOONSHINE("moonshine", "VOX AI English"),
    REGIONAL("regional", "VOX AI India")
}

class SettingsStore(private val context: Context) {
    private val profileNameKey = stringPreferencesKey("personal_display_name")
    private val profileNameTimeKey = longPreferencesKey("personal_display_name_updated_at")
    private val localSignInKey = longPreferencesKey("web_connect_local_signed_in")
    private val driveSignInKey = longPreferencesKey("web_connect_drive_signed_in")

    internal val personalProfile = context.monologSettings.data.map {
        PersonalProfile(it[profileNameKey].orEmpty(), it[profileNameTimeKey] ?: 0L,
            it[localSignInKey] ?: 0L, it[driveSignInKey] ?: 0L)
    }.distinctUntilChanged()

    internal suspend fun setProfileName(name: String) {
        val clean = limitProfileName(name.trim()).trimEnd()
        context.monologSettings.edit {
            if (it[profileNameKey].orEmpty() != clean) {
                it[profileNameKey] = clean
                it[profileNameTimeKey] = System.currentTimeMillis()
            }
        }
    }

    internal suspend fun recordBrowserSignIn(local: Boolean, time: Long) {
        val key = if (local) localSignInKey else driveSignInKey
        context.monologSettings.edit { if (time > (it[key] ?: 0L)) it[key] = time }
    }
    private val typefaceKey = stringPreferencesKey("keyboard_typeface")
    private val keyFontSizeKey = intPreferencesKey("keyboard_font_percent")
    private val emojiColumnsKey = intPreferencesKey("keyboard_emoji_columns")
    private val tapSoundKey = stringPreferencesKey("keyboard_tap_sound")
    private val tapSoundRevisionKey = longPreferencesKey("keyboard_tap_sound_revision")
    internal val keyboardTypeface = context.monologSettings.data.map { values ->
        KeyboardTypeface.entries.firstOrNull { it.name == values[typefaceKey] } ?: KeyboardTypeface.MONOLOG
    }.distinctUntilChanged()
    internal val keyboardFontSize = context.monologSettings.data.map { (it[keyFontSizeKey] ?: 100).coerceIn(80, 130) }.distinctUntilChanged()
    internal val emojiColumns = context.monologSettings.data.map { (it[emojiColumnsKey] ?: 8).coerceIn(5, 10) }.distinctUntilChanged()
    internal val keyTapSound = context.monologSettings.data.map { values ->
        KeyTapSound.entries.firstOrNull { it.name == values[tapSoundKey] } ?: KeyTapSound.OFF
    }.distinctUntilChanged()
    internal val keyTapSoundRevision = context.monologSettings.data.map { it[tapSoundRevisionKey] ?: 0L }.distinctUntilChanged()
    internal suspend fun setKeyboardTypeface(value: KeyboardTypeface) { context.monologSettings.edit { it[typefaceKey] = value.name } }
    internal suspend fun setKeyboardFontSize(value: Int) { context.monologSettings.edit { it[keyFontSizeKey] = value.coerceIn(80, 130) } }
    internal suspend fun setEmojiColumns(value: Int) { context.monologSettings.edit { it[emojiColumnsKey] = value.coerceIn(5, 10) } }
    internal suspend fun setKeyTapSound(value: KeyTapSound) {
        context.monologSettings.edit {
            it[tapSoundKey] = value.name
            if (value == KeyTapSound.CUSTOM) it[tapSoundRevisionKey] = System.currentTimeMillis()
        }
    }
    internal val keyboardOptions = context.monologSettings.data.map { values ->
        KeyboardOption.entries.filterTo(mutableSetOf()) { values[it.key] ?: it.default }
    }.distinctUntilChanged()
    internal suspend fun setKeyboardOption(option: KeyboardOption, enabled: Boolean) {
        context.monologSettings.edit { it[option.key] = enabled }
    }
    private val autoCapKey = booleanPreferencesKey("keyboard_auto_capitalize")
    private val learningKey = booleanPreferencesKey("keyboard_learn_words")
    private val typingProfileKey = stringPreferencesKey("keyboard_typing_profile")
    private val statsDeviceKey = stringPreferencesKey("keyboard_stats_device")
    val autoCapitalize = context.monologSettings.data.map { it[autoCapKey] ?: false }.distinctUntilChanged()
    val learnWords = context.monologSettings.data.map { it[learningKey] ?: true }.distinctUntilChanged()
    internal val typingProfile = context.monologSettings.data.map { it[typingProfileKey] }
        .distinctUntilChanged().map(::decodeTypingProfile).flowOn(Dispatchers.Default)

    suspend fun setAutoCapitalize(enabled: Boolean) { context.monologSettings.edit { it[autoCapKey] = enabled } }
    suspend fun setLearnWords(enabled: Boolean) { context.monologSettings.edit { it[learningKey] = enabled } }

    internal suspend fun deleteKeyboardLearning() {
        context.monologSettings.edit { values ->
            val profile = readTypingProfile(values)
            values.remove(personalWordsKey)
            values[typingProfileKey] = JournalCipher.seal(
                profile.copy(words = emptyMap(), transitions = emptyMap()).encode().toString(), "keyboard-typing-profile")
        }
    }

    internal suspend fun resetLocalContent() {
        var screenshotFolder: String? = null
        context.monologSettings.edit { values ->
            values.remove(typingProfileKey)
            values.remove(personalWordsKey)
            values.remove(clipboardPinsKey)
            values.remove(clipboardKey)
            screenshotFolder = values[screenshotFolderKey]
            values.remove(screenshotFolderKey)
            values.remove(recentEmojiKey)
            values.remove(recentGifSearchKey)
            values.remove(recentStickerSearchKey)
            values.remove(incognitoUntilKey)
            values.remove(draftRescueKey)
        }
        screenshotFolder?.let { folder ->
            runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(folder),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.getSharedPreferences("recent_screenshot_suggestions", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun readTypingProfile(values: Preferences): TypingProfile = decodeTypingProfile(values[typingProfileKey])

    private fun decodeTypingProfile(cipher: String?): TypingProfile {
        if (cipher == null) return TypingProfile()
        return TypingProfile.decode(JSONObject(JournalCipher.open(cipher, "keyboard-typing-profile")))
    }

    internal suspend fun recordTyping(tokens: Map<String, Int>, counters: Map<TypingMetric, Long>,
        transitions: Map<String, Int>) {
        if (tokens.isEmpty() && counters.isEmpty() && transitions.isEmpty()) return
        context.monologSettings.edit { values ->
            val profile = readTypingProfile(values)
            val device = values[statsDeviceKey] ?: UUID.randomUUID().toString().also { values[statsDeviceKey] = it }
            val counts = profile.usage[device].orEmpty().toMutableMap()
            counters.forEach { (metric, delta) -> counts[metric] = ((counts[metric] ?: 0L) + delta).coerceAtMost(1_000_000_000_000L) }
            val words = profile.words.toMutableMap()
            val pairs = profile.transitions.toMutableMap()
            if (values[learningKey] != false) {
                val now = System.currentTimeMillis()
                tokens.forEach { (input, count) ->
                    val word = dictionaryWord(input) ?: return@forEach
                    val key = wordKey(word)
                    val previous = words[key]
                    words[key] = LearnedWord(previous?.text ?: word, ((previous?.uses ?: 0) + count).coerceAtMost(9999),
                        maxOf(now, (previous?.at ?: 0L) + 1))
                }
                for ((pair, count) in transitions) {
                    val previous = pairs[pair]
                    pairs[pair] = LearnedTransition(((previous?.uses ?: 0) + count).coerceAtMost(255),
                        maxOf(now, (previous?.at ?: 0L) + 1))
                }
            }
            val updated = TypingProfile(TypingProfile.limit(words), profile.usage + (device to counts),
                TypingProfile.limitTransitions(pairs))
            values[typingProfileKey] = JournalCipher.seal(updated.encode().toString(), "keyboard-typing-profile")
        }
    }

    private val autocorrectKey = booleanPreferencesKey("autocorrect")
    private val systemSpellCheckerKey = booleanPreferencesKey("system_spell_checker")
    val systemSpellChecker = context.monologSettings.data.map { it[systemSpellCheckerKey] ?: false }.distinctUntilChanged()
    suspend fun setSystemSpellChecker(value: Boolean) { context.monologSettings.edit { it[systemSpellCheckerKey] = value } }
    val autocorrect = context.monologSettings.data.map { it[autocorrectKey] ?: true }.distinctUntilChanged()
    suspend fun setAutocorrect(value: Boolean) { context.monologSettings.edit { it[autocorrectKey] = value } }
    private val swipeTypingKey = booleanPreferencesKey("swipe_typing")
    private val swipeTrailKey = intPreferencesKey("swipe_trail_dp")
    val swipeTrail = context.monologSettings.data.map { it[swipeTrailKey] ?: 120 }.distinctUntilChanged()
    suspend fun setSwipeTrail(value: Int) {
        context.monologSettings.edit { it[swipeTrailKey] = value.coerceIn(0, 240) }
    }
    private val longPressCharactersKey = booleanPreferencesKey("long_press_characters")
    val swipeTyping = context.monologSettings.data.map { it[swipeTypingKey] ?: true }.distinctUntilChanged()
    val longPressCharacters = context.monologSettings.data.map { it[longPressCharactersKey] ?: true }.distinctUntilChanged()
    suspend fun setSwipeTyping(value: Boolean) { context.monologSettings.edit { it[swipeTypingKey] = value } }
    suspend fun setLongPressCharacters(value: Boolean) { context.monologSettings.edit { it[longPressCharactersKey] = value } }
    private val keyLongPressMsKey = intPreferencesKey("key_long_press_ms")
    val keyLongPressMs: Flow<Int> = context.monologSettings.data.map { (it[keyLongPressMsKey] ?: 350).coerceIn(150, 1000) }.distinctUntilChanged()
    suspend fun setKeyLongPressMs(value: Int) { context.monologSettings.edit { it[keyLongPressMsKey] = value.coerceIn(150, 1000) } }
    private val shortcutHoldKey = intPreferencesKey("shortcut_hold_ms")
    private val shortcutHapticsKey = booleanPreferencesKey("shortcut_haptics")
    private val vibrationHapticsKey = booleanPreferencesKey("vibration_haptics")
    private val captureBorderColorKey = stringPreferencesKey("capture_border_color")
    private val tapOpensShortcutsKey = booleanPreferencesKey("tap_opens_shortcuts")
    val tapOpensShortcuts = context.monologSettings.data.map { it[tapOpensShortcutsKey] ?: false }.distinctUntilChanged()
    suspend fun setTapOpensShortcuts(value: Boolean) {
        context.monologSettings.edit { it[tapOpensShortcutsKey] = value }
    }

    val shortcutHoldMs: Flow<Int> = context.monologSettings.data.map { (it[shortcutHoldKey] ?: 500).coerceIn(250, 1500) }.distinctUntilChanged()
    val shortcutHaptics: Flow<Boolean> = context.monologSettings.data.map { it[shortcutHapticsKey] ?: true }.distinctUntilChanged()
    val vibrationHaptics: Flow<Boolean> = context.monologSettings.data.map { it[vibrationHapticsKey] ?: true }.distinctUntilChanged()
    val captureBorderColor: Flow<String> = context.monologSettings.data.map { it[captureBorderColorKey] ?: "#FFB800" }.distinctUntilChanged()
    suspend fun setShortcutHoldMs(value: Int) { context.monologSettings.edit { it[shortcutHoldKey] = value.coerceIn(250, 1500) } }
    suspend fun setShortcutHaptics(value: Boolean) { context.monologSettings.edit { it[shortcutHapticsKey] = value } }
    suspend fun setVibrationHaptics(value: Boolean) { context.monologSettings.edit { it[vibrationHapticsKey] = value } }
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
    private val screenshotFolderKey = stringPreferencesKey("keyboard_screenshot_folder")
    internal val screenshotFolder = preferences.map { it[screenshotFolderKey] }.distinctUntilChanged()
    internal suspend fun setScreenshotFolder(uri: String?) {
        context.monologSettings.edit { if (uri == null) it.remove(screenshotFolderKey) else it[screenshotFolderKey] = uri }
    }
    private val draftRescueKey = booleanPreferencesKey("keyboard_draft_rescue")
    val draftRescue = preferences.map { it[draftRescueKey] ?: false }.distinctUntilChanged()
    suspend fun setDraftRescue(enabled: Boolean) { context.monologSettings.edit { it[draftRescueKey] = enabled } }
    private val phoneActionsKey = booleanPreferencesKey("keyboard_phone_actions")
    val phoneActions = preferences.map { it[phoneActionsKey] ?: true }.distinctUntilChanged()
    suspend fun setPhoneActions(value: Boolean) { context.monologSettings.edit { it[phoneActionsKey] = value } }
    private val defaultVoxKey = booleanPreferencesKey("keyboard_default_vox")
    private val smartFilterKey = booleanPreferencesKey("vox_smart_filter")
    private val recentEmojiKey = stringPreferencesKey("keyboard_recent_emoji")
    val recentEmoji = preferences.map { it[recentEmojiKey].orEmpty() }.distinctUntilChanged()
    suspend fun rememberEmoji(text: String, label: String) {
        context.monologSettings.edit { prefs ->
            val previous = prefs[recentEmojiKey].orEmpty().lineSequence().filter(String::isNotBlank)
            prefs[recentEmojiKey] = (sequenceOf("$text\t$label") + previous.filter { it.substringBefore('\t') != text })
                .take(36).joinToString("\n")
        }
    }
    suspend fun clearRecentEmoji() {
        context.monologSettings.edit { it.remove(recentEmojiKey) }
    }

    private val cursorOnSpaceKey = booleanPreferencesKey("keyboard_cursor_on_space")
    val cursorOnSpace = preferences.map { it[cursorOnSpaceKey] ?: false }.distinctUntilChanged()
    suspend fun setCursorOnSpace(value: Boolean) { context.monologSettings.edit { it[cursorOnSpaceKey] = value } }

    private val voxLanguageKey = stringPreferencesKey("vox_regional_language")
    val voxLanguage = preferences.map { it[voxLanguageKey] }.distinctUntilChanged()
    suspend fun setVoxLanguage(code: String) { context.monologSettings.edit { it[voxLanguageKey] = code } }

    private val keyBordersKey = booleanPreferencesKey("keyboard_key_borders")
    private val onlineMediaKey = booleanPreferencesKey("keyboard_online_media")
    val onlineMedia = preferences.map { it[onlineMediaKey] ?: false }.distinctUntilChanged()
    suspend fun setOnlineMedia(enabled: Boolean) { context.monologSettings.edit { it[onlineMediaKey] = enabled } }
    private val recentGifSearchKey = stringPreferencesKey("keyboard_recent_gif_searches")
    private val recentStickerSearchKey = stringPreferencesKey("keyboard_recent_sticker_searches")

    private fun mediaSearchKey(stickers: Boolean) = if (stickers) recentStickerSearchKey else recentGifSearchKey

    fun recentMediaSearches(stickers: Boolean) = preferences.map { it[mediaSearchKey(stickers)] }
        .distinctUntilChanged().map(::decodeMediaSearches).flowOn(Dispatchers.Default)

    suspend fun rememberMediaSearch(stickers: Boolean, search: String) {
        val query = search.trim().take(80)
        if (query.isBlank()) return
        val key = mediaSearchKey(stickers)
        context.monologSettings.edit { values ->
            if (values[onlineMediaKey] != true) return@edit
            val recent = decodeMediaSearches(values[key]).filterNot { it.equals(query, ignoreCase = true) }
            values[key] = JournalCipher.seal(org.json.JSONArray((listOf(query) + recent).take(10)).toString(),
                "keyboard-media-history")
        }
    }

    suspend fun clearRecentMediaSearches(stickers: Boolean) {
        context.monologSettings.edit { it.remove(mediaSearchKey(stickers)) }
    }

    private fun decodeMediaSearches(value: String?): List<String> = if (value == null) emptyList() else runCatching {
        val history = org.json.JSONArray(JournalCipher.open(value, "keyboard-media-history"))
        (0 until minOf(history.length(), 10)).mapNotNull {
            (history.opt(it) as? String)?.takeIf { search -> search.isNotBlank() && search.length <= 80 }
        }
    }.getOrDefault(emptyList())

    private val compactKeysKey = booleanPreferencesKey("keyboard_compact_keys")
    private val placementKey = stringPreferencesKey("keyboard_placement")
    val compactKeys = preferences.map { it[compactKeysKey] ?: false }.distinctUntilChanged()
    val keyboardPlacement = preferences.map { values ->
        KeyboardPlacement.entries.firstOrNull { it.name == values[placementKey] } ?: KeyboardPlacement.FULL
    }.distinctUntilChanged()
    suspend fun setCompactKeys(enabled: Boolean) { context.monologSettings.edit { it[compactKeysKey] = enabled } }
    suspend fun setKeyboardPlacement(value: KeyboardPlacement) {
        context.monologSettings.edit { it[placementKey] = value.name }
    }
    private val keyPopupsKey = booleanPreferencesKey("keyboard_key_popups")
    private val keyPopupMsKey = intPreferencesKey("keyboard_key_popup_ms")
    private val keyHeightKey = intPreferencesKey("keyboard_height_percent")
    private val sensitiveWordsKey = stringPreferencesKey("keyboard_sensitive_words")
    private val clipboardPinsKey = stringPreferencesKey("keyboard_clipboard_pins")
    private val dashboardExtrasKey = stringSetPreferencesKey("keyboard_dashboard_extras")
    private val dashboardOrderKey = stringPreferencesKey("keyboard_dashboard_order")
    private val dashboardPinsKey = stringPreferencesKey("keyboard_dashboard_pins")
    private val hiddenDashboardKey = stringSetPreferencesKey("hidden_dashboard_tiles")
    internal val dashboardTiles = preferences.map { configuredDashboard(it).take(12) }.distinctUntilChanged()
    val dashboardPins = preferences.map { dashboardIds(it[dashboardPinsKey]).take(4) }.distinctUntilChanged()

    suspend fun setDashboardOrder(ids: List<String>) {
        context.monologSettings.edit { values ->
            val configured = configuredDashboard(values)
            if (values[dashboardExtrasKey] == null && values[hiddenDashboardKey] == null) {
                saveDashboardMembership(values, configured)
            }
            val remaining = configured.map { it.name }.filterNot { it in ids }
            values[dashboardOrderKey] = (ids + remaining).distinct().joinToString(",")
        }
    }

    suspend fun toggleDashboardPin(id: String) {
        context.monologSettings.edit { values ->
            val configured = configuredDashboard(values)
            if (values[dashboardExtrasKey] == null && values[hiddenDashboardKey] == null) {
                saveDashboardMembership(values, configured)
                values[dashboardOrderKey] = configured.joinToString(",") { it.name }
            }
            val pins = dashboardIds(values[dashboardPinsKey]).take(4)
            values[dashboardPinsKey] = when {
                id in pins -> pins - id
                pins.size < 4 -> pins + id
                else -> pins
            }.joinToString(",")
        }
    }

    private fun dashboardIds(value: String?) = value.orEmpty().split(',').filter(String::isNotBlank).distinct()

    val clipboardPins = preferences.map { decodePins(it[clipboardPinsKey]) }.distinctUntilChanged()
    private fun configuredDashboard(values: Preferences): List<DashboardTile> {
        if (!values.contains(dashboardExtrasKey) && !values.contains(hiddenDashboardKey) &&
            !values.contains(dashboardOrderKey) && !values.contains(dashboardPinsKey)) return DashboardTile.defaults
        val extras = values[dashboardExtrasKey].orEmpty()
        val hidden = values[hiddenDashboardKey].orEmpty()
        val visible = DashboardTile.entries.filter { if (it.extra) it.name in extras else it.name !in hidden }
        return (dashboardIds(values[dashboardOrderKey]).mapNotNull { id -> visible.find { it.name == id } } + visible).distinct()
    }

    private fun saveDashboardMembership(values: androidx.datastore.preferences.core.MutablePreferences, tiles: List<DashboardTile>) {
        values[dashboardExtrasKey] = tiles.filter { it.extra }.mapTo(hashSetOf()) { it.name }
        values[hiddenDashboardKey] = DashboardTile.entries.filter { !it.extra && it !in tiles }.mapTo(hashSetOf()) { it.name }
    }

    internal suspend fun setDashboardTileVisible(tile: DashboardTile, visible: Boolean) {
        context.monologSettings.edit { values ->
            val current = configuredDashboard(values)
            if (visible && (tile in current || current.size >= 12)) return@edit
            val next = if (visible) current + tile else current - tile
            saveDashboardMembership(values, next)
            values[dashboardOrderKey] = next.joinToString(",") { it.name }
            if (!visible) values[dashboardPinsKey] =
                (dashboardIds(values[dashboardPinsKey]) - tile.name).joinToString(",")
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
    val keyPopups = preferences.map { it[keyPopupsKey] ?: true }.distinctUntilChanged()
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
    val keyboardClipboard = preferences.map { it[clipboardKey] ?: false }.distinctUntilChanged()
    val keyboardIncognitoUntil = preferences.map { it[incognitoUntilKey] ?: 0L }.distinctUntilChanged()

    suspend fun setKeyboardClipboard(enabled: Boolean) {
        context.monologSettings.edit { it[clipboardKey] = enabled }
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
        val word = dictionaryWord(input) ?: return false
        var accepted = false
        context.monologSettings.edit {
            val words = it[personalWordsKey].orEmpty()
            val existing = words.firstOrNull { item -> item.equals(word, ignoreCase = true) }
            if (existing != null || words.size < 500) {
                it[personalWordsKey] = (words - setOfNotNull(existing)) + word
                val profile = readTypingProfile(it)
                val key = wordKey(word)
                val updated = if (profile.words[key]?.uses == 0) profile.copy(words = profile.words - key) else profile
                if (updated != profile) it[typingProfileKey] =
                    JournalCipher.seal(updated.encode().toString(), "keyboard-typing-profile")
                accepted = true
            }
        }
        return accepted
    }

    suspend fun removePersonalWord(word: String) {
        val text = dictionaryWord(word) ?: return
        val key = wordKey(text)
        context.monologSettings.edit { values ->
            values[personalWordsKey] = values[personalWordsKey].orEmpty().filterNot { wordKey(it) == key }.toSet()
            val profile = readTypingProfile(values)
            val at = maxOf(System.currentTimeMillis(), (profile.words[key]?.at ?: 0L) + 1)
            val words = TypingProfile.limit(profile.words + (key to LearnedWord(text, 0, at)))
            val transitions = profile.transitions.filterKeys { it.substringAfter(' ') != key && it.substringBefore(' ') != key }
            values[typingProfileKey] = JournalCipher.seal(profile.copy(words = words, transitions = transitions)
                .encode().toString(), "keyboard-typing-profile")
        }
    }

    private val portableBooleans get() = listOf(autoCapKey, learningKey, autocorrectKey, swipeTypingKey,
        systemSpellCheckerKey, longPressCharactersKey, shortcutHapticsKey, vibrationHapticsKey, tapOpensShortcutsKey,
        smartToneKey, clipboardKey, phoneActionsKey, defaultVoxKey, smartFilterKey, cursorOnSpaceKey,
        keyBordersKey, compactKeysKey, keyPopupsKey, swapVoxAndAiKey, reduceTransparencyKey) +
        KeyboardOption.entries.map { it.key }
    private val portableIntegers get() = listOf(swipeTrailKey, keyLongPressMsKey, shortcutHoldKey,
        keyPopupMsKey, keyHeightKey, voxPauseKey, keyFontSizeKey, emojiColumnsKey)
    private val portableStrings get() = listOf(keyboardLanguageKey, themeModeKey, placementKey,
        dashboardOrderKey, dashboardPinsKey, voiceInputEngineKey, voxLanguageKey, quickShortcutOrderKey, sensitiveWordsKey, typefaceKey)
    private val portableSets get() = listOf(dashboardExtrasKey, hiddenDashboardKey, personalWordsKey)

    internal suspend fun exportKeyboard(): JSONObject {
        val values = preferences.first()
        return JSONObject().put("profile", readTypingProfile(values).encode()).put("settings", JSONObject().apply {
            put(profileNameKey.name, values[profileNameKey].orEmpty())
            put(profileNameTimeKey.name, values[profileNameTimeKey] ?: 0L)
            portableBooleans.forEach { put(it.name, values[it] ?: JSONObject.NULL) }
            portableIntegers.forEach { put(it.name, values[it] ?: JSONObject.NULL) }
            portableStrings.forEach { put(it.name, values[it] ?: JSONObject.NULL) }
            portableSets.forEach { put(it.name, org.json.JSONArray(values[it].orEmpty().toList())) }
        })
    }

    internal suspend fun importKeyboard(snapshot: JSONObject, stillAuthorized: () -> Unit) {
        val remote = TypingProfile.decode(snapshot.getJSONObject("profile"))
        val saved = snapshot.getJSONObject("settings")
        context.monologSettings.edit { values ->
            stillAuthorized()
            val nameTime = saved.optLong(profileNameTimeKey.name)
            val savedName = saved.opt(profileNameKey.name) as? String
            if (savedName != null && nameTime > (values[profileNameTimeKey] ?: 0L)) {
                values[profileNameKey] = limitProfileName(savedName.trim()).trimEnd()
                values[profileNameTimeKey] = nameTime
            }
            val profile = readTypingProfile(values).merge(remote)
            portableBooleans.forEach { key ->
                when (val value = saved.opt(key.name)) {
                    is Boolean -> values[key] = value
                    JSONObject.NULL -> values.remove(key)
                }
            }
            portableIntegers.forEach { key ->
                when (val value = saved.opt(key.name)) {
                    is Int -> values[key] = value
                    JSONObject.NULL -> values.remove(key)
                }
            }
            portableStrings.forEach { key ->
                when (val value = saved.opt(key.name)) {
                    is String -> if (value.length <= 1500 && !(key == themeModeKey && value == ThemeMode.PHOTO.name)) values[key] = value
                    JSONObject.NULL -> values.remove(key)
                }
            }
            portableSets.forEach { key ->
                val list = saved.optJSONArray(key.name) ?: return@forEach
                val items = (0 until minOf(500, list.length())).mapNotNull { list.opt(it) as? String }
                if (key == personalWordsKey) {
                    values[key] = (values[key].orEmpty() + items.mapNotNull(::dictionaryWord))
                        .filter { profile.words[wordKey(it)]?.uses != 0 }.distinctBy(::wordKey).take(500).toSet()
                } else values[key] = items.filter { it.length <= 40 }.toSet()
            }
            values[typingProfileKey] = JournalCipher.seal(profile.encode().toString(), "keyboard-typing-profile")
        }
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
