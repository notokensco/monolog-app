package com.notokens.monolog.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ime.vox.RecordedVoice
import com.notokens.monolog.ime.vox.VoiceRecorder
import com.notokens.monolog.ime.vox.VoxController
import com.notokens.monolog.ime.vox.VoxState
import com.notokens.monolog.ime.vox.VoxStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VoiceNoteControl(
    enabled: Boolean,
    onPermission: () -> Unit,
    onVoice: (RecordedVoice) -> Unit,
    onBusy: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val language by settings.keyboardLanguage.collectAsState(initial = "en")
    val incognito by settings.incognito.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var captions by remember { mutableStateOf(VoxState()) }
    val vox = remember(context, scope) { VoxController(context, scope, recordingNote = true) { captions = it } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var recording by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Job?>(null) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val busyCallback by rememberUpdatedState(onBusy)
    val voiceCallback by rememberUpdatedState(onVoice)
    fun cancel() {
        saving?.cancel()
        vox.cleanup()
        vox.clearTranscript()
        recording = false
        processing = false
        busyCallback(false)
    }
    DisposableEffect(vox, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && (recording || processing)) cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (recording || processing) cancel() else vox.cleanup()
        }
    }
    LaunchedEffect(incognito, enabled) {
        if ((incognito || !enabled) && (recording || processing)) cancel()
    }
    fun finish() {
        if (!recording) return
        recording = false
        processing = true
        saving = scope.launch {
            try {
                val voice = vox.finishNote()
                if (!settings.incognito.first()) {
                    if (voice.audio != null || voice.transcript.isNotBlank()) {
                        error = when {
                            voice.transcript.isBlank() -> "Audio ready. No captions were available."
                            voice.audio == null -> "Transcript ready. Audio was unavailable."
                            else -> ""
                        }
                        voiceCallback(voice)
                    } else error = captions.errorMessage ?: "Nothing was recorded. Tap Vox to try again."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn’t finish the voice note. Try again." }
            finally {
                vox.clearTranscript()
                processing = false
                busyCallback(false)
            }
        }
    }
    LaunchedEffect(recording, captions.status) {
        if (recording && (captions.status == VoxStatus.PAUSED || captions.status == VoxStatus.ERROR ||
                captions.status == VoxStatus.UNAVAILABLE)) finish()
    }
    LaunchedEffect(recording) {
        if (recording) {
            delay(VoiceRecorder.SECONDS * 1000L)
            finish()
        }
    }
    Column {
        TextButton(enabled = enabled && !incognito && !processing, onClick = {
            if (recording) finish()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onPermission()
            } else {
                error = ""
                vox.clearTranscript()
                val selected = KeyboardLanguage.of(language)
                vox.startListening(selected.speechTag, selected.label)
                recording = captions.status == VoxStatus.LISTENING || captions.status == VoxStatus.PROCESSING
                if (recording) busyCallback(true) else error = captions.errorMessage ?: "Microphone unavailable."
            }
        }) {
            MonologIcon(Glyph.MIC, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(when {
                recording -> "Stop recording"
                processing -> "Finishing voice note…"
                else -> "Vox voice note"
            })
        }
        val caption = listOf(captions.transcript, captions.partialTranscript).filter(String::isNotBlank).joinToString(" ")
        if ((recording || processing) && caption.isNotBlank()) {
            Text(caption, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = PaperKeySubText)
        }
        if (recording) captions.errorMessage?.let { Text(it, color = PaperKeySubText) }
        if (error.isNotBlank()) Text(error, color = PaperKeySubText)
    }
}

@Composable
internal fun VoiceNotePlayback(audio: String, label: String = "Play voice note") {
    var player by remember(audio) { mutableStateOf<MediaPlayer?>(null) }
    val active = rememberUpdatedState(player)
    DisposableEffect(audio) { onDispose { active.value?.release() } }
    TextButton(onClick = {
        if (player != null) {
            player?.release()
            player = null
            return@TextButton
        }
        val handle = MediaPlayer()
        try {
            val bytes = Base64.decode(audio, Base64.DEFAULT)
            handle.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            handle.setOnPreparedListener { it.start() }
            handle.setOnCompletionListener { it.release(); player = null }
            handle.setOnErrorListener { current, _, _ -> current.release(); player = null; true }
            player = handle
            handle.prepareAsync()
        } catch (_: Exception) {
            handle.release()
            player = null
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonologIcon(Glyph.MIC, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (player != null) "Stop audio" else label)
        }
    }
}
