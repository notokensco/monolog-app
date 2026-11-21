package com.notokens.monolog.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal data class PositionedText(val text: String, val box: Rect)

internal data class VisionEvidence(
    val text: List<PositionedText>,
    val objects: List<PositionedText>,
    val labels: List<String>,
    val width: Int,
    val height: Int
) {
    fun description(): String = buildString {
        text.forEach { item -> appendLine("Text at " + position(item.box) + ": " + item.text) }
        if (labels.isNotEmpty()) append("Whole-image labels: ").appendLine(labels.joinToString())
        objects.forEach { item -> appendLine("Object at " + position(item.box) + ": " + item.text) }
    }.trim()

    private fun position(box: Rect): String = String.format(
        Locale.ROOT, "(%.0f, %.0f)–(%.0f, %.0f)%% from top left",
        100f * box.left / width, 100f * box.top / height,
        100f * box.right / width, 100f * box.bottom / height
    )
}

internal object CaptureVision {
    suspend fun inspect(context: Context, bitmap: Bitmap): VisionEvidence = supervisorScope {
        LocalMlKit.initialize(context)
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = async {
            safe(emptyList()) {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { client ->
                    client.process(image).await().textBlocks.flatMap { it.lines }
                        .mapNotNull { line -> line.boundingBox?.let { PositionedText(line.text.take(180), it) } }
                        .sortedWith(compareBy<PositionedText> { it.box.top }.thenBy { it.box.left }).take(40)
                }
            }
        }
        val labels = async {
            safe(emptyList()) {
                ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).use { client ->
                    client.process(image).await().sortedByDescending { it.confidence }.take(5).map { it.text }
                }
            }
        }
        val objects = async {
            safe(emptyList()) {
                val options = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects().enableClassification().build()
                ObjectDetection.getClient(options).use { client ->
                    client.process(image).await().take(5).map { obj ->
                        PositionedText(obj.labels.maxByOrNull { it.confidence }?.text ?: "unclassified", obj.boundingBox)
                    }
                }
            }
        }
        VisionEvidence(text.await(), objects.await(), labels.await(), bitmap.width, bitmap.height)
    }

    suspend fun ask(context: Context, bitmap: Bitmap, question: String): String {
        val instruction = AiText.answerPrompt(question) + "\nUse only what is visible in the image."
        if (CloudAi.enabled(context)) return withAiFallback(
            online = { CloudAi.answer(context, instruction, bitmap) },
            offline = { askOffline(context, bitmap, question, instruction) }
        )
        return askOffline(context, bitmap, question, instruction)
    }

    private suspend fun askOffline(context: Context, bitmap: Bitmap, question: String, instruction: String): String {
        val nano = NanoAi.answer(context, instruction, bitmap)
        if (nano != null) return AiText.checkedAnswer(question, nano) ?: AiText.UNKNOWN_ANSWER

        val vision = inspect(context, bitmap)
        val caption = NanoAi.caption(context, bitmap)
        val evidence = buildString {
            if (caption != null) appendLine("Image description: $caption")
            // Prefer readable content to status bar text and broad classifier labels.
            val content = vision.text.filter { it.box.centerY() > vision.height * .045f }
            val questionWords = Regex("""[\p{L}\p{N}]{4,}""").findAll(question.lowercase(Locale.ROOT)).map { it.value }.toSet()
            val nearby = content.flatMapIndexed { index, line ->
                if (questionWords.any { line.text.contains(it, ignoreCase = true) }) {
                    content.subList(maxOf(0, index - 1), minOf(content.size, index + 3))
                } else emptyList()
            }
            (nearby + content).distinct().take(14).forEach { appendLine(it.text) }
            if (vision.labels.isNotEmpty()) appendLine("Image labels: ${vision.labels.joinToString()}")
            val objects = vision.objects.map { it.text }.filterNot { it == "unclassified" }.distinct()
            if (objects.isNotEmpty()) appendLine("Detected objects: ${objects.joinToString()}")
        }.trim().take(1_400)
        if (evidence.isBlank()) return AiText.UNKNOWN_ANSWER
        val answer = RewritePipeline.gemma(context, AiText.answerPrompt(question, evidence), 64)
        return AiText.checkedAnswer(question, answer, evidence) ?: AiText.UNKNOWN_ANSWER
    }

    private suspend fun <T> safe(fallback: T, work: suspend () -> T): T = try {
        withTimeoutOrNull(6_000) { work() } ?: fallback
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        fallback
    }
}
