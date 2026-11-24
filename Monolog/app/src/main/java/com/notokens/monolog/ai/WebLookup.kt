package com.notokens.monolog.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import kotlinx.coroutines.withContext
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

internal enum class WebSearchKind(val id: String) { WEB("web"), IMAGES("images") }

internal enum class WebSearchProvider(val id: String, val label: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"), BRAVE("brave", "Brave Search")
}

internal data class WebSource(
    val title: String, val url: String, val snippet: String = "", val thumbnail: String? = null
)
internal data class WebAnswer(val text: String, val sources: List<WebSource>)

internal object WebLookup {
    val configured: Boolean get() = BuildConfig.MONOLOG_WEB_ENDPOINT.isNotBlank()

    val searchProvider: WebSearchProvider?
        get() = when {
            BuildConfig.MONOLOG_BRAVE_SEARCH_ENDPOINT.isNotBlank() -> WebSearchProvider.BRAVE
            BuildConfig.MONOLOG_DUCKDUCKGO_ENDPOINT.isNotBlank() -> WebSearchProvider.DUCKDUCKGO
            else -> null
        }

    fun openSearchApp(
        context: Context, query: String, safeSearch: Boolean, kind: WebSearchKind = WebSearchKind.WEB
    ): Boolean {
        val text = query.trim().take(600)
        if (text.isEmpty()) return false
        val address = Uri.Builder().scheme("https")
            .authority(if (safeSearch) "safe.duckduckgo.com" else "duckduckgo.com")
            .path("/")
            .appendQueryParameter("q", text)
            .appendQueryParameter("kp", if (safeSearch) "1" else "-2")
            .apply {
                if (kind == WebSearchKind.IMAGES) {
                    appendQueryParameter("iax", "images")
                    appendQueryParameter("ia", "images")
                }
            }.build()
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, address).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun ask(submittedQuery: String, safeSearch: Boolean, kind: WebSearchKind): WebAnswer {
        val provider = searchProvider ?: throw IOException("Search service unavailable.")
        val endpoint = when (provider) {
            WebSearchProvider.BRAVE -> BuildConfig.MONOLOG_BRAVE_SEARCH_ENDPOINT
            WebSearchProvider.DUCKDUCKGO -> BuildConfig.MONOLOG_DUCKDUCKGO_ENDPOINT
        }
        val payload = JSONObject().put("query", submittedQuery.trim().take(400))
            .put("safeSearch", safeSearch).put("kind", kind.id)
        val response = request(URI.create(endpoint).toURL(), payload)
        if (response.optString("engine") != provider.id || response.opt("safeSearch") != safeSearch ||
            response.optString("kind", WebSearchKind.WEB.id) != kind.id) {
            throw IOException("The search service did not confirm the provider, result type, and Safe Search setting.")
        }
        val entries = response.optJSONArray("results") ?: throw IOException("Invalid search response.")
        val sources = (0 until minOf(entries.length(), 12)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val result = source(entry) ?: return@mapNotNull null
            val thumbnail = safeThumbnail(entry.optString("thumbnail"), provider)
            result.copy(thumbnail = thumbnail).takeIf { kind != WebSearchKind.IMAGES || thumbnail != null }
        }.distinctBy { if (kind == WebSearchKind.IMAGES) it.thumbnail else it.url }
            .take(if (kind == WebSearchKind.IMAGES) 8 else 5)
        return WebAnswer(sources.joinToString("\n\n") { result ->
            if (result.snippet.isBlank()) result.title else "${result.title} — ${result.snippet}"
        }, sources)
    }

    private fun safeThumbnail(value: String, provider: WebSearchProvider): String? {
        if (value.isBlank() || value.length > 4096) return null
        val uri = runCatching { URI.create(value) }.getOrNull() ?: return null
        val host = when (provider) {
            WebSearchProvider.BRAVE -> "imgs.search.brave.com"
            WebSearchProvider.DUCKDUCKGO -> "external-content.duckduckgo.com"
        }
        return value.takeIf { uri.scheme == "https" && uri.host == host && uri.userInfo == null &&
            uri.port in listOf(-1, 443) }
    }

    suspend fun weather(intent: ToolbarLookup, city: String): WebAnswer {
        val raw = request(providerUrl(), JSONObject().put("kind", "rain").put("city", city.trim().take(80))
            .put("date", intent.date.toString()))
        return parse(raw) ?: throw IOException("No sourced forecast.")
    }

    suspend fun thumbnail(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val provider = searchProvider ?: return@withContext null
        val address = safeThumbnail(url, provider) ?: return@withContext null
        val bytes = requestBytes(URI.create(address).toURL(), limit = 768 * 1024)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val ratio = maxOf(info.size.width, info.size.height) / 320
            decoder.setTargetSampleSize(ratio.coerceAtLeast(1))
        }
    }

    private fun providerUrl() = URI.create(BuildConfig.MONOLOG_WEB_ENDPOINT).toURL()

    private suspend fun request(endpoint: java.net.URL, payload: JSONObject): JSONObject =
        JSONObject(requestBytes(endpoint, payload).toString(Charsets.UTF_8))

    private suspend fun requestBytes(
        endpoint: java.net.URL, payload: JSONObject? = null, limit: Int = 65_536
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpsURLConnection?>()
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = endpoint.openConnection() as HttpsURLConnection
                active.set(connection)
                try {
                    if (!continuation.isActive) return@launch
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = if (payload == null) "GET" else "POST"
                    connection.setRequestProperty("User-Agent", "Monolog/1.0 (https://github.com/notokensco/monolog-app) Android")
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 10_000
                    if (payload != null) {
                        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    if (connection.responseCode != 200) throw IOException("Web lookup did not return a result.")
                    val raw = connection.inputStream.use { input ->
                        var total = 0
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            total += length
                            if (total > limit) throw IOException()
                            output.write(buffer, 0, length)
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(raw)
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
