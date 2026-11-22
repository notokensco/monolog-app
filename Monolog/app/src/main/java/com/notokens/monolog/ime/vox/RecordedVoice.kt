package com.notokens.monolog.ime.vox

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*

internal data class RecordedVoice(val transcript: String, val audio: String?)

/** A single microphone session. PCM stays in memory and the saved clip uses mono AAC. */
internal class VoiceRecorder(private val scope: CoroutineScope) {
    private var record: AudioRecord? = null
    private var reading: Deferred<ByteArray>? = null

    @SuppressLint("MissingPermission")
    fun start(
        onEnd: () -> Unit = {},
        onSpeechEnd: () -> Unit = {},
        onPreview: ((ByteArray) -> Unit)? = null,
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
        reading = scope.async(Dispatchers.IO) {
            val samples = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            val endpoint = if (onPreview == null) null else SpeechEndpoint()
            try {
                while (isActive && samples.size() < LIMIT) {
                    val count = audio.read(buffer, 0, minOf(buffer.size, LIMIT - samples.size()))
                    if (count <= 0) break
                    samples.write(buffer, 0, count)
                    onSamples?.invoke(buffer, count)
                    if (endpoint != null) {
                        val event = endpoint.accept(buffer, count)
                        if (event == SpeechEndpoint.Event.Finished) {
                            onSpeechEnd()
                            break
                        }
                        if (event == SpeechEndpoint.Event.Preview) {
                            // Use a short trailing window for provisional captions.
                            val recorded = samples.toByteArray()
                            val window = RATE * 2 * 8
                            onPreview?.invoke(if (recorded.size <= window) recorded
                                else recorded.copyOfRange(recorded.size - window, recorded.size))
                        }
                    }
                }
                samples.toByteArray()
            } finally {
                runCatching { audio.stop() }
                audio.release()
                onEnd()
            }
        }
    }

    suspend fun finishAudio(): String {
        val pcm = finishPcm()
        val aac = withContext(Dispatchers.Default) { encode(pcm) }
        return Base64.encodeToString(aac, Base64.NO_WRAP)
    }

    suspend fun pcmForRecognition(): ByteArray {
        val pending = checkNotNull(reading)
        stop()
        return pending.await()
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

        private fun encode(pcm: ByteArray): ByteArray {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val output = ByteArrayOutputStream()
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var offset = 0
                var queuedEnd = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (!queuedEnd) {
                        val id = codec.dequeueInputBuffer(5_000)
                        if (id >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(id))
                            input.clear()
                            val size = minOf(input.remaining(), pcm.size - offset)
                            input.put(pcm, offset, size)
                            val flags = if (offset + size == pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(id, 0, size, offset * 500_000L / RATE, flags)
                            offset += size
                            queuedEnd = flags != 0
                        }
                    }
                    val id = codec.dequeueOutputBuffer(info, 5_000)
                    if (id < 0) continue
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val packet = ByteArray(info.size)
                        checkNotNull(codec.getOutputBuffer(id)).apply { position(info.offset); get(packet) }
                        val length = packet.size + 7
                        // ADTS, AAC LC, 16 kHz sample index 8, one channel.
                        output.write(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60,
                            ((1 shl 6) or (length shr 11)).toByte(), (length shr 3).toByte(),
                            (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte()))
                        output.write(packet)
                    }
                    codec.releaseOutputBuffer(id, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        check(output.size() in 1..160_000)
                        return output.toByteArray()
                    }
                }
                error("The audio encoder timed out.")
            } finally { codec.release() }
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
