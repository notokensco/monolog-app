package com.notokens.monolog.ime.vox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class VoxController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordingNote: Boolean = false,
    private val onStateUpdated: (VoxState) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var state = VoxState()
    private var generation = 0
    private var recorder: VoiceRecorder? = null
    private var transcription: Job? = null
    private var preview: Job? = null
    private var finishedAudio: VoiceRecorder? = null
    private var languageTag = "en-IN"

    fun startListening(languageTag: String, languageLabel: String) {
        if (!OfflineWhisper.ready(context)) {
            update(state.copy(status = VoxStatus.UNAVAILABLE,
                errorMessage = "Download Whisper Small in Monolog’s On-device AI settings to use Vox."))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update(state.copy(status = VoxStatus.PERMISSION_REQUIRED, errorMessage = "Microphone permission required."))
            return
        }
        if (!OfflineWhisper.hasHeadroom(context)) {
            update(state.copy(status = VoxStatus.UNAVAILABLE,
                errorMessage = "Close some apps to free memory for Whisper Small."))
            return
        }
        cleanup()
        this.languageTag = languageTag
        val session = generation
        try {
            recorder = VoiceRecorder(scope).also { audio ->
                audio.start(
                    onEnd = { main.post { finishCapture(session) } },
                    onSpeechEnd = { main.post { finishCapture(session) } },
                    onPreview = { pcm -> main.post { previewSpeech(session, pcm) } }
                ) { data, count ->
                    val level = microphoneLevel(data, count)
                    main.post {
                        if (session == generation && state.status == VoxStatus.LISTENING) {
                            update(state.copy(rmsLevel = level))
                        }
                    }
                }
            }
            update(state.copy(status = VoxStatus.LISTENING, language = languageLabel,
                usesWhisper = true, errorMessage = null, finalSegment = "", partialTranscript = "", rmsLevel = 0f))
        } catch (_: Exception) {
            cleanup()
            update(state.copy(status = VoxStatus.ERROR, errorMessage = "Microphone unavailable."))
        }
    }

    fun pauseOrStopListening() = finishCapture(generation)

    private fun previewSpeech(session: Int, pcm: ByteArray) {
        if (session != generation || state.status != VoxStatus.LISTENING || preview?.isActive == true) return
        val locale = languageTag
        preview = scope.launch {
            val result = try {
                OfflineWhisper.transcribe(context, pcm, locale, provisional = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (session == generation && state.status == VoxStatus.LISTENING && !result.isNullOrBlank()) {
                update(state.copy(partialTranscript = PronunciationAlternatives.cleanFinal(result, locale)))
            }
        }
    }

    private fun finishCapture(session: Int) {
        if (session != generation || transcription != null) return
        val audio = recorder ?: return
        val locale = languageTag
        audio.stop()
        update(state.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
        transcription = scope.launch(start = CoroutineStart.LAZY) {
            val decoded = try {
                preview?.join()
                OfflineWhisper.transcribe(context, audio.pcmForRecognition(), locale)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (generation != session) return@launch
            recorder = null
            transcription = null
            val text = PronunciationAlternatives.cleanFinal(decoded.orEmpty(), locale)
            if (recordingNote || text.isNotBlank()) {
                finishedAudio?.cancel()
                finishedAudio = audio
            } else audio.cancel()
            update(state.copy(status = VoxStatus.PAUSED,
                transcript = listOf(state.transcript, text).filter(String::isNotBlank).joinToString(" "),
                finalSegment = text, partialTranscript = "",
                errorMessage = when {
                    decoded == null -> "Whisper couldn’t finish transcribing."
                    text.isBlank() -> "No speech recognized."
                    else -> null
                }))
        }
        transcription?.start()
    }

    private fun microphoneLevel(bytes: ByteArray, count: Int): Float {
        var peak = 0
        for (i in 0 until count - 1 step 2) {
            val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        return (peak / 8192f).coerceIn(0f, 1f)
    }

    fun cleanTranscript(text: String): String = PronunciationAlternatives.cleanFinal(text, languageTag)

    fun clearTranscript() {
        update(state.copy(transcript = "", finalSegment = "", partialTranscript = "", rmsLevel = 0f, errorMessage = null))
    }

    fun cleanup() {
        generation++
        main.removeCallbacksAndMessages(null)
        transcription?.cancel()
        transcription = null
        preview?.cancel()
        preview = null
        recorder?.cancel()
        recorder = null
        finishedAudio?.cancel()
        finishedAudio = null
    }

    suspend fun finishNote(): RecordedVoice {
        pauseOrStopListening()
        transcription?.join()
        val transcript = cleanTranscript(state.transcript).take(4000)
        val segment = takeVoiceSegment()
        cleanup()
        val audio = try {
            segment?.finishAudio()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            segment?.cancel()
        }
        return RecordedVoice(transcript, audio)
    }

    fun takeVoiceSegment(): VoiceRecorder? = finishedAudio.also { finishedAudio = null }

    private fun update(next: VoxState) {
        state = next
        onStateUpdated(next)
    }
}
