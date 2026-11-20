package com.notokens.monolog.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal sealed interface GemmaResult {
    data class Answer(val text: String) : GemmaResult
    data object Missing : GemmaResult
    data object TimedOut : GemmaResult
    data object Empty : GemmaResult
    data class Failed(val loading: Boolean) : GemmaResult

    fun status(): String = when (this) {
        is Answer -> "Gemma returned text"
        Missing -> "Gemma is not downloaded"
        TimedOut -> "Gemma timed out"
        Empty -> "Gemma returned no text"
        is Failed -> if (loading) "Gemma could not start" else "Gemma could not finish the request"
    }
}

internal object LocalAi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var idleClose: Job? = null
    private var lastUsed = 0L
    private val lastRun = MutableStateFlow<String?>(null)
    val lastRunStatus = lastRun.asStateFlow()

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
        val result = request(context, AiText.answerPrompt("Who is Napoleon?"), 48)
        return when (result) {
            is GemmaResult.Answer -> SystemClock.elapsedRealtime() - start
            GemmaResult.TimedOut -> null
            else -> error(result.status())
        }
    }

    suspend fun request(context: Context, prompt: String, maxTokens: Int): GemmaResult {
        val start = SystemClock.elapsedRealtime()
        val result = if (ModelStore.get(context).state.value !is ModelState.Ready) GemmaResult.Missing else {
            withTimeoutOrNull(30_000L) {
                withContext(Dispatchers.Default) {
                    engineLock.withLock {
                        var loading = true
                        try {
                            val activeEngine = getEngine(context)
                            loading = false
                            val config = ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
                                maxOutputToken = maxTokens
                            )
                            val resultText = activeEngine.createConversation(config).use { session ->
                                val output = StringBuilder()
                                session.sendMessageAsync(prompt).collect { message ->
                                    ensureActive()
                                    message.contents.contents.filterIsInstance<Content.Text>()
                                        .forEach { output.append(it.text) }
                                }
                                output.toString().trim()
                            }
                            if (resultText.isBlank()) GemmaResult.Empty else GemmaResult.Answer(resultText)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            GemmaResult.Failed(loading)
                        } catch (_: LinkageError) {
                            GemmaResult.Failed(loading)
                        } finally {
                            scheduleClose()
                        }
                    }
                }
            } ?: GemmaResult.TimedOut
        }
        lastRun.value = "${result.status()} · ${SystemClock.elapsedRealtime() - start} ms"
        return result
    }

    private fun getEngine(context: Context): Engine {
        engine?.let { return it }
        val store = ModelStore.get(context)
        check(store.state.value is ModelState.Ready)
        val cache = File(context.cacheDir, "local-gemma").apply { mkdirs() }
        return Engine(
            EngineConfig(
                modelPath = store.model.path,
                backend = Backend.CPU(threadCount = 2),
                maxNumTokens = 1024,
                cacheDir = cache.path
            )
        ).also {
            try {
                it.initialize()
                engine = it
            } finally {
                if (engine == null) runCatching { it.close() }
            }
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
