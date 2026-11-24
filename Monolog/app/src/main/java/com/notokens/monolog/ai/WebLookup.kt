package com.notokens.monolog.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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

internal data class WebSource(val title: String, val url: String, val snippet: String = "")
internal data class WebAnswer(val text: String, val sources: List<WebSource>)

internal object WebLookup {
    val configured: Boolean get() = BuildConfig.MONOLOG_WEB_ENDPOINT.isNotBlank()

    val searchConfigured: Boolean get() = BuildConfig.MONOLOG_DUCKDUCKGO_ENDPOINT.isNotBlank()

    fun openSearchApp(context: Context, query: String, safeSearch: Boolean): Boolean {
        val text = query.trim().take(600)
        if (text.isEmpty()) return false
        val address = Uri.Builder().scheme("https")
            .authority(if (safeSearch) "safe.duckduckgo.com" else "duckduckgo.com")
            .path("/")
            .appendQueryParameter("q", text)
            .appendQueryParameter("kp", if (safeSearch) "1" else "-2")
            .build()
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, address).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun ask(submittedQuery: String, safeSearch: Boolean): WebAnswer {
        val endpoint = URI.create(BuildConfig.MONOLOG_DUCKDUCKGO_ENDPOINT).toURL()
        val payload = JSONObject().put("query", submittedQuery.trim().take(600)).put("safeSearch", safeSearch)
        val response = request(endpoint, payload)
        if (response.optString("engine") != "duckduckgo" || response.opt("safeSearch") != safeSearch) {
            throw IOException("The search service did not confirm the provider and Safe Search setting.")
        }
        val entries = response.optJSONArray("results") ?: throw IOException("No DuckDuckGo results.")
        val sources = (0 until minOf(entries.length(), 12)).mapNotNull { index ->
            entries.optJSONObject(index)?.let(::source)
        }.distinctBy { it.url }.take(4)
        if (sources.isEmpty()) throw IOException("No DuckDuckGo results.")
        return WebAnswer(sources.joinToString("\n\n") { result ->
            if (result.snippet.isBlank()) result.title else "${result.title} — ${result.snippet}"
        }, sources)
    }

    suspend fun weather(intent: ToolbarLookup, city: String): WebAnswer {
        val raw = request(providerUrl(), JSONObject().put("kind", "rain").put("city", city.trim().take(80))
            .put("date", intent.date.toString()))
        return parse(raw) ?: throw IOException("No sourced forecast.")
    }

    private fun providerUrl() = URI.create(BuildConfig.MONOLOG_WEB_ENDPOINT).toURL()

    private suspend fun request(endpoint: java.net.URL, payload: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpsURLConnection?>()
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = endpoint.openConnection() as HttpsURLConnection
                active.set(connection)
                try {
                    if (!continuation.isActive) return@launch
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("User-Agent", "Monolog/1.0 (https://github.com/notokensco/monolog-app) Android")
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 10_000
                    val bytes = payload.toString().toByteArray(Charsets.UTF_8)
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
                    if (continuation.isActive) continuation.resume(JSONObject(raw))
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

    private fun source(json: JSONObject): WebSource? {
        val link = json.optString("url")
        val uri = runCatching { URI.create(link) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return WebSource(json.optString("title").ifBlank { uri.host }.take(100), link,
            json.optString("content").trim().take(240))
    }

    private fun parse(json: JSONObject): WebAnswer? {
        val answer = json.optString("answer").trim().take(1800)
        val references = json.optJSONArray("sources") ?: return null
        val sources = (0 until minOf(references.length(), 5)).mapNotNull { index ->
            val entry = references.optJSONObject(index) ?: return@mapNotNull null
            source(entry)
        }
        return if (answer.isBlank() || sources.isEmpty()) null else WebAnswer(answer, sources)
    }
}
