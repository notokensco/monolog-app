package com.notokens.monolog.ai

import com.notokens.monolog.BuildConfig
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class WebSource(val title: String, val url: String)
internal data class WebAnswer(val text: String, val sources: List<WebSource>)

internal object WebLookup {
    val configured: Boolean get() = BuildConfig.MONOLOG_WEB_ENDPOINT.isNotBlank()

    suspend fun ask(submittedQuery: String): WebAnswer = request(
        JSONObject().put("kind", "search").put("query", submittedQuery.trim().take(600))
    )

    suspend fun weather(intent: ToolbarLookup, city: String): WebAnswer = request(
        JSONObject().put("kind", "rain").put("city", city.trim().take(80)).put("date", intent.date.toString())
    )

    private suspend fun request(payload: JSONObject): WebAnswer = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpsURLConnection?>()
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!configured) throw IOException("Web search is set up separately for this build.")
                val endpoint = URI.create(BuildConfig.MONOLOG_WEB_ENDPOINT).toURL()
                val connection = endpoint.openConnection() as HttpsURLConnection
                active.set(connection)
                try {
                    if (!continuation.isActive) return@launch
                    val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 10_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    if (connection.responseCode != 200) throw IOException("Web lookup did not return a result.")
                    val raw = connection.inputStream.use { input ->
                        var total = 0
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            total += length
                            if (total > 65_536) throw IOException()
                            output.write(buffer, 0, length)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    val answer = parse(raw) ?: throw IOException("No verified web sources were returned.")
                    if (continuation.isActive) continuation.resume(answer)
                } finally {
                    active.set(null)
                    connection.disconnect()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            active.getAndSet(null)?.disconnect()
            job.cancel()
        }
    }

    private fun parse(raw: String): WebAnswer? {
        val json = JSONObject(raw)
        val answer = json.optString("answer").trim().take(1800)
        val references = json.optJSONArray("sources") ?: return null
        val sources = (0 until minOf(references.length(), 5)).mapNotNull { index ->
            val source = references.optJSONObject(index) ?: return@mapNotNull null
            val link = source.optString("url")
            val uri = runCatching { URI.create(link) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@mapNotNull null
            WebSource(source.optString("title").ifBlank { uri.host }.take(100), link)
        }
        return if (answer.isBlank() || sources.isEmpty()) null else WebAnswer(answer, sources)
    }
}
