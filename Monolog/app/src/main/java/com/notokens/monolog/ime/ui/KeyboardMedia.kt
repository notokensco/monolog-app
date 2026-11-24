package com.notokens.monolog.ime.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.BuildConfig
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal enum class KeyboardMediaTab(val label: String) {
    EMOJI("Emoji"), GIFS("GIFs"), STICKERS("Stickers"), EMOTICONS("Emoticons")
}

internal object KeyboardMedia {
    val configured get() = BuildConfig.MONOLOG_KLIPY_ENDPOINT.isNotBlank()
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).build() }
    private const val maxMediaBytes = 12 * 1024 * 1024

    data class Item(
        val id: String, val name: String, val thumbnail: String, val file: String, val extension: String,
        val attribution: String?
    ) {
        val mime get() = "image/$extension"
    }

    suspend fun list(tab: KeyboardMediaTab, query: String): List<Item> {
        val address = BuildConfig.MONOLOG_KLIPY_ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegment(if (tab == KeyboardMediaTab.GIFS) "gifs" else "stickers")
            .addPathSegment(if (query.isBlank()) "trending" else "search")
            .addQueryParameter("page", "1").addQueryParameter("per_page", "16")
            .addQueryParameter("content_filter", "high")
            .addQueryParameter("locale", Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" })
        if (query.isNotBlank()) address.addQueryParameter("q", query.take(80))
        val json = JSONObject(fetch(address.build().toString(), 768 * 1024, relay = true).toString(Charsets.UTF_8))
        val entries = json.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return (0 until minOf(entries.length(), 16)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            if (entry.optString("type") == "ad") return@mapNotNull null
            val sizes = entry.optJSONObject("file") ?: return@mapNotNull null
            fun find(sizesInOrder: List<String>, formats: List<String>): Pair<String, String>? {
                for (size in sizesInOrder) for (format in formats) {
                    val url = sizes.optJSONObject(size)?.optJSONObject(format)?.optString("url").orEmpty()
                    val host = url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.host
                    if (host == "static.klipy.com" || host?.endsWith(".klipy.com") == true) return url to format
                }
                return null
            }
            val formats = if (tab == KeyboardMediaTab.GIFS) listOf("gif") else listOf("webp", "png", "gif")
            val (file, extension) = find(listOf("hd", "md", "sm", "xs"), formats) ?: return@mapNotNull null
            val preview = find(listOf("xs", "sm", "md", "hd"), listOf("jpg", "webp", "png", "gif"))?.first ?: file
            val creator = entry.optJSONObject("user")?.optString("username")
                ?: entry.optJSONObject("creator")?.optString("username") ?: entry.optString("username")
            val credit = creator.ifBlank { entry.optString("source") }.trim().take(64)
            Item(entry.optString("slug").ifBlank { file }, entry.optString("title").ifBlank { tab.label }, preview, file,
                extension, credit.takeIf(String::isNotBlank))
        }.distinctBy { it.id }
    }

    suspend fun preview(item: Item): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = fetch(item.thumbnail, 2 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 360) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun stream(item: Item, output: java.io.OutputStream) {
        val request = Request.Builder().url(item.file).build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Empty media response")
            if (!response.isSuccessful || body.contentLength() > maxMediaBytes) throw IOException("Media is unavailable")
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxMediaBytes) throw IOException("Media exceeds its size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private suspend fun fetch(url: String, limit: Int, relay: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
        if (relay) request.header("User-Agent", "Monolog for Android (https://monolog.notokens.co)")
        client.newCall(request.build()).execute().use { response ->
            val body = response.body ?: throw IOException("Empty media response")
            if (!response.isSuccessful || body.contentLength() > limit) throw IOException("Media is unavailable")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw IOException("Media is too large")
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    }
}

private val emoticons = listOf(":)", ":-)", ":D", ";)", ":P", ":(", ":o", ":/", "<3", "¯\\_(ツ)_/¯",
    "(•‿•)", "(╯°□°)╯︵ ┻━┻", "ʕ•ᴥ•ʔ", "(ง •̀_•́)ง", "(づ｡◕‿‿◕｡)づ", "(⌐■_■)")

@Composable
internal fun KeyboardMediaPicker(
    tab: KeyboardMediaTab, onTab: (KeyboardMediaTab) -> Unit, height: androidx.compose.ui.unit.Dp,
    recent: List<Emoji>, mediaAllowed: Boolean, mediaOptIn: Boolean, query: String,
    onSearch: () -> Unit, onEnable: () -> Unit, onEmoji: (Emoji) -> Unit, onText: (String) -> Unit,
    onMedia: (KeyboardMedia.Item) -> Boolean
) {
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (choice in KeyboardMediaTab.entries) {
                val enabled = mediaAllowed || choice == KeyboardMediaTab.EMOJI || choice == KeyboardMediaTab.EMOTICONS
                Text(choice.label, Modifier.clickable(enabled = enabled) { onTab(choice) }.padding(7.dp),
                    fontSize = 12.sp, color = if (choice == tab) MaterialTheme.colorScheme.primary else PaperKeySubText)
            }
        }
        when (tab) {
            KeyboardMediaTab.EMOJI -> EmojiPicker((height - 31.dp).coerceAtLeast(45.dp), recent, onEmoji)
            KeyboardMediaTab.EMOTICONS -> LazyVerticalGrid(GridCells.Adaptive(68.dp), Modifier.weight(1f)) {
                items(emoticons) { text ->
                    Text(text, Modifier.clickable { onText(text) }.padding(7.dp), color = PaperKeyText, fontSize = 14.sp)
                }
            }
            else -> when {
                !mediaAllowed -> Text("Online media is unavailable in this field.", color = PaperKeySubText)
                !KeyboardMedia.configured -> Text("KLIPY is awaiting setup.", Modifier.padding(8.dp), color = PaperKeySubText)
                !mediaOptIn -> Column(Modifier.padding(horizontal = 8.dp)) {
                    Text("GIF and sticker searches share the terms you submit with KLIPY. Its catalog may contain ads.",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                    TextButton(onEnable) { Text("Enable online media") }
                }
                else -> KlipyResults(tab, query, onSearch, onMedia)
            }
        }
    }
}

@Composable
private fun KlipyResults(tab: KeyboardMediaTab, query: String, onSearch: () -> Unit, onMedia: (KeyboardMedia.Item) -> Boolean) {
    var issue by remember(tab, query) { mutableStateOf("") }
    val results by produceState<List<KeyboardMedia.Item>?>(null, tab, query) {
        value = null
        value = try { KeyboardMedia.list(tab, query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue = "Couldn’t load media. Try another search."; emptyList() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(query.ifBlank { "Search KLIPY" }, Modifier.weight(1f).clickable(onClick = onSearch).padding(5.dp),
            color = PaperKeyText, maxLines = 1)
        Text("Powered by KLIPY", Modifier.padding(end = 5.dp), color = PaperKeySubText, fontSize = 10.sp)
    }
    if (issue.isNotBlank()) Text(issue, color = PaperKeySubText, fontSize = 11.sp)
    val entries = results
    if (entries == null) Text("Loading media…", color = PaperKeySubText)
    else if (entries.isEmpty() && issue.isBlank()) Text("No media found", color = PaperKeySubText)
    else LazyVerticalGrid(GridCells.Adaptive(66.dp), Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(entries, key = { it.id }) { item ->
            val bitmap by produceState<Bitmap?>(null, item.thumbnail) {
                value = try { KeyboardMedia.preview(item) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            Column(Modifier.clickable {
                if (!onMedia(item)) issue = "This field doesn’t accept this media."
            }) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image != null) Image(image.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(item.name, color = PaperKeySubText, fontSize = 10.sp, maxLines = 2)
                }
                item.attribution?.let { credit ->
                    Text(credit, Modifier.padding(horizontal = 2.dp), maxLines = 1, fontSize = 9.sp, color = PaperKeySubText)
                }
            }
        }
    }
}
