package com.notokens.monolog.memory

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.URL
import java.nio.LongBuffer
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SentenceEncoder private constructor(private val context: Context) {
    private val folder = File(context.noBackupFilesDir, "sentence-encoder")
    private val model = File(folder, "model.onnx")
    private val tokenizerFile = File(folder, "tokenizer.json")
    private val statusState = MutableStateFlow(if (ready()) "Ready · multilingual offline search" else "Keyword search · encoder not downloaded")
    val status = statusState.asStateFlow()
    private val lock = Mutex()
    private var tokenizer: SentenceTokenizer? = null
    private var session: OrtSession? = null
    private var downloading = false

    fun ready() = model.isFile && tokenizerFile.isFile && model.length() == MODEL_BYTES && tokenizerFile.length() == TOKENIZER_BYTES

    fun download() {
        if (downloading || ready()) return
        downloading = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                folder.mkdirs()
                download("onnx/model_quantized.onnx", model, MODEL_SHA, MODEL_BYTES)
                download("tokenizer.json", tokenizerFile, TOKENIZER_SHA, TOKENIZER_BYTES)
                statusState.value = "Ready · multilingual offline search"
                MemoryIndexWork.enqueue(context)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                statusState.value = "Download failed · keyword search remains available"
            } finally {
                downloading = false
            }
        }
    }

    private suspend fun download(remote: String, target: File, expected: String, size: Long) {
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val connection = URL("$BASE/$remote").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= size)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        statusState.value = "Downloading offline search · ${total * 100 / size}%"
                    }
                }
            }
            require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == expected)
            check(part.renameTo(target))
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    suspend fun embed(text: String): ByteArray? = withContext(Dispatchers.Default) {
        if (!ready()) return@withContext null
        lock.withLock {
            val environment = OrtEnvironment.getEnvironment()
            val active = session ?: run {
                require(sha(model) == MODEL_SHA && sha(tokenizerFile) == TOKENIZER_SHA) { "Encoder verification failed." }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                }
                try { environment.createSession(model.path, options) } finally { options.close() }
            }.also { session = it }
            val words = tokenizer ?: SentenceTokenizer(tokenizerFile).also { tokenizer = it }
            val ids = words.tokens(text)
            val shape = longArrayOf(1, ids.size.toLong())
            val inputs = mutableMapOf<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
                inputs["attention_mask"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
                if ("token_type_ids" in active.inputNames) {
                    inputs["token_type_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape)
                }
                active.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = result[0].value as Array<Array<FloatArray>>
                    require(hidden.size == 1 && hidden[0].size == ids.size && hidden[0][0].size == 384)
                    val mean = FloatArray(384)
                    for (token in hidden[0]) for (i in mean.indices) mean[i] += token[i] / ids.size
                    val norm = sqrt(mean.sumOf { it.toDouble() * it }.toFloat()).coerceAtLeast(.000001f)
                    ByteArray(384) { (mean[it] / norm * 127).roundToInt().coerceIn(-127, 127).toByte() }
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        }
    }

    suspend fun release() = lock.withLock { session?.close(); session = null; tokenizer = null }

    companion object {
        const val VERSION = "multilingual-minilm-l12-int8-2c4055b1-sources-v2"
        private const val BASE = "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/2c4055b12046f11709e9df2c122e59ffbdc2f900"
        private const val MODEL_SHA = "66fc00f5f29afcaff34092e1bdd20008ca3918265a82fb9695a551e510cc4ebc"
        private const val TOKENIZER_SHA = "b60b6b43406a48bf3638526314f3d232d97058bc93472ff2de930d43686fa441"
        private const val MODEL_BYTES = 118308126L
        private const val TOKENIZER_BYTES = 17082913L
        @Volatile private var instance: SentenceEncoder? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: SentenceEncoder(context.applicationContext).also { instance = it }
        }
        private fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun cosine(a: ByteArray, b: ByteArray): Double {
            require(a.size == 384 && b.size == 384)
            var dot = 0.0
            var left = 0.0
            var right = 0.0
            for (i in a.indices) {
                dot += a[i].toInt() * b[i].toInt()
                left += a[i].toInt() * a[i].toInt()
                right += b[i].toInt() * b[i].toInt()
            }
            return if (left == 0.0 || right == 0.0) 0.0 else dot / sqrt(left * right)
        }
    }
}
