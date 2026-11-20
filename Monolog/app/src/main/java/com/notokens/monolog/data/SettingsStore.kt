package com.notokens.monolog.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.monologSettings by preferencesDataStore(name = "monolog_settings")

enum class ThemeMode {
    INK, PAPER, SYSTEM
}

data class Appearance(val theme: ThemeMode = ThemeMode.INK, val reduceTransparency: Boolean = false)

enum class QuickShortcut { REMINDER, ALARM, JOURNAL }

class SettingsStore(private val context: Context) {
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
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val reduceTransparencyKey = booleanPreferencesKey("reduce_transparency")
    private val quickShortcutOrderKey = stringPreferencesKey("quick_shortcut_order")
    private val preferences = context.monologSettings.data

    val incognito: Flow<Boolean> = preferences.map { it[incognitoKey] ?: false }.distinctUntilChanged()

    val appearance: Flow<Appearance> = preferences.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map {
        val theme = when (it[themeModeKey]) {
            "LIGHT", ThemeMode.PAPER.name -> ThemeMode.PAPER
            ThemeMode.SYSTEM.name -> ThemeMode.SYSTEM
            else -> ThemeMode.INK
        }
        Appearance(theme, it[reduceTransparencyKey] ?: false)
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
        context.monologSettings.edit { it[incognitoKey] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.monologSettings.edit { it[themeModeKey] = mode.name }
    }

    suspend fun setReduceTransparency(value: Boolean) {
        context.monologSettings.edit { it[reduceTransparencyKey] = value }
    }
}
