package com.notokens.monolog.ime.vox

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object OfflineMoonshine {
    private const val ARCH = JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
    private val spec = ModelSpec.stt("en", ARCH, false)
    private val downloader by lazy { AssetDownloader() }
    private val modelAccess = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cached: Transcriber? = null

    val available: Boolean get() = Build.SUPPORTED_ABIS.firstOrNull() in
        setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun supports(language: String): Boolean = language.substringBefore('-').equals("en", ignoreCase = true)

    fun removeRetiredModels(context: Context) {
        val root = context.noBackupFilesDir
        for (name in listOf("ggml-small-q5_1.bin", ".ggml-small-q5_1.bin.part", "whisper-tiny-q5_1.bin",
            "moonshine-v2-base.bin", ".moonshine-v2-base.bin.part")) {
            File(root, name).delete()
        }
        File(root, "moonshine-v2-tiny-streaming-en").deleteRecursively()
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "moonshine-v2-small-streaming-en")

    fun ready(context: Context): Boolean {
        val root = directory(context)
        return available && File(root, ".verified").isFile
    }

    suspend fun download(context: Context, onProgress: (Int) -> Unit) = modelAccess.withLock {
        check(available)
        val root = directory(context)
        runInterruptible(Dispatchers.IO) {
            downloader.ensureModelPresent(root, spec) { _, index, total, bytes, size ->
                val fraction = if (size > 0) bytes.toDouble().div(size).coerceIn(0.0, 1.0) else 0.0
                if (total > 0) onProgress(((index - 1 + fraction) * 100 / total).toInt())
            }
            try {
                check(downloader.isModelPresent(root, spec))
                load(context).close()
                File(root, ".verified").writeText("1")
            } catch (failure: Exception) {
                root.deleteRecursively()
                throw failure
            }
        }
        withContext(Dispatchers.Main.immediate) { onProgress(100) }
    }

    suspend fun remove(context: Context) = modelAccess.withLock {
        closeCached()
        withContext(Dispatchers.IO) { directory(context).deleteRecursively() }
    }

    private fun transcriber(context: Context) = cached ?: load(context).also { cached = it }

    suspend fun warm(context: Context) = withContext(Dispatchers.Default) {
        modelAccess.withLock { if (ready(context)) transcriber(context) }
    }

    suspend fun <T> use(context: Context, block: suspend (Transcriber) -> T): T = modelAccess.withLock {
        val engine = transcriber(context)
        try {
            block(engine)
        } catch (failure: Throwable) {
            engine.removeAllListeners()
            closeCached()
            throw failure
        } finally {
            if (cached === engine) engine.removeAllListeners()
        }
    }

    fun release() {
        scope.launch { modelAccess.withLock { closeCached() } }
    }

    private fun closeCached() {
        val engine = cached
        cached = null
        engine?.close()
    }

    private fun load(context: Context): Transcriber = Transcriber().apply {
        try {
            loadFromFiles(directory(context).absolutePath, ARCH)
            setUpdateInterval(0.32)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }
}
