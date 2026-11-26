package com.notokens.monolog.ime.vox

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*

internal data class RecordedVoice(val transcript: String, val audio: String?)

/** A single microphone session. PCM stays in memory and the saved clip uses mono Opus in Ogg. */
internal class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
    private var record: AudioRecord? = null
    private var reading: Deferred<ByteArray>? = null

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        onEnd: () -> Unit = {},
        onSamples: ((ByteArray, Int) -> Unit)? = null
    ) {
        check(reading == null)
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (e: Exception) {
            audio.release()
            throw e
        }
        record = audio
        reading = scope.async(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val samples = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            try {
                while (isActive && samples.size() < LIMIT) {
                    val count = audio.read(buffer, 0, minOf(buffer.size, LIMIT - samples.size()))
                    if (count <= 0) break
                    samples.write(buffer, 0, count)
                    onSamples?.invoke(buffer, count)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve successfully captured audio when the recorder is interrupted.
            } finally {
                runCatching { audio.stop() }
                runCatching { audio.release() }
                onEnd()
            }
            samples.toByteArray()
        }
    }

    suspend fun finishAudio(): String {
        val pcm = finishPcm()
        val ogg = withContext(Dispatchers.IO) { encode(pcm, context.cacheDir) }
        return Base64.encodeToString(ogg, Base64.NO_WRAP)
    }

    private suspend fun finishPcm(): ByteArray {
        val pending = checkNotNull(reading)
        stop()
        val pcm = try { pending.await() } finally { reading = null }
        check(pcm.size >= RATE / 2) { "No audio was recorded." }
        return pcm
    }

    fun stop() {
        record?.let { runCatching { it.stop() } }
        record = null
    }

    fun cancel() {
        stop()
        reading?.cancel()
        reading = null
    }

    companion object {
        const val RATE = 16_000
        const val SECONDS = 30
        private const val LIMIT = RATE * 2 * SECONDS

        private suspend fun encode(pcm: ByteArray, cache: File): ByteArray {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 16_000)
            }
            val file = File.createTempFile("monolog-audio-", ".ogg", cache)
            try {
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                try {
                    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        codec.start()
                        val info = MediaCodec.BufferInfo()
                        var track = -1
                        var offset = 0
                        var inputEnded = false
                        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            currentCoroutineContext().ensureActive()
                            if (!inputEnded) {
                                val index = codec.dequeueInputBuffer(5_000)
                                if (index >= 0) {
                                    val input = checkNotNull(codec.getInputBuffer(index))
                                    input.clear()
                                    val size = minOf(input.remaining(), pcm.size - offset) and -2
                                    input.put(pcm, offset, size)
                                    val flags = if (offset + size >= pcm.size - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    codec.queueInputBuffer(index, 0, size, offset * 500_000L / RATE, flags)
                                    offset += size
                                    inputEnded = flags != 0
                                }
                            }
                            val index = codec.dequeueOutputBuffer(info, 5_000)
                            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                track = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                            }
                            if (index < 0) continue
                            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(track >= 0)
                                    muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(index)), info)
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                            if (ended) {
                                check(track >= 0)
                                muxer.stop()
                                check(file.length() in 1..160_000)
                                return file.readBytes()
                            }
                        }
                        error("The audio encoder timed out.")
                    } finally { muxer.release() }
                } finally { codec.release() }
            } finally { file.delete() }
        }
    }
}

internal object VoiceNoteBody {
    private const val PREFIX = "\n\n<!-- MONOLOG_AUDIO:"
    fun attach(text: String, audio: String?) = if (audio.isNullOrBlank()) text else strip(text) + PREFIX + audio + "-->"
    fun strip(text: String) = text.substringBeforeLast(PREFIX)
    fun audio(text: String): String? = text.substringAfterLast(PREFIX, "").removeSuffix("-->")
        .takeIf { it.isNotBlank() && it.length <= 225_000 && it.all { c -> c.isLetterOrDigit() || c in "+/=" } }
}
