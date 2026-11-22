package com.notokens.monolog.memory

import android.content.Context

internal class MemoryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("journal_memory", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var explained: Boolean
        get() = prefs.getBoolean("explained", false)
        set(value) { prefs.edit().putBoolean("explained", value).apply() }
}
