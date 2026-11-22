package com.notokens.monolog.ai

import androidx.annotation.Keep
import android.os.Process
import com.notokens.monolog.BuildConfig
import java.io.File

@Keep
internal object GgufRuntime {
    val available get() = BuildConfig.MONOLOG_HAS_GGUF && Process.is64Bit()
    private val loaded by lazy {
        if (!available) false else try {
            System.loadLibrary("monolog_gguf")
            true
        } catch (_: LinkageError) {
            false
        }
    }

    fun complete(model: File, prompt: String, tokenLimit: Int): String? {
        if (!loaded || !model.isFile) return null
        val input = prompt.replace("<|", "< |").replace("<think>", "< think>").replace("</think>", "</ think>")
        val chat = "<|im_start|>user\n$input<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        return generate(model.path, chat.encodeToByteArray(), tokenLimit.coerceIn(1, 192))
            ?.decodeToString()?.trim()?.substringAfterLast("</think>")?.trim()
    }

    private external fun generate(path: String, prompt: ByteArray, tokenLimit: Int): ByteArray?
}
