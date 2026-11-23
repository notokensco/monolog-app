package com.notokens.monolog.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Only receives successful Monolog keystrokes, never surrounding host-app text. */
internal class DraftMemoryRecorder(context: Context, scope: CoroutineScope) {
    private data class Draft(val text: String, val app: String, val timestamp: Long)
    private val preferences = MemoryPreferences(context)
    private val companionPackage = context.packageName
    private val repository = MemoryRepository(context)
    private val pending = Channel<Draft>(32)
    private val buffer = StringBuilder()
    private var app = ""
    init {
        scope.launch(Dispatchers.IO) {
            for (draft in pending) {
                try { repository.draft(draft.text, draft.app, draft.timestamp) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { /* Storage failure must not interrupt typing. */ }
            }
        }
    }
    fun start(app: String) { clear(); this.app = app }
    fun committed(text: String, allowed: Boolean) {
        // Companion search questions are not life events. Its saved notes already
        // enter memory through the repository's explicit-save path.
        if (!allowed || !preferences.enabled || app == companionPackage) { clear(); return }
        buffer.append(text)
        if (buffer.length > 800) buffer.delete(0, buffer.length - 800)
    }
    fun backspace(selected: Boolean) {
        if (selected) { clear(); return }
        if (buffer.isNotEmpty()) {
            val start = Character.offsetByCodePoints(buffer, buffer.length, -1)
            buffer.delete(start, buffer.length)
        }
    }
    fun replace(typed: String?, replacement: String, allowed: Boolean) {
        if (!allowed || typed == null || !buffer.endsWith(typed)) { clear(); return }
        buffer.delete(buffer.length - typed.length, buffer.length)
        committed(replacement, allowed)
    }
    fun boundary() {
        if (preferences.enabled && buffer.isNotBlank()) pending.trySend(Draft(buffer.toString(), app, System.currentTimeMillis()))
        buffer.clear()
    }
    fun clear() { buffer.clear() }
}
