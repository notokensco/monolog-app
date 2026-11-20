package com.notokens.monolog.capture

import android.content.Context
import android.graphics.Bitmap
import com.notokens.monolog.ai.LocalMlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CaptureEntry(val title: String, val body: String, val category: String)

private val categoryTerms = mapOf(
    "Shopping" to setOf("myntra", "cart", "checkout", "wishlist", "shopping", "subtotal", "coupon", "flipkart"),
    "Travel" to setOf("boarding", "itinerary", "flight", "hotel", "reservation", "departures"),
    "Events" to setOf("calendar", "rsvp", "appointment", "webinar", "schedule", "event"),
    "Food" to setOf("recipe", "ingredients", "tablespoon", "nutrition", "cuisine"),
    "Reading" to setOf("article", "newsletter", "chapter", "abstract", "references")
)
private val captureWord = Regex("[\\p{L}\\p{N}]+")
private val markdownPunctuation = Regex("[\\\\`*_{}\\[\\]<>#!|~]")
private val clockLine = Regex("[0-9]{1,2}:[0-9]{2}(?:\\s*[AP]M)?", RegexOption.IGNORE_CASE)

internal fun buildCaptureEntry(recognized: String): CaptureEntry {
    val lines = recognized.take(20_000).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val words = captureWord.findAll(lines.joinToString(" ").lowercase(Locale.ROOT)).map { it.value }.toSet()
    val category = categoryTerms.maxByOrNull { (_, terms) -> words.count { it in terms } }
        ?.takeIf { (_, terms) -> words.any { it in terms } }?.key ?: "Screen capture"
    val heading = lines.firstOrNull { line -> line.any(Char::isLetter) && !clockLine.matches(line) }
    val title = heading?.take(70) ?: category
    val body = buildString {
        append("**Category:** ").append(category)
        if (lines.isNotEmpty()) {
            append("\n\n## Text from screenshot\n\n")
            lines.joinTo(this, "\n> ", prefix = "> ") { line -> markdownPunctuation.replace(line) { "\\${it.value}" } }
        }
    }
    return CaptureEntry(title, body, category)
}

internal suspend fun recognizeScreenText(context: Context, bitmap: Bitmap): String = try {
    LocalMlKit.initialize(context)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspendCancellableCoroutine { continuation ->
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                recognizer.close()
                when {
                    task.isCanceled -> continuation.cancel()
                    task.isSuccessful -> continuation.resume(task.result.text)
                    else -> continuation.resumeWithException(checkNotNull(task.exception))
                }
            }
        } catch (error: Exception) {
            recognizer.close()
            continuation.resumeWithException(error)
        }
    }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    ""
}
