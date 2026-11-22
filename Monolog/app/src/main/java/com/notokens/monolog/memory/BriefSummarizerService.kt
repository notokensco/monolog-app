package com.notokens.monolog.memory

import android.app.ActivityManager
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
import android.os.SystemClock
import com.google.mediapipe.tasks.text.textsummarizer.TextSummarizer
import com.google.mediapipe.tasks.text.textsummarizer.TextSummarizer.TextSummarizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SUMMARIZE = 1
private const val RESULT = 2
private const val BODY = "body"
internal const val BRIEF_INPUT_BYTES = 1_200

private fun hasBriefHeadroom(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    if (manager.isLowRamDevice) return false
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    // Leave the system's low-memory reserve in addition to the model's working memory.
    return !memory.lowMemory && memory.availMem - memory.threshold >= 448L * 1024 * 1024
}

/** Runs MediaPipe in the private :journal_brief process so native failures cannot close the journal. */
class BriefSummarizerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != SUMMARIZE || message.sendingUid != applicationInfo.uid) return@Handler false
        val target = message.replyTo ?: return@Handler false
        val source = message.data.getString(BODY).orEmpty()
        scope.launch {
            val summary = try {
                if (source.isBlank() || source.toByteArray().size > BRIEF_INPUT_BYTES ||
                    !BriefModelStore.installed(this@BriefSummarizerService) ||
                    !hasBriefHeadroom(this@BriefSummarizerService) || !inference.tryLock()) {
                    null
                } else try {
                    summarize(source)
                } finally {
                    inference.unlock()
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
                    data = Bundle().apply { putString(BODY, summary) }
                })
            } catch (_: RemoteException) {
                // The journal closed or requested a newer recap.
            }
        }
        true
    })

    private fun summarize(source: String): String? {
        val options = TextSummarizerOptions.builder()
            .setModelPath(BriefModelStore.modelFile(this).absolutePath)
            .setMode(TextSummarizerOptions.Mode.TLDR)
            .setMaxNumTokens(768)
            .build()
        return TextSummarizer.createFromOptions(this, options).use { summarizer ->
            summarizer.summarize(source).summary?.trim()?.take(900)?.takeIf(String::isNotBlank)
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val inference = Mutex()
    }
}

internal object BriefSummarizer {
    private val request = Mutex()
    private var retryAfter = 0L

    suspend fun summarize(context: Context, source: String, retry: Boolean): String? =
        withContext(Dispatchers.Main.immediate) {
            if (!BriefModelStore.supported || !BriefModelStore.installed(context) || !hasBriefHeadroom(context) ||
                (!retry && SystemClock.elapsedRealtime() < retryAfter) || !request.tryLock()) return@withContext null
            try {
                val result = withTimeoutOrNull(25_000) { sendRequest(context.applicationContext, source) }
                retryAfter = if (result == null) SystemClock.elapsedRealtime() + 60_000 else 0L
                result
            } finally {
                request.unlock()
            }
        }

    private suspend fun sendRequest(context: Context, source: String): String? =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var bound = false
            lateinit var connection: ServiceConnection
            fun unbind() {
                if (!bound) return
                bound = false
                context.unbindService(connection)
            }
            fun complete(value: String?) {
                unbind()
                if (continuation.isActive) continuation.resume(value)
            }
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what != RESULT) return@Handler false
                complete(message.data.getString(BODY))
                true
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!continuation.isActive) return
                    try {
                        Messenger(service).send(Message.obtain(null, SUMMARIZE).apply {
                            data = Bundle().apply { putString(BODY, source) }
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
                    Intent(context, BriefSummarizerService::class.java), connection, Context.BIND_AUTO_CREATE
                )
            } catch (_: IllegalStateException) {
                complete(null)
            } catch (_: SecurityException) {
                complete(null)
            }
            if (!bound) complete(null)
        }
}
