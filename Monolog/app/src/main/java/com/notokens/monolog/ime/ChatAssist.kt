package com.notokens.monolog.ime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ChatSession(
    val target: String,
    val goal: String,
    val paused: Boolean = false,
    val busy: Boolean = false,
    val reply: String = "",
    val coaching: String = "",
    val status: String = "Return to your chat and open Monolog."
)

internal object ChatAssist {
    private val current = MutableStateFlow<ChatSession?>(null)
    val session = current.asStateFlow()
    var editor: String? = null
        private set
    var revision = 0
        private set
    private val error = MutableStateFlow<String?>(null)
    val failure = error.asStateFlow()

    fun start(target: String, goal: String) {
        error.value = null
        revision++
        current.value = ChatSession(target, goal)
    }

    fun setEditor(target: String?) {
        if (target == editor) return
        editor = target
        revision++
        update {
            if (target == it.target) it
            else it.copy(reply = "", coaching = "", busy = false, status = "Return to the shared chat.")
        }
    }

    fun pause() {
        revision++
        update {
            it.copy(paused = !it.paused, busy = false, reply = "", coaching = "",
                status = if (it.paused) "Waiting for chat text…" else "Paused")
        }
    }

    fun stop(message: String? = null) {
        error.value = message
        revision++
        current.value = null
    }

    fun update(change: (ChatSession) -> ChatSession) {
        current.value = current.value?.let(change)
    }
}
