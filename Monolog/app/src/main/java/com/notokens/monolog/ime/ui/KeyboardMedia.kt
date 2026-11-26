package com.notokens.monolog.ime.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.glassSurface
import java.nio.ByteBuffer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.notokens.monolog.BuildConfig
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.collect

internal enum class KeyboardMediaTab(val label: String) {
    EMOJI("Emoji"), GIFS("GIFs"), STICKERS("Stickers"), EMOTICONS("Emoticons");

    val online get() = this == GIFS || this == STICKERS
    val searchLabel get() = if (this == EMOJI) "Search emoji" else "Search $label"
}

internal object KeyboardMedia {
    val configured get() = BuildConfig.MONOLOG_KLIPY_ENDPOINT.isNotBlank()
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).build() }
    private const val maxMediaBytes = 12 * 1024 * 1024

    data class Item(
        val id: String, val name: String, val thumbnail: String, val file: String, val extension: String,
        val attribution: String?, val smallerThumbnail: String? = null
    ) {
        val mime get() = "image/$extension"
    }

    suspend fun list(tab: KeyboardMediaTab, query: String): List<Item> = withContext(Dispatchers.IO) {
        val address = BuildConfig.MONOLOG_KLIPY_ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegment(if (tab == KeyboardMediaTab.GIFS) "gifs" else "stickers")
            .addPathSegment(if (query.isBlank()) "trending" else "search")
            .addQueryParameter("page", "1").addQueryParameter("per_page", "16")
            .addQueryParameter("content_filter", "high")
            .addQueryParameter("locale", Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" })
        if (query.isNotBlank()) address.addQueryParameter("q", query.take(80))
        val json = JSONObject(fetch(address.build().toString(), 768 * 1024, relay = true).toString(Charsets.UTF_8))
        val entries = json.optJSONObject("data")?.optJSONArray("data") ?: return@withContext emptyList()
        (0 until minOf(entries.length(), 16)).mapNotNull { index ->
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
            val preview = find(listOf("sm", "md", "xs"), listOf("gif", "webp"))?.first
                ?: find(listOf("xs", "sm", "md"), listOf("jpg", "png"))?.first ?: file
            val smaller = find(listOf("xs"), listOf("gif", "webp"))?.first?.takeUnless { it == preview }
            val creator = entry.optJSONObject("user")?.optString("username")
                ?: entry.optJSONObject("creator")?.optString("username") ?: entry.optString("username")
            val credit = creator.ifBlank { entry.optString("source") }.trim().take(64)
            Item(entry.optString("slug").ifBlank { file }, entry.optString("title").ifBlank { tab.label }, preview, file,
                extension, credit.takeIf(String::isNotBlank), smaller)
        }.distinctBy { it.id }
    }

    suspend fun preview(item: Item): Drawable = withContext(Dispatchers.IO) {
        val bytes = try {
            fetch(item.thumbnail, 2 * 1024 * 1024)
        } catch (error: IOException) {
            val fallback = item.smallerThumbnail ?: throw error
            fetch(fallback, 2 * 1024 * 1024)
        }
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val dimension = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((dimension / 360).coerceAtLeast(1))
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

    private suspend fun fetch(url: String, limit: Int, relay: Boolean = false): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
            if (relay) request.header("User-Agent", "Monolog for Android (https://monolog.notokens.co)")
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            val body = it.body ?: throw IOException("Empty media response")
                            if (!it.isSuccessful || body.contentLength() > limit) throw IOException("Media is unavailable")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > limit) throw IOException("Media is too large")
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
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
private fun EmoticonPicker(query: String, onClearSearch: () -> Unit, onPick: (String) -> Unit) {
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
    Column(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            MediaPickerMessage("No matching emoticons", Modifier.weight(1f))
        } else key(category, query) {
            LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(entries, key = { it }) { text ->
                    Box(Modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f))
                        .clickable(role = Role.Button) {
                            triggerHapticFeedback(view, enabled = vibration)
                            onPick(text)
                        }, contentAlignment = Alignment.Center) {
                        Text(text, Modifier.padding(horizontal = 4.dp), color = PaperKeyText,
                            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                            fontSize = if (text.length > 11) 11.sp else 15.sp)
                    }
                }
            }
        }
        MediaCategoryRow(emoticons.map { it.label }, emoticons[category].label.takeIf { query.isBlank() }) { name ->
            onClearSearch()
            category = emoticons.indexOfFirst { it.label == name }.coerceAtLeast(0)
        }
    }
}

@Composable
private fun PickerTab(label: String, active: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    caption: String = label, glyph: Glyph? = null, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else if (enabled) PaperKeyText else PaperKeySubText.copy(alpha = .55f)
    Box(modifier.height(35.dp).clip(RoundedCornerShape(10.dp))
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
        .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
        .semantics {
            selected = active
            if (glyph != null || caption != label) contentDescription = label
        }.padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center) {
        if (glyph != null) MonologIcon(glyph, Modifier.size(18.dp), color)
        else Text(caption, color = color,
            fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MediaCategoryRow(categories: List<String>, active: String?, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    LazyRow(modifier.height(38.dp), contentPadding = PaddingValues(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(categories, key = { it }) { category ->
            PickerTab(category, active == category) { onSelect(category) }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun KeyboardMediaSearchBar(
    tab: KeyboardMediaTab, query: String, editing: Boolean, enabled: Boolean,
    recent: List<String>, onSearch: () -> Unit, onClear: () -> Unit, onCancel: () -> Unit, onSubmit: () -> Unit,
    onRepeatSearch: (String) -> Unit, onClearHistory: () -> Unit
) {
    val scroll = rememberScrollState()
    val hasHistory = enabled && tab.online && recent.isNotEmpty()
    val historyLabel = if (tab == KeyboardMediaTab.STICKERS) "Recent sticker searches" else "Recent GIF searches"
    val clearLabel = "Clear " + historyLabel.replaceFirstChar { it.lowercaseChar() }
    var showHistory by remember(tab, editing) { mutableStateOf(false) }
    var clearOnly by remember(tab, editing) { mutableStateOf(false) }
    fun showClearAction() {
        clearOnly = true
        showHistory = true
    }
    if (editing) LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 8.dp, vertical = 4.dp)
            .glassSurface(14), verticalAlignment = Alignment.CenterVertically) {
            if (editing) MediaIcon(Glyph.BACK, "Back to ${tab.label} results", onCancel)
            Row(Modifier.weight(1f).fillMaxHeight().combinedClickable(
                enabled = enabled && (!editing || hasHistory), role = Role.Button,
                onClickLabel = tab.searchLabel, onClick = keyboardHapticAction { if (!editing) onSearch() },
                onLongClickLabel = clearLabel,
                onLongClick = if (hasHistory) keyboardHapticAction(isLong = true, action = ::showClearAction) else null
            ).padding(horizontal = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!editing) MonologIcon(Glyph.SEARCH, Modifier.size(17.dp), PaperKeySubText)
                Text(query.ifBlank { tab.searchLabel },
                    modifier = if (editing) Modifier.horizontalScroll(scroll) else Modifier,
                    color = if (query.isBlank()) PaperKeySubText else PaperKeyText,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (hasHistory) MediaIcon(Glyph.CLOCK, historyLabel, onClick = {
                clearOnly = false
                showHistory = true
            }, onLongClickLabel = clearLabel, onLongClick = ::showClearAction)
            if (query.isNotEmpty()) MediaIcon(Glyph.CLOSE, if (editing) "Clear search text" else "Clear search results", onClear)
            if (editing) MediaIcon(Glyph.SEARCH, "Show ${tab.label} results", onSubmit)
        }
        DropdownMenu(showHistory && hasHistory, { showHistory = false }, Modifier.widthIn(min = 176.dp, max = 280.dp)
            .heightIn(max = 250.dp), properties = PopupProperties(focusable = false)) {
            if (clearOnly) {
                DropdownMenuItem(text = { Text(clearLabel) }, onClick = keyboardHapticAction {
                    onClearHistory()
                    showHistory = false
                })
            } else {
                Text(historyLabel, Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    color = PaperKeySubText, style = MaterialTheme.typography.labelMedium)
                for (item in recent) {
                    Row(Modifier.fillMaxWidth().height(42.dp).combinedClickable(
                        role = Role.Button, onClick = keyboardHapticAction { showHistory = false; onRepeatSearch(item) },
                        onLongClickLabel = clearLabel,
                        onLongClick = keyboardHapticAction(isLong = true, action = ::showClearAction)
                    ).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DropdownMenuItem(text = { Text(clearLabel) }, onClick = keyboardHapticAction {
                    onClearHistory()
                    showHistory = false
                })
            }
        }
    }
}

@Composable
internal fun KeyboardMediaPicker(
    tab: KeyboardMediaTab, onTab: (KeyboardMediaTab) -> Unit,
    recent: List<Emoji>, mediaAllowed: Boolean, mediaOptIn: Boolean, query: String,
    onClearRecentEmoji: (() -> Unit)?,
    onSearch: (String) -> Unit, onEnable: () -> Unit, onEmoji: (Emoji) -> Unit, onText: (String) -> Unit,
    onBackspace: () -> Unit, onLetters: () -> Unit, onMedia: (KeyboardMedia.Item) -> Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                KeyboardMediaTab.EMOJI -> EmojiPicker(recent, query, { onSearch("") }, onClearRecentEmoji, onEmoji)
                KeyboardMediaTab.EMOTICONS -> EmoticonPicker(query, { onSearch("") }, onText)
                else -> when {
                    !mediaAllowed -> MediaPickerMessage("Online media is unavailable in this field.")
                    !KeyboardMedia.configured -> MediaPickerMessage("KLIPY is awaiting setup.")
                    !mediaOptIn -> Column(Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GIF and sticker searches share the terms you submit with KLIPY. Its catalog may contain ads.",
                            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                        TextButton(onEnable) { Text("Enable online media") }
                    }
                    else -> KlipyResults(tab, query, onSearch, onMedia)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 52.dp, height = 48.dp).clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))
                .clickable(role = Role.Button, onClickLabel = "Return to letters", onClick = onLetters),
                contentAlignment = Alignment.Center) {
                Text("ABC", color = PaperKeyText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val compact = maxWidth < 241.dp
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)).padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (choice in KeyboardMediaTab.entries) {
                        val glyph = if (compact) when (choice) {
                            KeyboardMediaTab.EMOJI -> Glyph.SMILE
                            KeyboardMediaTab.STICKERS -> Glyph.LAYERS
                            else -> null
                        } else null
                        val caption = if (compact && choice == KeyboardMediaTab.EMOTICONS) ":-)" else choice.label
                        PickerTab(choice.label, choice == tab,
                            Modifier.weight(if (!compact && choice == KeyboardMediaTab.EMOTICONS) 1.45f else 1f),
                            enabled = mediaAllowed || !choice.online, caption = caption, glyph = glyph) { onTab(choice) }
                    }
                }
            }
            KeyButton(Modifier.size(48.dp), glyph = Glyph.BACKSPACE,
                label = "Backspace", onClick = onBackspace)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaIcon(glyph: Glyph, label: String, onClick: () -> Unit, onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).combinedClickable(
        role = Role.Button, onClick = keyboardHapticAction(action = onClick), onLongClickLabel = onLongClickLabel,
        onLongClick = if (onLongClick != null) keyboardHapticAction(isLong = true, action = onLongClick) else null)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MonologIcon(glyph, Modifier.size(17.dp), PaperKeyText)
    }
}

@Composable
private fun MediaPickerMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
    }
}

private val mediaCategories = listOf("Trending", "Reactions", "Laugh", "Love", "Thanks", "Celebrate", "Good night")

@Composable
private fun KlipyResults(tab: KeyboardMediaTab, query: String, onSearch: (String) -> Unit,
    onMedia: (KeyboardMedia.Item) -> Boolean) {
    var issue by remember(tab, query) { mutableStateOf<String?>(null) }
    var attempt by remember(tab, query) { mutableIntStateOf(0) }
    val results by produceState<List<KeyboardMedia.Item>?>(null, tab, query, attempt) {
        value = null
        value = try { KeyboardMedia.list(tab, query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue = "Couldn’t load ${tab.label.lowercase()}."; emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        val entries = results
        val error = issue
        when {
            entries == null -> MediaPickerMessage("Loading ${tab.label.lowercase()}…", Modifier.weight(1f))
            entries.isEmpty() && error == null -> MediaPickerMessage("No ${tab.label.lowercase()} found", Modifier.weight(1f))
            entries.isEmpty() -> Column(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty(), color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                TextButton({ issue = null; attempt++ }) { Text("Try again") }
            }
            else -> {
                if (error != null) Text(error, Modifier.padding(horizontal = 8.dp), color = PaperKeySubText, fontSize = 11.sp)
                key(tab, query) {
                    LazyVerticalGrid(GridCells.Adaptive(134.dp), Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(entries, key = { it.id }) { item ->
                            val preview by produceState<Drawable?>(null, item.thumbnail) {
                                value = try { KeyboardMedia.preview(item) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            }
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f))
                                .clickable(role = Role.Button, onClickLabel = "Insert ${item.name}") {
                                    if (!onMedia(item)) issue = "This field doesn’t accept this media."
                                }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(if (tab == KeyboardMediaTab.GIFS) 1.4f else 1.2f),
                                    contentAlignment = Alignment.Center) {
                                    val image = preview
                                    if (image != null) AnimatedMediaPreview(image, item.name)
                                    else Text(item.name, Modifier.padding(7.dp), color = PaperKeySubText,
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                                item.attribution?.let { credit ->
                                    Text(credit, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = PaperKeySubText)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val active = if (query.isEmpty()) "Trending" else mediaCategories.drop(1).firstOrNull { it.equals(query, true) }
            MediaCategoryRow(mediaCategories, active, Modifier.weight(1f)) { topic ->
                onSearch(topic.takeUnless { it == "Trending" }.orEmpty())
            }
            Text("Powered by KLIPY", Modifier.padding(start = 3.dp, end = 7.dp),
                color = PaperKeySubText, fontSize = 10.sp, maxLines = 1)
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
