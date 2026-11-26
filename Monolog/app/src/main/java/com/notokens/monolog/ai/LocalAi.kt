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
    data object Busy : GemmaResult
    data class Failed(val loading: Boolean) : GemmaResult

    fun status(): String = when (this) {
        is Answer -> "The offline text model returned text"
        Missing -> "Download an offline text model"
        TimedOut -> "The offline text model timed out"
        Empty -> "The offline text model returned no text"
        Busy -> "The on-device model is already working"
        is Failed -> if (loading) "The offline text model could not start" else "The offline text model could not finish the request"
    }
}

internal object LocalAi {
    const val DEFAULT_CONTEXT_TOKENS = 1024
    const val MAX_CONTEXT_TOKENS = 16_384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var idleClose: Job? = null
    private var lastUsed = 0L
    private var keepWarm = false
    private val lastRun = MutableStateFlow<String?>(null)
    val lastRunStatus = lastRun.asStateFlow()

    suspend fun warm(context: Context, whileVisible: Boolean = false) = withContext(Dispatchers.Default) {
        engineLock.withLock {
            val store = ModelStore.get(context)
            val selection = store.active.value ?: return@withLock
            if (store.state.value !is ModelState.Ready) return@withLock
            if (whileVisible) keepWarm = true
            try {
                if (selection.isGguf) GgufRuntime.warm(store.file(selection))
                else getEngine(context)
            } finally {
                scheduleClose()
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

    suspend fun request(
        context: Context, prompt: String, maxTokens: Int, expectedModel: TextModel? = null,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS, waitForModel: Boolean = true
    ): GemmaResult {
        require(contextTokens in DEFAULT_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS)
        val extended = contextTokens > DEFAULT_CONTEXT_TOKENS
        val timeoutMillis = if (extended) 90_000L else 30_000L
        val start = SystemClock.elapsedRealtime()
        val result = if (ModelStore.get(context).active.value == null) GemmaResult.Missing else {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.Default) {
                    runInference(waitForModel) {
                        val store = ModelStore.get(context)
                        val selection = store.active.value ?: return@runInference GemmaResult.Missing
                        if (expectedModel != null && selection != expectedModel) return@runInference GemmaResult.Missing
                        if (!store.file(selection).isFile) return@runInference GemmaResult.Missing
                        if (selection.isGguf) {
                            return@runInference try {
                                val text = GgufRuntime.complete(store.file(selection), prompt, maxTokens,
                                    contextTokens, timeoutMillis - 2_000L)
                                if (text.isNullOrBlank()) GemmaResult.Empty else GemmaResult.Answer(text)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                GemmaResult.Failed(false)
                            } catch (_: LinkageError) {
                                GemmaResult.Failed(true)
                            } finally {
                                scheduleClose()
                            }
                        }
                        var loading = true
                        var extendedEngine: Engine? = null
                        try {
                            val activeEngine = if (extended) {
                                closeEngine()
                                initializeEngine(context, contextTokens).also { extendedEngine = it }
                            } else getEngine(context)
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
                            runCatching { extendedEngine?.close() }
                            scheduleClose()
                        }
                    }
                }
            } ?: GemmaResult.TimedOut
        }
        lastRun.value = "${result.status()} · ${SystemClock.elapsedRealtime() - start} ms"
        return result
    }

    private suspend fun runInference(waitForModel: Boolean, block: suspend () -> GemmaResult): GemmaResult {
        if (waitForModel) engineLock.lock() else if (!engineLock.tryLock()) return GemmaResult.Busy
        return try { block() } finally { engineLock.unlock() }
    }

    private fun getEngine(context: Context): Engine =
        engine ?: initializeEngine(context, DEFAULT_CONTEXT_TOKENS).also { engine = it }

    private fun initializeEngine(context: Context, contextTokens: Int): Engine {
        val store = ModelStore.get(context)
        check(store.file(TextModel.GEMMA).isFile)
        val cache = File(context.cacheDir, "local-gemma").apply { mkdirs() }
        return Engine(
            EngineConfig(
                modelPath = store.file(TextModel.GEMMA).path,
                backend = Backend.CPU(threadCount = 2),
                maxNumTokens = contextTokens,
                cacheDir = cache.path
            )
        ).also {
            try { it.initialize() }
            catch (failure: Throwable) {
                runCatching { it.close() }
                throw failure
            }
        }
    }

    private fun scheduleClose() {
        lastUsed = SystemClock.elapsedRealtime()
        idleClose?.cancel()
        if (keepWarm) return
        val timestamp = lastUsed
        idleClose = scope.launch {
            delay(IDLE_TIMEOUT)
            engineLock.withLock {
                if (lastUsed == timestamp) closeEngine()
            }
        }
    }

    suspend fun changeModel(swap: () -> Unit) {
        engineLock.withLock {
            idleClose?.cancel()
            closeEngine()
            swap()
        }
    }

    fun release(shouldRelease: () -> Boolean = { true }) {
        scope.launch {
            engineLock.withLock {
                if (!shouldRelease()) return@withLock
                keepWarm = false
                idleClose?.cancel()
                closeEngine()
            }
        }
    }

    private fun closeEngine() {
        val previous = engine
        engine = null
        previous?.close()
        GgufRuntime.release()
    }

    private const val IDLE_TIMEOUT = 45_000L
}
