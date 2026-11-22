package com.notokens.monolog.ai

import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

internal enum class TextModel(
    val displayName: String, val bytes: Long, val filename: String,
    val hash: String, val url: String, val licenseUrl: String
) {
    GEMMA("Gemma 3 270M IT", 304_005_120L, "gemma3-270m-it-q8.litertlm",
        "757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63",
        "https://huggingface.co/moonlauncher/moon-models/resolve/bbf238fa1804f295ea05df88502ac97a4732db30/gemma/gemma3-270m-it-q8.litertlm",
        "https://gemma.ai/resources/terms/"),
    QWEN3("Qwen3 0.6B", 396_705_472L, "Qwen3-0.6B-Q4_K_M.gguf",
        "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf",
        "https://huggingface.co/Qwen/Qwen3-0.6B"),
    QWEN35("Qwen3.5 0.8B", 532_517_120L, "Qwen3.5-0.8B-Q4_K_M.gguf",
        "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf",
        "https://huggingface.co/Qwen/Qwen3.5-0.8B");

    val isGguf get() = this != GEMMA
    val downloadSize get() = "${(bytes + 500_000) / 1_000_000} MB"
}

internal class ModelStore private constructor(context: Context) {
    private val directory = File(context.noBackupFilesDir, "models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val marker = File(directory, "active-model")
    fun file(model: TextModel) = File(directory, model.filename)
    private fun valid(model: TextModel) = file(model).let { it.isFile && it.length() == model.bytes }
    private val initial = runCatching { marker.readText() }.getOrNull().let { saved ->
        TextModel.entries.firstOrNull { it.name == saved && valid(it) }
            ?: TextModel.entries.firstOrNull(::valid)
    }
    private val installed = MutableStateFlow(initial)
    val active = installed.asStateFlow()
    private val requested = MutableStateFlow<TextModel?>(null)
    val downloading = requested.asStateFlow()
    private val mutableState = MutableStateFlow<ModelState>(if (initial != null) ModelState.Ready else ModelState.Missing)
    val state = mutableState.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()

    init {
        // Complete deletion if the app was stopped just after a verified switch.
        if (initial != null) TextModel.entries.filter { it != initial }.forEach { file(it).delete() }
    }

    @Synchronized
    fun download(selection: TextModel = TextModel.GEMMA) {
        if (requested.value != null || installed.value == selection) return
        if (selection.isGguf && !GgufRuntime.available) return
        requested.value = selection
        failure.value = null
        mutableState.value = ModelState.Downloading(0)
        scope.launch {
            val partial = File(directory, "${selection.filename}.partial")
            val nextMarker = File(directory, "active-model.partial")
            try {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException()
                if (StatFs(directory.path).availableBytes < selection.bytes + 8_388_608L) {
                    error("Free space for the ${selection.downloadSize} download and try again.")
                }
                downloadVerified(selection, partial)
                LocalAi.changeModel {
                    nextMarker.writeText(selection.name)
                    val verified = file(selection)
                    Os.rename(partial.path, verified.path)
                    try {
                        Os.rename(nextMarker.path, marker.path)
                    } catch (error: Exception) {
                        verified.delete()
                        throw error
                    }
                    installed.value = selection
                    TextModel.entries.filter { it != selection }.forEach { previous ->
                        val obsolete = file(previous)
                        if (obsolete.exists() && !obsolete.delete()) {
                            failure.value = "The previous model could not be removed."
                        }
                    }
                }
                mutableState.value = ModelState.Ready
            } catch (cancelled: CancellationException) {
                mutableState.value = if (installed.value != null) ModelState.Ready else ModelState.Missing
                throw cancelled
            } catch (error: Exception) {
                val message = if (error is IllegalStateException) error.message.orEmpty()
                    else "Could not download the offline model. Check your connection and retry."
                failure.value = message
                mutableState.value = if (installed.value != null) ModelState.Ready else ModelState.Failed(message)
            } finally {
                partial.delete()
                nextMarker.delete()
                requested.value = null
            }
        }
    }

    private suspend fun downloadVerified(selection: TextModel, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(selection.url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode != 200) throw IOException()
            var received = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > selection.bytes) throw IOException()
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val percent = (received * 100 / selection.bytes).toInt().coerceAtMost(99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.value = ModelState.Downloading(percent)
                        }
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(received == selection.bytes && hash == selection.hash) { "Model verification failed. Try again." }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        @Volatile private var instance: ModelStore? = null

        fun get(context: Context): ModelStore = instance ?: synchronized(this) {
            instance ?: ModelStore(context.applicationContext).also { instance = it }
        }
    }
}
