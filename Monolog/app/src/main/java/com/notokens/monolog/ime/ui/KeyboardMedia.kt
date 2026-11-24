package com.notokens.monolog.ime.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import java.nio.ByteBuffer
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
                for (format in formats) for (size in sizesInOrder) {
                    val url = sizes.optJSONObject(size)?.optJSONObject(format)?.optString("url").orEmpty()
                    val host = url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.host
                    if (host == "static.klipy.com" || host?.endsWith(".klipy.com") == true) return url to format
                }
                return null
            }
            val formats = if (tab == KeyboardMediaTab.GIFS) listOf("gif") else listOf("webp", "png", "gif")
            val (file, extension) = find(listOf("hd", "md", "sm", "xs"), formats) ?: return@mapNotNull null
            val preview = find(listOf("xs", "sm", "md"), listOf("gif", "webp"))?.first
                ?: find(listOf("xs", "sm", "md"), listOf("jpg", "png"))?.first ?: file
            val creator = entry.optJSONObject("user")?.optString("username")
                ?: entry.optJSONObject("creator")?.optString("username") ?: entry.optString("username")
            val credit = creator.ifBlank { entry.optString("source") }.trim().take(64)
            Item(entry.optString("slug").ifBlank { file }, entry.optString("title").ifBlank { tab.label }, preview, file,
                extension, credit.takeIf(String::isNotBlank))
        }.distinctBy { it.id }
    }

    suspend fun preview(item: Item): Drawable = withContext(Dispatchers.IO) {
        val bytes = fetch(item.thumbnail, 2 * 1024 * 1024)
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val dimension = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((dimension / 240).coerceAtLeast(1))
        }
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

private data class EmoticonGroup(val label: String, val entries: List<String>)

private val emoticons = listOf(
    EmoticonGroup("Classic", listOf(":)", ":-)", ":D", ":-D", ";)", ";-)", ":P", ":-P", ":(", ":-(",
        ":O", ":-O", ":/", ":-/", ":|", ":')", ":'(", "<3", "</3", "B)", "XD", "^_^", "-_-", "o_O")),
    EmoticonGroup("Happy", listOf("(•‿•)", "(*^▽^*)", "(＾▽＾)", "(≧▽≦)", "(◠‿◠)", "(✿◠‿◠)",
        "٩(◕‿◕｡)۶", "\\(^o^)/", "(⌒‿⌒)", "(´▽`)", "(๑˃̵ᴗ˂̵)", "(•̀ᴗ•́)و")),
    EmoticonGroup("Affection", listOf("(づ｡◕‿‿◕｡)づ", "(っ˘з(˘⌣˘ )", "(♡‿♡)", "(´꒳`♡)",
        "(´ε` )♡", "(｡♥‿♥｡)", "(づ￣ ³￣)づ", "<(^_^<)", "ヽ(♡‿♡)ノ", "(◕ᴗ◕✿)")),
    EmoticonGroup("Surprised", listOf("(⊙_⊙)", "(°o°)", "(O_O)", "(・o・)", "(・_・;)", "(・・?)",
        "(⊙_☉)", "(☉_☉)", "Σ(°△°)", "(゜゜)", "(*_*)", "(ʘᗩʘ’)")),
    EmoticonGroup("Sad", listOf("(╥﹏╥)", "(T_T)", "(；ω；)", "(｡•́︿•̀｡)", "(´-ω-｀)", "(._.)",
        "(ಥ_ಥ)", "(︶︹︶)", "(つ﹏⊂)", "(╯︵╰)")),
    EmoticonGroup("Gestures", listOf("¯\\_(ツ)_/¯", "(•̀ᴗ•́)و", "(ง •̀_•́)ง", "(⌐■_■)", "(•_•)>⌐■-■",
        "(╯°□°)╯︵ ┻━┻", "┬─┬ ノ( ゜-゜ノ)", "(シ_ _)シ", "m(_ _)m", "(￣^￣)ゞ")),
    EmoticonGroup("Animals", listOf("ʕ•ᴥ•ʔ", "(=^･ω･^=)", "ฅ^•ﻌ•^ฅ", "(=ↀωↀ=)", "(ᵔᴥᵔ)",
        "U・ᴥ・U", "(・ө・)", "(◉ω◉)", "(ᵒ˙ᴥ˙ᵒ)", "૮ ˶ᵔ ᵕ ᵔ˶ ა"))
)

@Composable
private fun EmoticonPicker(query: String, onSearch: () -> Unit, onClearSearch: () -> Unit, onPick: (String) -> Unit) {
    var category by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val vibration = LocalVibrationHaptics.current
    val entries = remember(query, category) {
        if (query.isBlank()) emoticons[category].entries
        else emoticons.flatMap { group ->
            if (group.label.contains(query, ignoreCase = true)) group.entries
            else group.entries.filter { it.contains(query, ignoreCase = true) }
        }.distinct()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items(emoticons.indices.toList()) { index ->
                PickerTab(emoticons[index].label, category == index && query.isBlank()) {
                    onClearSearch()
                    category = index
                }
            }
        }
        PickerSearch(query.ifBlank { "Search emoticons" }, onSearch)
    }
    if (query.isNotBlank()) Text(query, Modifier.padding(start = 6.dp), color = PaperKeySubText, fontSize = 11.sp)
    if (entries.isEmpty()) Text("No matching emoticons", Modifier.padding(8.dp), color = PaperKeySubText)
    else LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(entries, key = { it }) { text ->
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f))
                .clickable(role = Role.Button) {
                    triggerHapticFeedback(view, enabled = vibration)
                    onPick(text)
                }, contentAlignment = Alignment.Center) {
                Text(text, Modifier.padding(horizontal = 3.dp), color = PaperKeyText,
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                    fontSize = if (text.length > 11) 11.sp else 15.sp)
            }
        }
    }
}

@Composable
internal fun PickerTab(label: String, active: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
        .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
        .semantics { selected = active }.padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else if (enabled) PaperKeyText else PaperKeySubText.copy(alpha = .55f),
            fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
internal fun PickerSearch(label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MonologIcon(Glyph.SEARCH, Modifier.size(19.dp), PaperKeyText)
    }
}

@Composable
internal fun KeyboardMediaPicker(
    tab: KeyboardMediaTab, onTab: (KeyboardMediaTab) -> Unit, height: androidx.compose.ui.unit.Dp,
    recent: List<Emoji>, mediaAllowed: Boolean, mediaOptIn: Boolean, query: String,
    onSearch: () -> Unit, onClearSearch: () -> Unit, onEnable: () -> Unit, onEmoji: (Emoji) -> Unit, onText: (String) -> Unit,
    onMedia: (KeyboardMedia.Item) -> Boolean
) {
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (choice in KeyboardMediaTab.entries) {
                val enabled = mediaAllowed || choice == KeyboardMediaTab.EMOJI || choice == KeyboardMediaTab.EMOTICONS
                PickerTab(choice.label, choice == tab, Modifier.weight(1f), enabled) { onTab(choice) }
            }
        }
        when (tab) {
            KeyboardMediaTab.EMOJI -> EmojiPicker((height - 38.dp).coerceAtLeast(45.dp), recent, query, onSearch, onClearSearch, onEmoji)
            KeyboardMediaTab.EMOTICONS -> EmoticonPicker(query, onSearch, onClearSearch, onText)
            else -> when {
                !mediaAllowed -> Text("Online media is unavailable in this field.", color = PaperKeySubText)
                !KeyboardMedia.configured -> Text("KLIPY is awaiting setup.", Modifier.padding(8.dp), color = PaperKeySubText)
                !mediaOptIn -> Column(Modifier.padding(horizontal = 8.dp)) {
                    Text("GIF and sticker searches share the terms you submit with KLIPY. Its catalog may contain ads.",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                    TextButton(onEnable) { Text("Enable online media") }
                }
                else -> KlipyResults(tab, query, onSearch, onClearSearch, onMedia)
            }
        }
    }
}

@Composable
private fun KlipyResults(tab: KeyboardMediaTab, query: String, onSearch: () -> Unit,
    onClearSearch: () -> Unit, onMedia: (KeyboardMedia.Item) -> Boolean) {
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
        if (query.isNotBlank()) IconControl(Glyph.CLOSE, "Clear media search", onClearSearch)
        Text("Powered by KLIPY", Modifier.padding(end = 5.dp), color = PaperKeySubText, fontSize = 10.sp)
    }
    if (issue.isNotBlank()) Text(issue, color = PaperKeySubText, fontSize = 11.sp)
    val entries = results
    if (entries == null) Text("Loading media…", color = PaperKeySubText)
    else if (entries.isEmpty() && issue.isBlank()) Text("No media found", color = PaperKeySubText)
    else LazyVerticalGrid(GridCells.Adaptive(66.dp), Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(entries, key = { it.id }) { item ->
            val preview by produceState<Drawable?>(null, item.thumbnail) {
                value = try { KeyboardMedia.preview(item) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            Column(Modifier.clickable {
                if (!onMedia(item)) issue = "This field doesn’t accept this media."
            }) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    val image = preview
                    if (image != null) AnimatedMediaPreview(image, item.name)
                    else Text(item.name, color = PaperKeySubText, fontSize = 10.sp, maxLines = 2)
                }
                item.attribution?.let { credit ->
                    Text(credit, Modifier.padding(horizontal = 2.dp), maxLines = 1, fontSize = 9.sp, color = PaperKeySubText)
                }
            }
        }
    }
}

@Composable
private fun AnimatedMediaPreview(drawable: Drawable, label: String) {
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
        update = { view ->
            if (view.drawable !== drawable) {
                (view.drawable as? AnimatedImageDrawable)?.stop()
                view.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            }
        },
        onRelease = { view ->
            (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(null)
        }
    )
}
