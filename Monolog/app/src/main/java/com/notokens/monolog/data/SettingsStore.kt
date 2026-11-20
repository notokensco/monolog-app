package com.notokens.monolog.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

class SettingsStore(private val context: Context) {
    private val incognitoKey = booleanPreferencesKey("incognito")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val reduceTransparencyKey = booleanPreferencesKey("reduce_transparency")
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
