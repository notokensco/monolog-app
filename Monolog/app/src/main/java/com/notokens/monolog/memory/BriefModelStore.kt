package com.notokens.monolog.memory

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import com.notokens.monolog.ai.ModelState
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

internal class BriefModelStore private constructor(context: Context) {
    private val model = modelFile(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<ModelState>(
        if (installed(context)) ModelState.Ready else ModelState.Missing
    )
    val state = mutableState.asStateFlow()

    @Synchronized
    fun download() {
        if (!supported || mutableState.value is ModelState.Downloading || mutableState.value is ModelState.Ready) return
        mutableState.value = ModelState.Downloading(0)
        scope.launch {
            val directory = checkNotNull(model.parentFile)
            val partial = File(directory, "$FILENAME.partial")
            try {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException()
                if (StatFs(directory.path).availableBytes < MODEL_BYTES + 8_388_608L) {
                    mutableState.value = ModelState.Failed("Free up at least 126 MB to download the summarizer.")
                    return@launch
                }
                downloadVerified(partial)
                Os.rename(partial.path, model.path)
                mutableState.value = ModelState.Ready
            } catch (cancelled: CancellationException) {
                mutableState.value = ModelState.Missing
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = ModelState.Failed("The summarizer download failed. Try again when connected.")
            } finally {
                partial.delete()
            }
        }
    }

    private suspend fun downloadVerified(partial: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(MODEL_URL).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode != 200) throw IOException()
            var received = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MODEL_BYTES) throw IOException()
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        mutableState.value = ModelState.Downloading((received * 100 / MODEL_BYTES).toInt().coerceAtMost(99))
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (received != MODEL_BYTES || hash != SHA256) throw IOException()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val FILENAME = "journal-summarizer-200m-v1.litertlm"
        private const val MODEL_BYTES = 117_587_968L
        private const val SHA256 = "8b2d4ef09236adb9ead3127325526ba1aa5a59feb7c5de2d3f5958f27479de59"
        private const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/text_summarizer/200m/latest/" +
            "summarization_quant_200m_2modes.litertlm?generation=1786566818453053"
        val supported get() = android.os.Process.is64Bit() &&
            Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        @Volatile private var instance: BriefModelStore? = null

        fun modelFile(context: Context) = File(context.noBackupFilesDir, "models/$FILENAME")

        fun installed(context: Context) = modelFile(context).let { it.isFile && it.length() == MODEL_BYTES }

        fun get(context: Context): BriefModelStore = instance ?: synchronized(this) {
            instance ?: BriefModelStore(context.applicationContext).also { instance = it }
        }
    }
}
