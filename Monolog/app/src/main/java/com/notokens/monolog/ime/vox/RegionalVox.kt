package com.notokens.monolog.ime.vox

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object RegionalVox {
    private data class Asset(val name: String, val bytes: Long, val sha: String)
    private val assets = listOf(
        Asset("frontend.onnx", 911143, "0457d948c74b62e12b5d652d275269331092e13b22a2cdf64d2773c639a2b276"),
        Asset("encoder.onnx", 19633861, "b26a33cedf74ce11f13ad3854f3c0a34e7272c4e2dfd0d5cd2818185b029b996"),
        Asset("decoder.onnx", 32293075, "777870e340af2c5a9513f8bef14ecf0c2d57ba7dbfe8682a99674ec58dbd6f70"),
        Asset("shruti.json", 94153, "82aa655fef9ace280b626f5cfbbbe9a9e417c5c997a2708480e65d33ec3bdc45")
    )
    private const val SOURCE = "https://huggingface.co/udgamlabs/Shruti-ONNX/resolve/a464a502cc732e4335313869e1ccf393ff8ba56b/hi/onnx-int8"
    private val downloadLock = Mutex()
    private fun folder(context: Context) = File(context.noBackupFilesDir, "vox-india-shruti-1")
    fun supports(language: String) = language.substringBefore('-').lowercase() in setOf("en", "hi")
    fun ready(context: Context) = assets.all { File(folder(context), it.name).length() == it.bytes }
    fun open(context: Context): RegionalRecognizer = RegionalRecognizer(folder(context))

    suspend fun download(context: Context, progress: (Int) -> Unit) = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            val dir = folder(context)
            check(dir.isDirectory || dir.mkdirs())
            val total = assets.sumOf { it.bytes }
            var downloaded = 0L
            var lastPercent = -1
            for (asset in assets) {
                val file = File(dir, asset.name)
                if (file.length() != asset.bytes) {
                    val part = File(dir, "${asset.name}.part")
                    val connection = (URL("$SOURCE/${asset.name}").openConnection() as HttpsURLConnection).apply {
                        readTimeout = 25_000
                        connectTimeout = 12_000
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    val sha = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    try {
                        check(connection.responseCode == 200)
                        connection.inputStream.use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    check(bytes <= asset.bytes)
                                    sha.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    val percent = ((downloaded + bytes) * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main) { progress(percent) }
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                        check(bytes == asset.bytes && sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.sha)
                        Os.rename(part.path, file.path)
                    } finally {
                        connection.disconnect()
                        part.delete()
                    }
                }
                downloaded += asset.bytes
            }
            withContext(Dispatchers.Main) { progress(100) }
        }
    }
}

// Shruti's waveform frontend, encoder, and cached autoregressive decoder run in Monolog's existing ONNX Runtime.
internal class RegionalRecognizer(folder: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val metadata = JSONObject(File(folder, "shruti.json").readText())
    private val vocab = metadata.getJSONArray("vocab")
    private val config = metadata.getJSONObject("config")
    private val vocabLimit = config.getInt("text")
    private val eos = config.getInt("eos")
    private val bos = config.getInt("bos")
    private val tags = config.getJSONArray("languages").let { tags ->
        List(tags.length()) { tags.getString(it) }.withIndex().associate { it.value to vocabLimit + it.index }
    }
    private val sessions = mutableListOf<OrtSession>()
    private val frontend: OrtSession
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        try {
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }.use { opts ->
                fun load(name: String) = env.createSession(File(folder, name).path, opts).also(sessions::add)
                frontend = load("frontend.onnx")
                encoder = load("encoder.onnx")
                decoder = load("decoder.onnx")
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun decode(samples: FloatArray, english: Boolean): String {
        if (samples.size < 4000 || samples.none { kotlin.math.abs(it) > .001f }) return ""
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { audio ->
            frontend.run(mapOf("audio" to audio)).use { features ->
                ints(samples.size.toLong() / config.getInt("hop")).use { frameCount ->
                    encoder.run(mapOf("features" to (features[0] as OnnxTensor), "frames" to frameCount)).use { encoded ->
                        return generate(encoded[0] as OnnxTensor, samples.size, english)
                    }
                }
            }
        }
    }

    private fun ints(value: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value)), longArrayOf(1))

    private suspend fun generate(cross: OnnxTensor, sampleCount: Int, english: Boolean): String {
        val state = linkedMapOf<String, OnnxTensor>()
        var previous: OrtSession.Result? = null
        val tokens = mutableListOf<Int>()
        try {
            for ((name, node) in decoder.inputInfo) {
                if (name == "tokens" || name == "cross") continue
                val info = node.info as TensorInfo
                val shape = info.shape.mapIndexed { index, value -> if (index == 0) 1L else value.coerceAtLeast(0L) }.toLongArray()
                val size = shape.fold(1L, Long::times).toInt()
                state[name] = if (info.type == OnnxJavaType.INT64) {
                    OnnxTensor.createTensor(env, LongBuffer.allocate(size), shape)
                } else OnnxTensor.createTensor(env, FloatBuffer.allocate(size), shape)
            }
            var input = if (english) longArrayOf(bos.toLong(), tags.getValue("en").toLong()) else longArrayOf(bos.toLong())
            val limit = minOf(config.getInt("max_tokens") - 2, 10 * sampleCount / VoiceRecorder.RATE + 12)
            for (step in 0..limit) {
                currentCoroutineContext().ensureActive()
                OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { feed ->
                    val args = mutableMapOf("tokens" to feed, "cross" to cross)
                    for ((key, initial) in state) args[key] = previous?.get("new_$key")?.orElseThrow() as? OnnxTensor ?: initial
                    val next = decoder.run(args)
                    previous?.close()
                    previous = next
                }
                val logits = (checkNotNull(previous)[0] as OnnxTensor).floatBuffer
                val token = if (!english && step == 0) {
                    val hi = tags.getValue("hi")
                    val en = tags.getValue("en")
                    if (logits[hi] >= logits[en]) hi else en
                } else {
                    var best = eos
                    for (candidate in 0 until vocabLimit) if (logits[candidate] > logits[best]) best = candidate
                    if (best == eos) break
                    tokens.add(best)
                    best
                }
                input = longArrayOf(token.toLong())
            }
        } finally {
            previous?.close()
            state.values.forEach(OnnxTensor::close)
        }
        return buildString {
            val escaped = ByteArrayOutputStream()
            for (id in tokens) {
                val piece = vocab.getString(id)
                if (piece.length == 6 && piece.startsWith("<0x") && piece.endsWith('>')) {
                    piece.substring(3, 5).toIntOrNull(16)?.let(escaped::write)
                    continue
                }
                if (escaped.size() > 0) { append(escaped.toByteArray().decodeToString()); escaped.reset() }
                if (!piece.startsWith('<') || !piece.endsWith('>')) append(piece)
            }
            if (escaped.size() > 0) append(escaped.toByteArray().decodeToString())
        }.replace('▁', ' ').trim()
    }

    override fun close() { sessions.asReversed().forEach { runCatching { it.close() } } }
}
