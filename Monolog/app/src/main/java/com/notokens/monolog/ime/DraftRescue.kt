package com.notokens.monolog.ime

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RescueDraft(val text: String, val savedAt: Long)

/** Ephemeral drafts. All access is on the main thread and nothing is written to disk. */
internal object DraftRescue {
    private const val LIFETIME = 30 * 60_000L
    private const val BADGE_LIFETIME = 5 * 60_000L
    const val MAX_LENGTH = 100_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val drafts = MutableStateFlow(emptyList<RescueDraft>())
    val entries = drafts.asStateFlow()
    private val unread = MutableStateFlow(false)
    val hasBadge = unread.asStateFlow()
    private var pendingHint = false
    private var badgeUntil = 0L
    var revision = 0
        private set
    private var expiryJob: Job? = null
    private var badgeJob: Job? = null

    fun save(text: String) {
        if (text.isBlank() || text.length > MAX_LENGTH) return
        val now = SystemClock.elapsedRealtime()
        drafts.value = (listOf(RescueDraft(text, now)) + drafts.value
            .filter { it.text != text && now - it.savedAt < LIFETIME }).take(10)
        if (text.length >= 100) {
            pendingHint = true
            unread.value = true
            badgeUntil = now + BADGE_LIFETIME
            badgeJob?.cancel()
            badgeJob = scope.launch {
                delay(BADGE_LIFETIME)
                refresh()
            }
        }
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (drafts.value.isNotEmpty()) {
                val age = SystemClock.elapsedRealtime() - drafts.value.last().savedAt
                delay((LIFETIME - age).coerceAtLeast(1))
                refresh()
            }
            pendingHint = false
        }
    }

    fun takeHint(): Boolean {
        refresh()
        val hint = pendingHint && drafts.value.any { it.text.length >= 100 }
        pendingHint = false
        return hint
    }

    fun available(entry: RescueDraft): Boolean =
        entry in drafts.value && SystemClock.elapsedRealtime() - entry.savedAt < LIFETIME

    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        drafts.value = drafts.value.filter { now - it.savedAt < LIFETIME }
        if (now >= badgeUntil) unread.value = false
    }

    fun clear() {
        revision++
        expiryJob?.cancel()
        badgeJob?.cancel()
        drafts.value = emptyList()
        unread.value = false
        pendingHint = false
        badgeUntil = 0L
    }
}
