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

internal class ModelStore private constructor(context: Context) {
    private val directory = File(context.noBackupFilesDir, "models")
    val model = File(directory, "$REVISION.litertlm")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<ModelState>(
        if (model.isFile && model.length() == SIZE) ModelState.Ready else ModelState.Missing
    )
    val state = mutableState.asStateFlow()

    @Synchronized
    fun download() {
        if (mutableState.value is ModelState.Downloading || mutableState.value is ModelState.Ready) return
        mutableState.value = ModelState.Downloading(0)
        scope.launch {
            val temporary = File(directory, "$REVISION.partial")
            try {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException()
                if (StatFs(directory.path).availableBytes < SIZE + 8_388_608L) {
                    mutableState.value = ModelState.Failed("Free up at least 155 MB and try again.")
                    return@launch
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val connection = (URL(MODEL_URL).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    setRequestProperty("Accept-Encoding", "identity")
                }
                try {
                    if (connection.responseCode != 200) throw IOException()
                    var received = 0L
                    var lastPercent = -1
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > SIZE) throw IOException()
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val percent = (received * 100 / SIZE).toInt().coerceAtMost(99)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    mutableState.value = ModelState.Downloading(percent)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (received != SIZE || hash != SHA256) {
                        mutableState.value = ModelState.Failed("The download could not be verified. Try again.")
                        return@launch
                    }
                    Os.rename(temporary.path, model.path)
                    mutableState.value = ModelState.Ready
                } finally {
                    connection.disconnect()
                }
            } catch (error: CancellationException) {
                mutableState.value = ModelState.Missing
                throw error
            } catch (_: Exception) {
                mutableState.value = ModelState.Failed("Could not download the offline model. Check your connection and retry.")
            } finally {
                temporary.delete()
            }
        }
    }

    companion object {
        private const val REVISION = "f7e76881373410af03873a1d92e939495ba98f7b"
        private const val SIZE = 142_819_328L
        private const val SHA256 = "ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/$REVISION/SmolLM2_135M_Instruct.litertlm"
        @Volatile private var instance: ModelStore? = null

        fun get(context: Context): ModelStore = instance ?: synchronized(this) {
            instance ?: ModelStore(context.applicationContext).also { instance = it }
        }
    }
}
