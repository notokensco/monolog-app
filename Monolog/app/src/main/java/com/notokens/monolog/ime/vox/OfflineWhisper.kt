package com.notokens.monolog.ime.vox

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.notokens.monolog.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object OfflineWhisper {
    private const val FILENAME = "ggml-small-q5_1.bin"
    private const val BYTES = 190_085_487L
    private const val SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private const val URL_STRING =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    private val access = Mutex()

    val available: Boolean get() = BuildConfig.MONOLOG_HAS_WHISPER && Process.is64Bit()

    fun modelFile(context: Context) = File(context.noBackupFilesDir, FILENAME)

    fun ready(context: Context): Boolean = available && modelFile(context).length() == BYTES

    fun hasHeadroom(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        if (manager.isLowRamDevice) return false
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return !memory.lowMemory && memory.availMem - memory.threshold >= 640L * 1024 * 1024
    }

    suspend fun download(context: Context, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        access.withLock {
            check(available)
            val destination = modelFile(context)
            if (destination.length() == BYTES) return@withLock
            val pending = File(destination.parentFile, ".$FILENAME.part")
            val connection = URL(URL_STRING).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Whisper model download failed.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var lastPercent = 0
                connection.inputStream.use { input ->
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            count += size
                            if (count > BYTES) throw IOException("Unexpected Whisper model size.")
                            digest.update(buffer, 0, size)
                            output.write(buffer, 0, size)
                            val percent = (count * 100 / BYTES).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main.immediate) { onProgress(percent) }
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (count != BYTES || hash != SHA256) throw IOException("Whisper model verification failed.")
                Files.move(pending.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                File(context.noBackupFilesDir, "whisper-tiny-q5_1.bin").delete()
                Unit
            } finally {
                connection.disconnect()
                pending.delete()
            }
        }
    }

    suspend fun remove(context: Context): Boolean = withContext(Dispatchers.IO) {
        access.withLock { Files.deleteIfExists(modelFile(context).toPath()) }
    }

    suspend fun transcribe(context: Context, pcm: ByteArray, languageTag: String, provisional: Boolean = false): String? =
        withContext(Dispatchers.Default) {
            access.withLock {
                if (!ready(context)) return@withLock null
                val language = languageTag.substringBefore('-').lowercase(Locale.ROOT)
                WhisperTranscription.recognize(context, pcm, language, provisional)
            }
        }

}
