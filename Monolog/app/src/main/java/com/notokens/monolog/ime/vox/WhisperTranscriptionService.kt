package com.notokens.monolog.ime.vox

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.Keep
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TRANSCRIBE = 1
private const val RESULT = 2
private const val PCM = "pcm"
private const val LANGUAGE = "language"
private const val TEXT = "text"
private const val PROVISIONAL = "provisional"
private const val MAX_PCM_BYTES = VoiceRecorder.RATE * 2 * VoiceRecorder.SECONDS

/** Keeps the model and native inference out of the keyboard and companion process. */
@Keep
class WhisperTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != TRANSCRIBE || message.sendingUid != applicationInfo.uid) return@Handler false
        val data = message.data
        val audio = data.getParcelable(PCM, SharedMemory::class.java) ?: return@Handler false
        val target = message.replyTo
        if (target == null) {
            audio.close()
            return@Handler false
        }
        val locale = data.getString(LANGUAGE).orEmpty()
        val provisional = data.getBoolean(PROVISIONAL)
        scope.launch {
            val text = try {
                audio.use {
                    if (!provisional) inference.withLock { recognize(audio, locale, false) }
                    else if (!inference.tryLock()) null
                    else try {
                        recognize(audio, locale, true)
                    } finally {
                        inference.unlock()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            try {
                target.send(Message.obtain(null, RESULT).apply {
                    this.data = Bundle().apply { putString(TEXT, text) }
                })
            } catch (_: RemoteException) {
                // The editor or note closed while inference was running.
            }
        }
        true
    })

    private fun recognize(audio: SharedMemory, language: String, provisional: Boolean): String? {
        if (audio.size !in 1..MAX_PCM_BYTES || audio.size % 2 != 0 ||
            !OfflineWhisper.ready(this) || !OfflineWhisper.hasHeadroom(this)) return null
        val samples = ByteArray(audio.size)
        val buffer = audio.mapReadOnly()
        try {
            buffer.get(samples)
        } finally {
            SharedMemory.unmap(buffer)
        }
        System.loadLibrary("monolog_whisper")
        val prompt = if (PronunciationAlternatives.supports(language)) {
            PronunciationAlternatives.terms.joinToString(", ")
        } else ""
        return transcribePcm(OfflineWhisper.modelFile(this).absolutePath, language, prompt, samples, provisional)
            ?.decodeToString()?.trim()?.take(4000)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private external fun transcribePcm(modelPath: String, language: String, prompt: String, pcm: ByteArray, provisional: Boolean): ByteArray?

    private companion object {
        val inference = Mutex()
    }
}

internal object WhisperTranscription {
    suspend fun recognize(context: Context, pcm: ByteArray, language: String, provisional: Boolean): String? {
        if (pcm.isEmpty() || pcm.size > MAX_PCM_BYTES) return null
        return SharedMemory.create("Monolog voice", pcm.size).use { audio ->
            val buffer = audio.mapReadWrite()
            try {
                buffer.put(pcm)
            } finally {
                SharedMemory.unmap(buffer)
            }
            if (!audio.setProtect(OsConstants.PROT_READ)) return@use null
            withTimeoutOrNull(if (provisional) 12_000 else 70_000) {
                withContext(Dispatchers.Main.immediate) { request(context.applicationContext, audio, language, provisional) }
            }
        }
    }

    private suspend fun request(context: Context, audio: SharedMemory, language: String, provisional: Boolean): String? =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var bound = false
            lateinit var connection: ServiceConnection

            fun unbind() {
                if (bound) {
                    bound = false
                    context.unbindService(connection)
                }
            }

            fun complete(text: String?) {
                unbind()
                if (continuation.isActive) continuation.resume(text)
            }

            val reply = Messenger(Handler(main.looper) { message ->
                if (message.what != RESULT || message.sendingUid != context.applicationInfo.uid) return@Handler false
                complete(message.data.getString(TEXT))
                true
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!continuation.isActive) return
                    try {
                        Messenger(service).send(Message.obtain(null, TRANSCRIBE).apply {
                            data = Bundle().apply {
                                putParcelable(PCM, audio)
                                putString(LANGUAGE, language)
                                putBoolean(PROVISIONAL, provisional)
                            }
                            replyTo = reply
                        })
                    } catch (_: RemoteException) {
                        complete(null)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = complete(null)
                override fun onBindingDied(name: ComponentName) = complete(null)
                override fun onNullBinding(name: ComponentName) = complete(null)
            }
            continuation.invokeOnCancellation { main.post { unbind() } }
            try {
                bound = context.bindService(
                    Intent(context, WhisperTranscriptionService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (_: IllegalStateException) {
                complete(null)
            } catch (_: SecurityException) {
                complete(null)
            }
            if (!bound) complete(null)
        }
}
