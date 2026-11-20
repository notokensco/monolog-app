package com.notokens.monolog.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RewriteStyle(val label: String, val instruction: String) {
    POLISH("Polish", "Fix grammar and punctuation without changing tone"),
    SHORTEN("Shorten", "Make the text shorter while preserving its meaning"),
    FRIENDLY("Friendly", "Make the text sound friendly and conversational"),
    PROFESSIONAL("Professional", "Make the text sound clear and professional")
}

internal object LocalAi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var idleClose: Job? = null
    private var lastUsed = 0L

    suspend fun warm(context: Context) = withContext(Dispatchers.Default) {
        engineLock.withLock {
            if (ModelStore.get(context).state.value is ModelState.Ready) {
                try {
                    getEngine(context)
                } finally {
                    scheduleClose()
                }
            }
        }
    }

    suspend fun benchmark(context: Context): Long? {
        val start = SystemClock.elapsedRealtime()
        return withTimeoutOrNull(12_000) {
            val prompt = AiText.rewritePrompt("Can you send me the report by Friday?", RewriteStyle.PROFESSIONAL)
            generate(context, prompt, 32)
            SystemClock.elapsedRealtime() - start
        }
    }

    suspend fun generate(context: Context, prompt: String, maxTokens: Int = 100): String =
        withContext(Dispatchers.Default) {
            engineLock.withLock {
                try {
                    val config = ConversationConfig(maxOutputToken = maxTokens)
                    val result = getEngine(context).createConversation(config).use { session ->
                        val output = StringBuilder()
                        session.sendMessageAsync(prompt).collect { message ->
                            ensureActive()
                            output.append(message.toString())
                        }
                        output.toString().trim()
                    }
                    check(result.isNotEmpty())
                    result
                } finally {
                    scheduleClose()
                }
            }
        }

    private fun getEngine(context: Context): Engine {
        engine?.let { return it }
        val store = ModelStore.get(context)
        check(store.state.value is ModelState.Ready)
        val cache = File(context.cacheDir, "local-rewrite").apply { mkdirs() }
        return Engine(
            EngineConfig(
                modelPath = store.model.path,
                backend = Backend.CPU(threadCount = 2),
                maxNumTokens = 1024,
                cacheDir = cache.path
            )
        ).also {
            it.initialize()
            engine = it
        }
    }

    private fun scheduleClose() {
        lastUsed = SystemClock.elapsedRealtime()
        idleClose?.cancel()
        val timestamp = lastUsed
        idleClose = scope.launch {
            delay(IDLE_TIMEOUT)
            engineLock.withLock {
                if (lastUsed == timestamp) closeEngine()
            }
        }
    }

    fun release() {
        scope.launch {
            engineLock.withLock { closeEngine() }
        }
    }

    private fun closeEngine() {
        val previous = engine
        engine = null
        previous?.close()
    }

    private const val IDLE_TIMEOUT = 45_000L
}
