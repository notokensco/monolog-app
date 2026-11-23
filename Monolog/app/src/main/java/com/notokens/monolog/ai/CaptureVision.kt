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
    fun imageKeywords(bitmap: Bitmap): List<String> {
        val names = (objects.map { it.text }.filterNot { it == "unclassified" } + labels)
            .map { it.lowercase(Locale.ROOT) }.distinct()
        val searchable = names + if ("footwear" in names) listOf("shoe", "shoes") else emptyList()
        val colors = if (bitmap.config == Bitmap.Config.HARDWARE) emptyList() else
            (objects.take(3).map { it.box } + Rect(0, 0, width, height))
            .mapNotNull { prominentColor(bitmap, it) }.distinct()
        return (searchable + colors + colors.flatMap { color -> searchable.take(3).map { "$color $it" } })
            .distinct().take(30)
    }

    private fun prominentColor(bitmap: Bitmap, box: Rect): String? {
        val left = box.left.coerceIn(0, bitmap.width)
        val right = box.right.coerceIn(left, bitmap.width)
        val top = box.top.coerceIn(0, bitmap.height)
        val bottom = box.bottom.coerceIn(top, bitmap.height)
        if (left == right || top == bottom) return null
        val palette = listOf("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")
        val counts = IntArray(palette.size)
        val hsv = FloatArray(3)
        for (row in 0 until 18) for (column in 0 until 18) {
            val x = left + (right - left - 1) * column / 17
            val y = top + (bottom - top - 1) * row / 17
            android.graphics.Color.colorToHSV(bitmap.getPixel(x, y), hsv)
            if (hsv[1] >= .38f && hsv[2] >= .22f) {
                val index = when (hsv[0]) {
                    in 15f..<44f -> 1
                    in 44f..<72f -> 2
                    in 72f..<158f -> 3
                    in 158f..<195f -> 4
                    in 195f..<253f -> 5
                    in 253f..<297f -> 6
                    in 297f..<343f -> 7
                    else -> 0
                }
                counts[index]++
            }
        }
        return counts.indices.maxByOrNull { counts[it] }?.takeIf { counts[it] >= 26 }?.let(palette::get)
    }

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
        val vision = inspect(context, bitmap)
        val evidence = buildString {
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
