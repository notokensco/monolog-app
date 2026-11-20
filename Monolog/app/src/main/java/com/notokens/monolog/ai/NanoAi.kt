package com.notokens.monolog.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal enum class NanoFeature(val label: String) {
    PROMPT("Ask and images"), REWRITE("Rewrite"), DESCRIPTION("Image description")
}

internal enum class NanoAvailability(val label: String) {
    READY("Ready"), DOWNLOADABLE("Download available"), DOWNLOADING("Downloading"), UNAVAILABLE("Unavailable")
}

internal object NanoAi {
    private val prompt by lazy { Generation.getClient() }
    private val retryAfter = ConcurrentHashMap<NanoFeature, Long>()

    suspend fun status(context: Context, feature: NanoFeature): NanoAvailability =
        optional {
            LocalMlKit.initialize(context)
            when (feature) {
                NanoFeature.PROMPT -> prompt.checkStatus()
                NanoFeature.REWRITE -> withRewriter(context, RewriteStyle.FRIENDLY) { it.checkFeatureStatus().await() }
                NanoFeature.DESCRIPTION -> withDescriber(context) { it.checkFeatureStatus().await() }
            }.let(::availability)
        } ?: NanoAvailability.UNAVAILABLE

    suspend fun download(context: Context, feature: NanoFeature): NanoAvailability {
        if (status(context, feature) != NanoAvailability.DOWNLOADABLE) return status(context, feature)
        when (feature) {
            NanoFeature.PROMPT -> prompt.download().collect()
            NanoFeature.REWRITE -> withRewriter(context, RewriteStyle.FRIENDLY) { client ->
                client.downloadFeature(object : DownloadCallback {}).await()
            }
            NanoFeature.DESCRIPTION -> withDescriber(context) { client ->
                client.downloadFeature(object : DownloadCallback {}).await()
            }
        }
        retryAfter.remove(feature)
        return status(context, feature)
    }

    suspend fun answer(context: Context, question: String, bitmap: Bitmap? = null): String? =
        attempt(context, NanoFeature.PROMPT) {
            if (prompt.checkStatus() != FeatureStatus.AVAILABLE) return@attempt null
            val content = if (bitmap == null) {
                generateContentRequest(TextPart(question)) { maxOutputTokens = 144 }
            } else {
                generateContentRequest(ImagePart(bitmap), TextPart(question)) { maxOutputTokens = 144 }
            }
            prompt.generateContent(content).candidates.firstOrNull()?.text?.trim()?.takeIf(String::isNotBlank)
        }

    suspend fun rewrite(context: Context, draft: String, style: RewriteStyle): String? =
        attempt(context, NanoFeature.REWRITE) { runRewrite(context, draft, style) }

    suspend fun caption(context: Context, bitmap: Bitmap): String? = attempt(context, NanoFeature.DESCRIPTION) {
        withDescriber(context) { client ->
            if (client.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return@withDescriber null
            client.runInference(ImageDescriptionRequest.builder(bitmap).build()).await().description
                .trim().takeIf(String::isNotBlank)
        }
    }

    suspend fun probe(context: Context): String {
        val start = SystemClock.elapsedRealtime()
        val result = try {
            LocalMlKit.initialize(context)
            withTimeoutOrNull(12_000L) {
                if (runRewrite(context, "Could you send the report by Friday?", RewriteStyle.FRIENDLY) != null)
                    "Success" else "Unavailable"
            } ?: "Timed out"
        } catch (error: CancellationException) {
            throw error
        } catch (error: GenAiException) {
            "AICore error " + error.errorCode
        } catch (_: Exception) {
            "Nano request failed"
        }
        return result + " · " + (SystemClock.elapsedRealtime() - start) + " ms"
    }

    private suspend fun runRewrite(context: Context, draft: String, style: RewriteStyle): String? =
        withRewriter(context, style) { client ->
            if (client.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return@withRewriter null
            client.runInference(RewritingRequest.builder(draft).build()).await().results
                .firstOrNull()?.text?.trim()?.takeIf(String::isNotBlank)
        }

    private suspend fun <T> withRewriter(
        context: Context,
        style: RewriteStyle,
        work: suspend (com.google.mlkit.genai.rewriting.Rewriter) -> T
    ): T {
        val client = Rewriting.getClient(
            RewriterOptions.builder(context)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .setOutputType(when (style) {
                    RewriteStyle.POLISH -> RewriterOptions.OutputType.REPHRASE
                    RewriteStyle.SHORTEN -> RewriterOptions.OutputType.SHORTEN
                    RewriteStyle.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
                    RewriteStyle.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
                }).build()
        )
        return try { work(client) } finally { client.close() }
    }

    private suspend fun <T> withDescriber(
        context: Context,
        work: suspend (com.google.mlkit.genai.imagedescription.ImageDescriber) -> T
    ): T {
        val client = ImageDescription.getClient(ImageDescriberOptions.builder(context).build())
        return try { work(client) } finally { client.close() }
    }

    private suspend fun attempt(context: Context, feature: NanoFeature, work: suspend () -> String?): String? {
        if ((retryAfter[feature] ?: 0L) > SystemClock.elapsedRealtime()) return null
        return optional {
            LocalMlKit.initialize(context)
            work()
        } ?: run {
            retryAfter[feature] = SystemClock.elapsedRealtime() + 60_000L
            null
        }
    }

    private fun availability(status: Int) = when (status) {
        FeatureStatus.AVAILABLE -> NanoAvailability.READY
        FeatureStatus.DOWNLOADABLE -> NanoAvailability.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> NanoAvailability.DOWNLOADING
        else -> NanoAvailability.UNAVAILABLE
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        withTimeoutOrNull(8_000L) { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
