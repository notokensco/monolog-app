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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.icu.text.Transliterator
import java.text.Normalizer
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal object RegionalVox {
    private data class Asset(val name: String, val bytes: Long, val sha: String)
    private val assetNames = listOf("frontend.onnx", "encoder.onnx", "decoder.onnx", "shruti.json")
    private const val SOURCE = "https://huggingface.co/udgamlabs/Shruti-ONNX/resolve/a464a502cc732e4335313869e1ccf393ff8ba56b"
    @Volatile private var manifest: Map<String, List<Asset>>? = null

    private fun assets(context: Context, code: String): List<Asset> {
        val packs = manifest ?: context.assets.open("vox-regional-packs.tsv").bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).associate { line ->
                val cells = line.split('\t')
                cells.first() to assetNames.mapIndexed { index, name ->
                    Asset(name, cells[index * 2 + 1].toLong(), cells[index * 2 + 2])
                }
            }
        }.also { manifest = it }
        return packs.getValue(code)
    }

    fun packSize(context: Context, mode: VoxLanguage) = assets(context, mode.pack).sumOf { it.bytes }

    private const val REQUEST_BYTES = 2L * 1024 * 1024
    internal sealed interface Install {
        data object Idle : Install
        data class Downloading(val pack: String, val percent: Int) : Install
        data class Ready(val pack: String) : Install
        data class Failed(val pack: String, val reason: String) : Install
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installState = MutableStateFlow<Install>(Install.Idle)
    val installation = installState.asStateFlow()
    private val recognizerAccess = Mutex()
    private var cached: Pair<String, RegionalRecognizer>? = null

    private fun folder(context: Context, code: String) = File(context.noBackupFilesDir,
        if (code == "hi") "vox-india-shruti-1" else "vox-india-shruti-1-$code")

    fun ready(context: Context, mode: VoxLanguage = VoxLanguage.HINDI) =
        assets(context, mode.pack).all { File(folder(context, mode.pack), it.name).length() == it.bytes }

    /** The open speech pack exposes subword pieces, which can supplement typed-prefix coverage. */
    fun typingVocabulary(context: Context, code: String): List<String> {
        val mode = VoxLanguage.entries.firstOrNull { it.code == code } ?: return emptyList()
        if (!ready(context, mode)) return emptyList()
        val meta = File(folder(context, mode.pack), "shruti.json")
        return runCatching {
            val tokens = JSONObject(meta.readText()).getJSONArray("vocab")
            (0 until tokens.length()).mapNotNull { index ->
                tokens.optString(index).takeIf { it.startsWith('\u2581') }?.drop(1)
            }
        }.getOrDefault(emptyList())
    }

    fun anyReady(context: Context) = VoxLanguage.downloadChoices.any { ready(context, it) }

    private fun recognizer(context: Context, mode: VoxLanguage): RegionalRecognizer {
        cached?.takeIf { it.first == mode.pack }?.let { return it.second }
        cached?.second?.close()
        cached = null
        return RegionalRecognizer(folder(context, mode.pack)).also { cached = mode.pack to it }
    }

    suspend fun warm(context: Context, mode: VoxLanguage) = withContext(Dispatchers.Default) {
        recognizerAccess.withLock {
            if (ready(context, mode)) recognizer(context, mode)
        }
    }

    suspend fun <T> use(context: Context, mode: VoxLanguage, block: suspend (RegionalRecognizer) -> T): T =
        recognizerAccess.withLock { block(recognizer(context, mode)) }

    fun release(shouldRelease: () -> Boolean = { true }) {
        scope.launch {
            recognizerAccess.withLock {
                if (!shouldRelease()) return@withLock
                cached?.second?.close()
                cached = null
            }
        }
    }

    @Synchronized
    fun startDownload(context: Context, mode: VoxLanguage) {
        if (installState.value is Install.Downloading) return
        val app = context.applicationContext
        val pack = mode.pack
        installState.value = Install.Downloading(pack, 0)
        scope.launch {
            try {
                download(app, pack) { percent -> installState.value = Install.Downloading(pack, percent) }
                val settings = SettingsStore(app)
                settings.setVoxLanguage(mode.code)
                settings.setVoiceInputEngine(VoiceInputEngine.REGIONAL)
                installState.value = Install.Ready(pack)
            } catch (cancelled: CancellationException) {
                installState.value = Install.Idle
                throw cancelled
            } catch (failure: Exception) {
                val reason = when (failure) {
                    is DownloadFailure -> failure.message.orEmpty()
                    is java.net.SocketTimeoutException -> "Download timed out. Tap Retry to resume."
                    is java.net.UnknownHostException -> "The download host could not be reached. Tap Retry to resume."
                    is javax.net.ssl.SSLException -> "The download host's secure connection failed. Tap Retry to resume."
                    else -> "The download was interrupted. Tap Retry to resume."
                }
                android.util.Log.w("MonologVox", "VOX AI India download failed: $reason")
                installState.value = Install.Failed(pack, reason)
            }
        }
    }

    private class DownloadFailure(message: String) : IOException(message)

    private suspend fun download(context: Context, pack: String, progress: (Int) -> Unit) {
        val dir = folder(context, pack)
        val assets = assets(context, pack)
        if (!dir.isDirectory && !dir.mkdirs()) throw DownloadFailure("Can't open model storage on this phone.")
        val total = assets.sumOf { it.bytes }
        val remaining = assets.sumOf { asset ->
            if (File(dir, asset.name).length() == asset.bytes) 0L
            else asset.bytes - File(dir, "${asset.name}.part").length().takeIf { it <= asset.bytes }.orEmptyBytes()
        }
        if (android.os.StatFs(dir.path).availableBytes < remaining + 4 * 1024 * 1024) {
            throw DownloadFailure("Free some storage, then tap Retry to resume.")
        }
        var completed = 0L
        for (asset in assets) {
            val file = File(dir, asset.name)
            if (file.length() != asset.bytes) {
                val partial = File(dir, "${asset.name}.part")
                if (partial.length() > asset.bytes) partial.delete()
                suspend fun obtain() {
                    var interruptions = 0
                    while (partial.length() < asset.bytes) {
                        val offset = partial.length()
                        try {
                            fetchPart(pack, asset, partial, offset) { count ->
                                progress(((completed + count) * 100 / total).toInt().coerceAtMost(99))
                            }
                            interruptions = 0
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IOException) {
                            if (++interruptions >= 3) throw failure
                            delay(350L * interruptions)
                        }
                    }
                }
                obtain()
                if (!verified(partial, asset)) {
                    partial.delete()
                    obtain()
                    if (!verified(partial, asset)) {
                        partial.delete()
                        throw DownloadFailure("The downloaded ${asset.name} failed verification. Tap Retry.")
                    }
                }
                Os.rename(partial.path, file.path)
            }
            completed += asset.bytes
            progress((completed * 100 / total).toInt())
        }
    }

    private fun Long?.orEmptyBytes() = this ?: 0L

    private suspend fun verified(file: File, asset: Asset): Boolean {
        if (file.length() != asset.bytes) return false
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                sha.update(bytes, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.sha
    }

    private suspend fun fetchPart(pack: String, asset: Asset, file: File, offset: Long, progress: (Long) -> Unit) {
        val end = minOf(asset.bytes - 1, offset + REQUEST_BYTES - 1)
        // Resolve the pinned file afresh. CDN redirects are signed and have a finite lifetime.
        val url = URL("$SOURCE/$pack/onnx-int8/${asset.name}?download=true&monolog=${UUID.randomUUID()}")
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Range", "bytes=$offset-$end")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Monolog Android model downloader")
        }
        try {
            val status = connection.responseCode
            val limit = when {
                status == 206 && connection.getHeaderField("Content-Range") == "bytes $offset-$end/${asset.bytes}" -> end + 1
                status == 200 && offset == 0L -> asset.bytes
                else -> throw DownloadFailure("Download host returned HTTP $status for ${asset.name}. Tap Retry to resume.")
            }
            RandomAccessFile(file, "rw").use { output ->
                output.seek(offset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var count = offset
                    while (count < limit) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - count).toInt())
                        if (size < 0) throw DownloadFailure("Download ended early. Tap Retry to resume.")
                        output.write(buffer, 0, size)
                        count += size
                        progress(count)
                    }
                }
                output.fd.sync()
            }
        } finally { connection.disconnect() }
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

    suspend fun decode(samples: FloatArray, mode: VoxLanguage): String {
        if (samples.size < 4000 || samples.none { kotlin.math.abs(it) > .001f }) return ""
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { audio ->
            frontend.run(mapOf("audio" to audio)).use { features ->
                ints(samples.size.toLong() / config.getInt("hop")).use { frameCount ->
                    encoder.run(mapOf("features" to (features[0] as OnnxTensor), "frames" to frameCount)).use { encoded ->
                        return generate(encoded[0] as OnnxTensor, samples.size, mode)
                    }
                }
            }
        }
    }

    private fun ints(value: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value)), longArrayOf(1))

    private suspend fun generate(cross: OnnxTensor, sampleCount: Int, mode: VoxLanguage): String {
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
            val chosenTag = if (mode == VoxLanguage.HINGLISH) null else tags.getValue(mode.code)
            var input = chosenTag?.let { longArrayOf(bos.toLong(), it.toLong()) } ?: longArrayOf(bos.toLong())
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
                val output = checkNotNull(previous)[0] as OnnxTensor
                val logits = output.floatBuffer
                val last = logits.limit() - output.info.shape.last().toInt()
                val token = if (chosenTag == null && step == 0) {
                    val hi = tags.getValue("hi")
                    val en = tags.getValue("en")
                    if (logits[last + hi] >= logits[last + en]) hi else en
                } else {
                    var best = eos
                    for (candidate in 0 until vocabLimit) if (logits[last + candidate] > logits[last + best]) best = candidate
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
        }.replace('\u2581', ' ').trim().let { format(it, mode) }
    }

    private fun format(text: String, mode: VoxLanguage): String = when (mode) {
        VoxLanguage.ENGLISH -> normalizeDigits(text.replace(nonLatinWord, ""))
            .replace(extraSpace, " ").trim()
        VoxLanguage.HINDI -> text.replace(latinWord, "").replace(extraSpace, " ").trim()
        VoxLanguage.HINGLISH -> normalizeDigits(devanagariWord.replace(text) { match ->
            romanWords[match.value] ?: Normalizer.normalize(
                romanizer.transliterate(match.value).replace("\u0101", "aa").replace("\u012b", "ee").replace("\u016b", "oo"),
                Normalizer.Form.NFD
            ).replace(combiningMarks, "").let {
                if (match.value.last() in '\u0915'..'\u0939') it.removeSuffix("a") else it
            }
        })
        else -> text
    }

    private fun normalizeDigits(text: String) = buildString(text.length) {
        for (char in text) {
            append(when (char) {
                in '\u0966'..'\u096f' -> '0' + (char - '\u0966')
                '\u0964' -> '.'
                else -> char
            })
        }
    }

    private companion object {
        val romanizer by lazy { Transliterator.getInstance("Devanagari-Latin") }
        val latinWord = Regex("""[\p{IsLatin}]+(?:['\u2019-][\p{IsLatin}]+)*""")
        val nonLatinWord = Regex("""[\p{L}&&[^\p{IsLatin}]][\p{L}\p{M}&&[^\p{IsLatin}]]*""")
        val devanagariWord = Regex("""[\p{IsDevanagari}&&[\p{L}\p{M}]]+""")
        val combiningMarks = Regex("""\p{M}+""")
        val extraSpace = Regex("""\s{2,}""")
        val romanWords = mapOf(
            "\u0906\u092a" to "aap", "\u092e\u0948\u0902" to "main", "\u0939\u0948" to "hai",
            "\u0939\u0948\u0902" to "hain", "\u0939\u0942\u0901" to "hoon", "\u0928\u0939\u0940\u0902" to "nahi",
            "\u092e\u0941\u091d\u0947" to "mujhe", "\u0915\u0948\u0938\u0947" to "kaise",
            "\u0920\u0940\u0915" to "theek", "\u0905\u091a\u094d\u091b\u093e" to "achha",
            "\u0927\u0928\u094d\u092f\u0935\u093e\u0926" to "dhanyavaad"
        )
    }

    override fun close() { sessions.asReversed().forEach { runCatching { it.close() } } }
}
