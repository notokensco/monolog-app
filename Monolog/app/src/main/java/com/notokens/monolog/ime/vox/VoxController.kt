package com.notokens.monolog.ime.vox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

class VoxController(
    private val context: Context,
    private val onStateUpdated: (VoxState) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentState = VoxState()

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState(
                currentState.copy(
                    status = VoxStatus.PERMISSION_REQUIRED,
                    errorMessage = "Microphone permission required for Vox mode."
                )
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateState(
                currentState.copy(
                    status = VoxStatus.UNAVAILABLE,
                    errorMessage = "Speech recognition is not available on this device."
                )
            )
            return
        }

        cleanup()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            updateState(currentState.copy(status = VoxStatus.LISTENING, errorMessage = null))
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            updateState(
                currentState.copy(
                    status = VoxStatus.ERROR,
                    errorMessage = "Could not start voice recognizer: ${e.message}"
                )
            )
        }
    }

    fun pauseOrStopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        updateState(currentState.copy(status = VoxStatus.PAUSED, rmsLevel = 0f))
    }

    fun clearTranscript() {
        updateState(currentState.copy(transcript = "", rmsLevel = 0f))
    }

    fun cleanup() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun updateState(newState: VoxState) {
        currentState = newState
        onStateUpdated(newState)
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            updateState(currentState.copy(status = VoxStatus.LISTENING, errorMessage = null))
        }

        override fun onBeginningOfSpeech() {
            updateState(currentState.copy(status = VoxStatus.LISTENING))
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize dB (typically -2 to 10) to 0.0 .. 1.0 range for the visualizer
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            updateState(currentState.copy(rmsLevel = normalized))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            updateState(currentState.copy(status = VoxStatus.PROCESSING, rmsLevel = 0f))
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection required"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Tap mic to try again."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer busy"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap mic to speak."
                else -> "Recognition error ($error)"
            }
            updateState(currentState.copy(status = VoxStatus.ERROR, errorMessage = message, rmsLevel = 0f))
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            val newTranscript = if (currentState.transcript.isBlank()) {
                text
            } else {
                "${currentState.transcript.trim()} $text"
            }
            updateState(currentState.copy(status = VoxStatus.PAUSED, transcript = newTranscript, rmsLevel = 0f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                updateState(currentState.copy(transcript = partial))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
