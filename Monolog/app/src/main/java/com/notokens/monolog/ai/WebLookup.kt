package com.notokens.monolog.ai

import android.app.SearchManager
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

internal data class WebSource(val title: String, val url: String)
internal data class WebAnswer(val text: String, val sources: List<WebSource>)

internal object WebLookup {
    val configured: Boolean get() = BuildConfig.MONOLOG_WEB_ENDPOINT.isNotBlank()

    fun openSearchApp(context: Context, query: String): Boolean {
        val text = query.trim().take(600)
        if (text.isEmpty()) return false
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
        val browser = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", text).build()
        return listOf(search, Intent(Intent.ACTION_VIEW, browser)).any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    suspend fun ask(submittedQuery: String): WebAnswer {
        val query = submittedQuery.trim().take(600)
        if (configured) {
            val raw = request(providerUrl(), JSONObject().put("kind", "search").put("query", query))
            return parse(raw) ?: throw IOException("No sourced search results.")
        }
        if (AiText.needsWeb(query)) throw IOException("Live information requires the search service.")
        val searchTerm = AiText.encyclopediaQuery(query)
        val url = Uri.parse("https://en.wikipedia.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrsearch", searchTerm)
            .appendQueryParameter("gsrlimit", "3")
            .appendQueryParameter("prop", "extracts|info")
            .appendQueryParameter("inprop", "url")
            .appendQueryParameter("exintro", "1")
            .appendQueryParameter("explaintext", "1")
            .appendQueryParameter("exchars", "430")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2")
            .build()
        val response = request(URI.create(url.toString()).toURL())
        val pages = response.optJSONObject("query")?.optJSONArray("pages")
            ?: throw IOException("No Wikipedia results.")
        val results = (0 until pages.length()).mapNotNull { index ->
            val page = pages.optJSONObject(index) ?: return@mapNotNull null
            val id = page.optInt("pageid")
            val title = page.optString("title")
            val extract = page.optString("extract").trim().take(430)
            if (id <= 0 || title.isBlank() || extract.isBlank()) return@mapNotNull null
            val rank = if (title.equals(searchTerm, ignoreCase = true)) 0 else page.optInt("index", index + 1)
            Triple(rank, "$title: $extract", WebSource(title, "https://en.wikipedia.org/?curid=$id"))
        }.sortedBy { it.first }
        if (results.isEmpty()) throw IOException("No Wikipedia excerpts.")
        return WebAnswer(results.joinToString("\n\n") { it.second }, results.map { it.third })
    }

    suspend fun weather(intent: ToolbarLookup, city: String): WebAnswer {
        val raw = request(providerUrl(), JSONObject().put("kind", "rain").put("city", city.trim().take(80))
            .put("date", intent.date.toString()))
        return parse(raw) ?: throw IOException("No sourced forecast.")
    }

    private fun providerUrl() = URI.create(BuildConfig.MONOLOG_WEB_ENDPOINT).toURL()

    private suspend fun request(endpoint: java.net.URL, payload: JSONObject? = null): JSONObject = suspendCancellableCoroutine { continuation ->
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

    private fun parse(json: JSONObject): WebAnswer? {
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
