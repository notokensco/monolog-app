package com.notokens.monolog.ime.vox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.media.AudioFormat
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal class VoxController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordingNote: Boolean = false,
    private val onStateUpdated: (VoxState) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentState = VoxState()
    private var stableTranscript = ""
    private var generation = 0
    private var running = false
    private var useAudioSource = true
    private var recorder: VoiceRecorder? = null
    private var source: ParcelFileDescriptor? = null
    private var sink: ParcelFileDescriptor? = null
    private var packets: Channel<ByteArray>? = null
    private var writer: Job? = null
    private var finishedSegment: VoiceRecorder? = null
    private val noteSegments = mutableListOf<VoiceRecorder>()
    private val main = Handler(Looper.getMainLooper())
    private var languageTag = "en-IN"
    private var languageLabel = "English"

    fun startListening(languageTag: String, languageLabel: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState(
                currentState.copy(
                    status = VoxStatus.PERMISSION_REQUIRED,
                    errorMessage = "Microphone permission required for Vox mode."
                )
            )
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            if (recordingNote) {
                cleanup()
                try {
                    recorder = VoiceRecorder(scope).also { it.start() }
                    updateState(currentState.copy(status = VoxStatus.LISTENING,
                        errorMessage = "On-device captions are unavailable for this recording."))
                } catch (_: Exception) {
                    updateState(currentState.copy(status = VoxStatus.ERROR, errorMessage = "Microphone unavailable."))
                }
                return
            }
            updateState(
                currentState.copy(
                    status = VoxStatus.UNAVAILABLE,
                    errorMessage = "On-device speech recognition is unavailable."
                )
            )
            return
        }

        cleanup()
        this.languageTag = languageTag
        this.languageLabel = languageLabel
        running = true
        useAudioSource = true
        listen()
    }

    private fun listen() {
        try {
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                setRecognitionListener(createListener(generation))
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            if (useAudioSource) {
                try {
                    val (read, write) = ParcelFileDescriptor.createPipe()
                    source = read
                    sink = write
                    val stream = Channel<ByteArray>(40)
                    packets = stream
                    writer = scope.launch(Dispatchers.IO) {
                        runCatching {
                            ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                                for (packet in stream) output.write(packet)
                            }
                        }
                    }
                    recorder = VoiceRecorder(scope).also { audio ->
                        audio.start(onEnd = { stream.close() }) { buffer, count ->
                            stream.trySend(buffer.copyOf(count))
                        }
                    }
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceRecorder.RATE)
                } catch (_: Exception) { closeAudio() }
            }

            updateState(currentState.copy(status = VoxStatus.LISTENING, errorMessage = null,
                language = languageLabel, finalSegment = ""))
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            cleanup()
            updateState(
                currentState.copy(
                    status = VoxStatus.ERROR,
                    errorMessage = "Could not start on-device speech recognition."
                )
            )
        }
    }

    fun pauseOrStopListening() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (speechRecognizer == null) {
            recorder?.stop()
            updateState(currentState.copy(status = VoxStatus.PAUSED, rmsLevel = 0f))
            return
        }
        try {
            if (recordingNote) recorder?.stop()
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        updateState(currentState.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
    }

    fun clearTranscript() {
        stableTranscript = ""
        updateState(currentState.copy(transcript = "", rmsLevel = 0f, finalSegment = ""))
    }

    fun cleanup() = stopSession()

    private fun stopSession(keepNoteAudio: Boolean = false) {
        running = false
        main.removeCallbacksAndMessages(null)
        generation++
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        if (keepNoteAudio) keepSegment()
        closeAudio()
        finishedSegment?.cancel()
        finishedSegment = null
        if (!keepNoteAudio) {
            noteSegments.forEach(VoiceRecorder::cancel)
            noteSegments.clear()
        }
    }

    private fun keepSegment() {
        val segment = recorder ?: return
        recorder = null
        segment.stop()
        noteSegments.add(segment)
    }

    suspend fun finishNote(): RecordedVoice {
        val transcript = currentState.transcript.trim().take(4000)
        stopSession(keepNoteAudio = true)
        val segments = noteSegments.toList()
        noteSegments.clear()
        val audio = try {
            VoiceRecorder.combineAudio(segments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            segments.forEach(VoiceRecorder::cancel)
        }
        return RecordedVoice(transcript, audio)
    }

    private fun closeAudio() {
        recorder?.cancel()
        recorder = null
        packets?.close()
        packets = null
        writer?.cancel()
        writer = null
        runCatching { source?.close() }
        runCatching { sink?.close() }
        source = null
        sink = null
    }

    fun takeVoiceSegment(): VoiceRecorder? = finishedSegment.also { finishedSegment = null }

    private fun updateState(newState: VoxState) {
        currentState = newState
        onStateUpdated(newState)
    }

    private fun createListener(session: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (session != generation) return
            updateState(currentState.copy(status = VoxStatus.LISTENING, errorMessage = null))
        }

        override fun onBeginningOfSpeech() {
            if (session != generation) return
            updateState(currentState.copy(status = VoxStatus.LISTENING))
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (session != generation) return
            // Normalize dB (typically -2 to 10) to 0.0 .. 1.0 range for the visualizer
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            updateState(currentState.copy(rmsLevel = normalized))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (session != generation) return
            updateState(currentState.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
        }

        override fun onError(error: Int) {
            if (session != generation) return
            if (running && source != null && error in listOf(SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                useAudioSource = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                if (recordingNote) keepSegment()
                closeAudio()
                val next = ++generation
                main.postDelayed({ if (running && next == generation) listen() }, 250L)
                return
            }
            stopSession(keepNoteAudio = recordingNote)
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "This language is unavailable for on-device speech."
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Tap mic to try again."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer busy"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap mic to speak."
                else -> "Recognition error ($error)"
            }
            updateState(currentState.copy(status = VoxStatus.ERROR, errorMessage = message, rmsLevel = 0f))
        }

        override fun onResults(results: Bundle?) {
            if (session != generation) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            stableTranscript = listOf(stableTranscript, text).filter(String::isNotBlank).joinToString(" ")
            speechRecognizer?.destroy()
            speechRecognizer = null
            if (recordingNote) keepSegment()
            else {
                finishedSegment?.cancel()
                recorder?.stop()
                finishedSegment = recorder
                recorder = null
            }
            closeAudio()
            val continuation = ++generation
            updateState(currentState.copy(
                status = if (running) VoxStatus.LISTENING else VoxStatus.PAUSED,
                transcript = stableTranscript,
                rmsLevel = 0f,
                finalSegment = text
            ))
            if (running) main.postDelayed({
                if (running && generation == continuation) listen()
            }, 250L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (session != generation) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                updateState(currentState.copy(
                    transcript = listOf(stableTranscript, partial).filter(String::isNotBlank).joinToString(" ")
                ))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
