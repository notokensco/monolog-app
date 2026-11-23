package com.notokens.monolog.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class MemoryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("journal_memory", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var explained: Boolean
        get() = prefs.getBoolean("explained", false)
        set(value) { prefs.edit().putBoolean("explained", value).apply() }

    val enabledChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled" || key == null) trySend(enabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(enabled)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
