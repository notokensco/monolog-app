package com.notokens.monolog.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The demo credential is provisioned into private app storage, outside the APK. */
internal object CloudAi {
    private fun key(context: Context) = File(context.noBackupFilesDir, "gemini-demo.key")
    fun configured(context: Context) = key(context).isFile
    fun enabled(context: Context) = configured(context) &&
        context.getSharedPreferences("online_ai", Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("online_ai", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    suspend fun answer(context: Context, prompt: String, bitmap: Bitmap? = null): String =
        withContext(Dispatchers.IO) {
            check(enabled(context)) { "Enable Gemini online in AI settings." }
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (bitmap != null) {
                val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
                val image = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val bytes = ByteArrayOutputStream().use {
                        image.compress(Bitmap.CompressFormat.JPEG, 80, it)
                        it.toByteArray()
                    }
                    parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                } finally {
                    if (image !== bitmap) image.recycle()
                }
            }
            val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 512).put("temperature", .2)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
            val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
                .openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("x-goog-api-key", key(context).readText().trim())
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != 200) throw IOException(when (connection.responseCode) {
                    401, 403 -> "Gemini access was rejected. Check your API key."
                    429 -> "Gemini quota is temporarily unavailable. Try again shortly."
                    else -> "Gemini could not answer. Check your connection and retry."
                })
                val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val output = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: throw IOException("Gemini returned no answer.")
                (0 until output.length()).mapNotNull { output.optJSONObject(it)?.optString("text") }
                    .joinToString("\n").trim().ifBlank { throw IOException("Gemini returned no answer.") }
            } finally {
                connection.disconnect()
            }
        }
}
