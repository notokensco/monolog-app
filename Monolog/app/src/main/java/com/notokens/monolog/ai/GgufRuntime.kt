package com.notokens.monolog.ai

import androidx.annotation.Keep
import android.os.Process
import com.notokens.monolog.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Keep
internal object GgufRuntime {
    val available get() = BuildConfig.MONOLOG_HAS_GGUF && Process.is64Bit()
    private val library = lazy {
        if (!available) false else try {
            System.loadLibrary("monolog_gguf")
            true
        } catch (_: LinkageError) {
            false
        }
    }

    private val loaded by library
    private val inference = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextRequest = AtomicLong()

    fun warm(model: File) {
        if (loaded && model.isFile) check(prepare(model.path)) { "Could not load the offline text model" }
    }

    fun release() {
        if (library.isInitialized() && loaded) unload()
    }

    private external fun prepare(path: String): Boolean
    private external fun unload()

    suspend fun complete(model: File, prompt: String, tokenLimit: Int): String? {
        if (!loaded || !model.isFile) return null
        val input = prompt.replace("<|", "< |").replace("<think>", "< think>").replace("</think>", "</ think>")
        val chat = "<|im_start|>user\n$input<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val request = nextRequest.incrementAndGet()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel(request) }
            inference.launch {
                if (!continuation.isActive) return@launch
                try {
                    val answer = generate(model.path, chat.encodeToByteArray(), tokenLimit.coerceIn(1, 192), request)
                        ?.decodeToString()?.trim()?.substringAfterLast("</think>")?.trim()
                    continuation.resume(answer)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } catch (error: LinkageError) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    private external fun generate(path: String, prompt: ByteArray, tokenLimit: Int, request: Long): ByteArray?
    private external fun cancel(request: Long)
}
