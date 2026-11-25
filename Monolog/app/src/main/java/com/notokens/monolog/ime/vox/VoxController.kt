package com.notokens.monolog.ime.vox

import ai.moonshine.voice.TranscriptEvent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ime.NativeTyping
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal class VoxController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordingNote: Boolean = false,
    private val onStateUpdated: (VoxState) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = SettingsStore(context.applicationContext)
    private var state = VoxState()
    private var generation = 0
    private var isListening = false
    private var activeEngine = VoiceInputEngine.NATIVE

    private var recorder: VoiceRecorder? = null
    private var finishedAudio: VoiceRecorder? = null
    private var offlineJob: Job? = null
    private var androidSilenceJob: Job? = null
    private var pauseMs = 3200

    // Native SpeechRecognizer components
    private var speechRecognizer: SpeechRecognizer? = null
    private var stableTranscript = ""
    var smartFilter = true

    private var regionalMode = VoxLanguage.ENGLISH
    private var languageTag = "en-IN"
    private var languageLabel = "English"

    fun startListening(languageTag: String, languageLabel: String, mode: VoxLanguage? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update(state.copy(status = VoxStatus.PERMISSION_REQUIRED, errorMessage = "Microphone permission required."))
            return
        }
        cleanup()
        this.languageTag = languageTag
        this.languageLabel = languageLabel
        isListening = true

        val session = generation
        update(state.copy(status = VoxStatus.PREPARING, errorMessage = null))
        scope.launch {
            try {
                val selected = settings.voiceInputEngine.first()
                pauseMs = settings.voxPause.first()
                if (recordingNote) smartFilter = settings.smartVoxFilter.first()
                if (session != generation || !isListening) return@launch
                regionalMode = mode ?: VoxLanguage.resolve(settings.voxLanguage.first(), languageTag)
                if (session != generation || !isListening) return@launch
                activeEngine = if (recordingNote && selected == VoiceInputEngine.NATIVE) {
                    if (RegionalVox.ready(context, regionalMode)) VoiceInputEngine.REGIONAL else VoiceInputEngine.MOONSHINE
                } else selected
                startEngine()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (session == generation) failToStart()
            } catch (_: LinkageError) {
                if (session == generation) failToStart()
            }
        }
    }

    private fun failToStart() {
        cleanup()
        update(state.copy(status = VoxStatus.ERROR, errorMessage = "Couldn’t start Vox. Check your voice model and try again."))
    }

    private fun startEngine() {
        when (activeEngine) {
            VoiceInputEngine.MOONSHINE -> startMoonshineCapture()
            VoiceInputEngine.REGIONAL -> startRegionalCapture()
            VoiceInputEngine.NATIVE -> startNativeCapture()
        }
    }

    private fun useAndroidSpeech() {
        recorder?.cancel()
        recorder = null
        if (activeEngine == VoiceInputEngine.REGIONAL) {
            languageTag = if (regionalMode == VoxLanguage.ENGLISH) "en-IN" else regionalMode.code
            languageLabel = regionalMode.title
        }
        activeEngine = VoiceInputEngine.NATIVE
        startNativeCapture()
    }

    private fun startNativeCapture() {
        val session = generation
        update(state.copy(
            status = VoxStatus.LISTENING,
            language = languageLabel,
            engineLabel = "Native streaming",
            errorMessage = null,
            partialTranscript = "",
            rmsLevel = 0f
        ))
        try {
            speechRecognizer?.destroy()
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                isListening = false
                update(state.copy(status = VoxStatus.UNAVAILABLE,
                    errorMessage = "Android speech is unavailable. Download an offline Vox model in On-device AI settings."))
                return
            }
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                setRecognitionListener(createNativeListener(session))
            }
            androidSilenceJob = scope.launch {
                delay(9000)
                if (session == generation && isListening) pauseOrStopListening()
            }
            listenNative(session)
        } catch (_: Exception) {
            update(state.copy(status = VoxStatus.ERROR, errorMessage = "Native speech recognizer unavailable."))
        }
    }

    private fun listenNative(session: Int) {
        if (session != generation || !isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs.toLong())
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            update(state.copy(status = VoxStatus.ERROR, errorMessage = "Speech recognizer busy."))
        }
    }

    private fun createNativeListener(session: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (session != generation) return
            update(state.copy(status = VoxStatus.LISTENING, errorMessage = null))
        }

        override fun onBeginningOfSpeech() {
            if (session != generation) return
            androidSilenceJob?.cancel()
            update(state.copy(status = VoxStatus.LISTENING))
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (session != generation) return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            update(state.copy(rmsLevel = normalized))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (session != generation) return
            update(state.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
        }

        override fun onError(error: Int) {
            if (session != generation) return
            if (isListening && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                main.postDelayed({
                    if (session == generation && isListening) {
                        listenNative(session)
                    }
                }, 150L)
                return
            }
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "Android speech is unavailable for this language."
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Tap mic to speak."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer busy"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap mic to speak."
                else -> "Recognition error ($error)"
            }
            update(state.copy(status = VoxStatus.ERROR, errorMessage = message, rmsLevel = 0f))
        }

        override fun onResults(results: Bundle?) {
            if (session != generation) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val raw = matches?.firstOrNull() ?: ""
            val cleaned = PronunciationAlternatives.cleanFinal(raw, languageTag, smartFilter)
            if (cleaned.isNotBlank()) {
                stableTranscript = listOf(stableTranscript, cleaned).filter(String::isNotBlank).joinToString(" ")
            }
            update(state.copy(
                status = if (isListening) VoxStatus.LISTENING else VoxStatus.PAUSED,
                transcript = stableTranscript,
                finalSegment = cleaned,
                partialTranscript = "",
                rmsLevel = 0f
            ))
            if (isListening) {
                if (cleaned.isNotBlank()) {
                    androidSilenceJob?.cancel()
                    androidSilenceJob = scope.launch {
                        delay(pauseMs.toLong())
                        if (session == generation && isListening) pauseOrStopListening()
                    }
                }
                main.postDelayed({
                    if (session == generation && isListening) {
                        listenNative(session)
                    }
                }, 150L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (session != generation) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                val cleanedPartial = if (smartFilter) PronunciationAlternatives.cleanFillerPhonemes(partial) else partial
                update(state.copy(
                    partialTranscript = cleanedPartial,
                    status = VoxStatus.LISTENING
                ))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startMoonshineCapture() {
        val problem = when {
            !OfflineMoonshine.supports(languageTag) -> "VOX AI supports English. Choose Android speech for another language."
            !OfflineMoonshine.ready(context) -> "Download VOX AI in Monolog’s On-device AI settings to use Vox."
            else -> null
        }
        if (problem != null) {
            if (recordingNote) startAudioOnlyNote(problem)
            else useAndroidSpeech()
            return
        }

        val session = generation
        val locale = languageTag
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        update(state.copy(
            status = VoxStatus.PREPARING, language = languageLabel,
            engineLabel = "VOX AI",
            errorMessage = null, partialTranscript = "", rmsLevel = 0f
        ))
        offlineJob = scope.launch {
            var result = ""
            var latestCaption = ""
            var failure = false
            try {
                withContext(Dispatchers.Default) {
                    OfflineMoonshine.use(context) { engine ->
                        // The recorder caps this queue at one 30-second PCM clip.
                        val lines = linkedMapOf<Long, String>()
                        engine.addListener { event ->
                            val line = when (event) {
                                is TranscriptEvent.LineTextChanged -> event.line
                                is TranscriptEvent.LineCompleted -> event.line
                                else -> null
                            }
                            if (line != null) {
                                lines[line.id] = line.text.orEmpty().trim()
                                val rawCaption = lines.values.joinToString(" ")
                                val caption = if (smartFilter) PronunciationAlternatives.cleanFillerPhonemes(rawCaption)
                                else rawCaption.trim()
                                if (caption != latestCaption) {
                                    latestCaption = caption
                                    main.post {
                                        if (session == generation && state.status == VoxStatus.LISTENING) {
                                            update(state.copy(partialTranscript = caption))
                                        }
                                    }
                                }
                            }
                        }
                        val stream = engine.createStream()
                        try {
                            engine.startStream(stream)
                            withContext(Dispatchers.Main.immediate) {
                                if (session == generation && isListening) {
                                    recorder = VoiceRecorder(scope).also { audio ->
                                        recordStream(session, audio, chunks)
                                    }
                                    update(state.copy(status = VoxStatus.LISTENING))
                                } else {
                                    chunks.close()
                                }
                            }
                            val audioGain = MoonshineAudioGain()
                            for (pcm in chunks) {
                                engine.addAudioToStream(stream, audioGain.apply(NativeTyping.voiceSamples(pcm)), VoiceRecorder.RATE)
                            }
                            engine.stopStream(stream)
                            result = PronunciationAlternatives.cleanFinal(lines.values.joinToString(" "), locale, smartFilter)
                        } finally {
                            engine.freeStream(stream)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("MonologVox", "Moonshine streaming failed", error)
                failure = true
            } catch (error: LinkageError) {
                android.util.Log.w("MonologVox", "Moonshine runtime unavailable", error)
                failure = true
            } finally {
                chunks.cancel()
            }
            if (session != generation) return@launch
            offlineJob = null
            if (failure && recordingNote && recorder == null && isListening) {
                startAudioOnlyNote("Captions unavailable. Recording audio only.")
                return@launch
            }
            if (failure && !recordingNote && isListening) {
                useAndroidSpeech()
                return@launch
            }
            if (failure) isListening = false
            val restart = isListening && !recordingNote
            val transcript = result.ifBlank {
                if (recordingNote) PronunciationAlternatives.cleanFinal(latestCaption, locale, smartFilter) else ""
            }
            completeClip(transcript, when {
                failure -> "VOX AI couldn’t transcribe this recording."
                transcript.isBlank() && stableTranscript.isBlank() && !restart -> "No speech recognized."
                else -> null
            }, restart)
            if (restart) startMoonshineCapture()
        }
    }

    private fun recordStream(session: Int, audio: VoiceRecorder, chunks: Channel<ByteArray>) {
        val silence = SpeechPause(pauseMs)
        var ending = false
        audio.start(onEnd = { chunks.close() }) { data, count ->
            chunks.trySend(data.copyOf(count))
            publishLevel(session, data, count)
            silence.accept(data, count)
            if (!ending && silence.shouldStop) {
                ending = true
                main.post {
                    if (session == generation && recorder === audio && isListening) pauseOrStopListening()
                }
            }
        }
    }

    private fun startRegionalCapture() {
        val mode = regionalMode
        if (!RegionalVox.ready(context, mode)) {
            if (recordingNote) startAudioOnlyNote("Download VOX AI India to transcribe this language.")
            else useAndroidSpeech()
            return
        }
        val session = generation
        val tag = if (mode == VoxLanguage.ENGLISH) "en-IN" else mode.code
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        update(state.copy(status = VoxStatus.PREPARING, engineLabel = "VOX AI India",
            language = mode.title, partialTranscript = "", rmsLevel = 0f, errorMessage = null))
        offlineJob = scope.launch {
            var transcript = ""
            var failed = false
            try {
                withContext(Dispatchers.Default) {
                    RegionalVox.use(context, mode) { recognizer ->
                        withContext(Dispatchers.Main.immediate) {
                            if (session == generation && isListening) {
                                recorder = VoiceRecorder(scope).also { recordStream(session, it, chunks) }
                                update(state.copy(status = VoxStatus.LISTENING))
                            } else chunks.close()
                        }
                        val phrase = ByteArrayOutputStream()
                        val pause = SpeechPause(pauseMs)
                        val previous = ByteArrayOutputStream()
                        var voiced = false
                        var nextPreview = 28_800
                        val settled = mutableListOf<String>()

                        suspend fun decode(): String {
                            val samples = NativeTyping.voiceSamples(phrase.toByteArray())
                            return PronunciationAlternatives.cleanFinal(recognizer.decode(samples, mode), tag, smartFilter)
                        }

                        fun show(caption: String) {
                            main.post {
                                if (session == generation && state.status == VoxStatus.LISTENING &&
                                    state.partialTranscript != caption) update(state.copy(partialTranscript = caption))
                            }
                        }

                        for (pcm in chunks) {
                            val speaking = pause.accept(pcm, pcm.size)
                            if (!voiced && !speaking) {
                                if (previous.size() + pcm.size > 6400) previous.reset()
                                previous.write(pcm)
                                continue
                            }
                            if (!voiced) {
                                phrase.write(previous.toByteArray())
                                voiced = true
                            }
                            phrase.write(pcm)
                            val boundary = pause.quietMs >= 720 || phrase.size() >= VoiceRecorder.RATE * 2 * 11
                            if (boundary) {
                                if (phrase.size() >= 12_800) decode().takeIf(String::isNotBlank)?.let(settled::add)
                                phrase.reset()
                                previous.reset()
                                nextPreview = 28_800
                                voiced = false
                                show(settled.joinToString(" "))
                            } else if (phrase.size() >= nextPreview && speaking) {
                                val preview = decode()
                                show((settled + preview).filter(String::isNotBlank).joinToString(" "))
                                nextPreview = phrase.size() + 38_400
                            }
                        }
                        if (voiced && phrase.size() >= 12_800) decode().takeIf(String::isNotBlank)?.let(settled::add)
                        transcript = settled.joinToString(" ")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("MonologVox", "Regional voice model failed", error)
                failed = true
            } catch (error: LinkageError) {
                android.util.Log.w("MonologVox", "Regional voice runtime unavailable", error)
                failed = true
            } finally { chunks.cancel() }
            if (session != generation) return@launch
            offlineJob = null
            if (failed && recordingNote && recorder == null && isListening) {
                startAudioOnlyNote("Captions unavailable. Recording audio only.")
                return@launch
            }
            if (failed && !recordingNote && isListening) {
                useAndroidSpeech()
                return@launch
            }
            if (failed) isListening = false
            val restart = isListening && !recordingNote
            completeClip(transcript, when {
                failed -> "VOX AI India couldn’t transcribe this recording."
                transcript.isBlank() && stableTranscript.isBlank() && !restart -> "No speech recognized."
                else -> null
            }, restart)
            if (restart) startRegionalCapture()
        }
    }

    private fun publishLevel(session: Int, data: ByteArray, count: Int) {
        val level = microphoneLevel(data, count)
        main.post {
            if (session == generation && state.status == VoxStatus.LISTENING) {
                update(state.copy(rmsLevel = level))
            }
        }
    }

    fun startNoteRecording() {
        cleanup()
        clearTranscript()
        isListening = true
        startAudioOnlyNote()
    }

    private fun startAudioOnlyNote(cause: String? = null) {
        val session = generation
        try {
            val audio = VoiceRecorder(scope)
            recorder = audio
            audio.start(onEnd = {
                main.post {
                    if (session == generation && recorder === audio) completeClip("", null, false)
                }
            })
            update(state.copy(
                status = VoxStatus.LISTENING,
                language = languageLabel,
                engineLabel = "Audio only",
                errorMessage = cause
            ))
        } catch (_: Exception) {
            cleanup()
            update(state.copy(status = VoxStatus.ERROR, errorMessage = "Microphone unavailable."))
        }
    }

    suspend fun finishCurrentPhrase() {
        pauseOrStopListening()
        offlineJob?.join()
    }

    fun pauseOrStopListening() {
        isListening = false
        if (recordingNote && recorder != null && offlineJob == null) {
            completeClip("", null, false)
            return
        }
        when (activeEngine) {
            VoiceInputEngine.MOONSHINE, VoiceInputEngine.REGIONAL -> {
                recorder?.stop()
                if (offlineJob != null) {
                    update(state.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
                } else if (recorder != null) {
                    completeClip("", null, false)
                }
            }
            VoiceInputEngine.NATIVE -> {
                runCatching { speechRecognizer?.stopListening() }
                update(state.copy(status = VoxStatus.PAUSED, rmsLevel = 0f))
            }
        }
    }

    private fun completeClip(text: String, message: String?, restart: Boolean) {
        val audio = recorder
        audio?.stop()
        recorder = null
        if (audio != null && (recordingNote || text.isNotBlank())) {
            finishedAudio?.cancel()
            finishedAudio = audio
        } else {
            audio?.cancel()
        }
        stableTranscript = listOf(stableTranscript, text).filter(String::isNotBlank).joinToString(" ")
        update(state.copy(
            status = if (restart) VoxStatus.LISTENING else VoxStatus.PAUSED,
            transcript = stableTranscript,
            finalSegment = text,
            partialTranscript = "",
            rmsLevel = 0f,
            errorMessage = message
        ))
    }

    private fun microphoneLevel(bytes: ByteArray, count: Int): Float =
        (NativeTyping.voiceRms(bytes, count) * 12f).coerceIn(0f, 1f)

    fun cleanTranscript(text: String): String =
        PronunciationAlternatives.cleanFinal(text, if (activeEngine == VoiceInputEngine.REGIONAL) regionalMode.code else languageTag, smartFilter)

    fun clearTranscript() {
        stableTranscript = ""
        update(state.copy(transcript = "", finalSegment = "", partialTranscript = "", rmsLevel = 0f, errorMessage = null))
    }

    fun cleanup() {
        androidSilenceJob?.cancel()
        androidSilenceJob = null
        isListening = false
        generation++
        main.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        offlineJob?.cancel()
        offlineJob = null
        recorder?.cancel()
        recorder = null
        finishedAudio?.cancel()
        finishedAudio = null
    }

    suspend fun finishNote(includeTranscript: Boolean = true): RecordedVoice {
        pauseOrStopListening()
        offlineJob?.join()
        val transcript = if (includeTranscript) cleanTranscript(state.transcript).take(4000) else ""
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
