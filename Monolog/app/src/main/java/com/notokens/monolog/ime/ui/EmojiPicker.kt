package com.notokens.monolog.ime.ui

import com.notokens.monolog.ui.theme.PaperKeyText

import android.content.res.AssetManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.R
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Emoji(val text: String, val label: String)
private data class EmojiGroup(val name: String, val entries: List<Emoji>, val variants: Map<String, List<Emoji>> = emptyMap())

private data class EmojiSearchEntry(val emoji: Emoji, val name: String)

private object EmojiCatalog {
    @Volatile private var indexed: List<EmojiSearchEntry>? = null

    fun searchIndex(assets: AssetManager): List<EmojiSearchEntry> = indexed ?: synchronized(this) {
        indexed ?: read(assets).flatMap { group ->
            group.entries.map { EmojiSearchEntry(it, it.label.lowercase().replace('-', ' ')) }
        }.also { indexed = it }
    }

    private val labels = mapOf(
        "Smileys & Emotion" to "Faces",
        "People & Body" to "People",
        "Animals & Nature" to "Nature",
        "Food & Drink" to "Food",
        "Travel & Places" to "Travel"
    )

    @Volatile var cached: List<EmojiGroup>? = null
        private set

    fun read(assets: AssetManager): List<EmojiGroup> = cached ?: synchronized(this) {
        cached ?: readFile(assets).also { cached = it }
    }

    private fun readFile(assets: AssetManager): List<EmojiGroup> {
        val groups = linkedMapOf<String, MutableList<Emoji>>()
        var group: MutableList<Emoji>? = null
        assets.open("emoji-18.0.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("##") || line.isBlank()) continue
                val separator = line.indexOf('\t')
                if (separator > 0) {
                    group?.add(Emoji(line.substring(0, separator), line.substring(separator + 1)))
                } else if (line.startsWith("#")) {
                    group = groups.getOrPut(line.drop(1)) { mutableListOf() }
                }
            }
        }
        return groups.filterValues { it.isNotEmpty() }.map { (name, entries) ->
            val names = entries.mapTo(hashSetOf()) { it.label }
            val variants = entries.filter { it.label.substringAfter(':', "").contains("skin tone") && it.label.substringBefore(':') in names }
                .groupBy { it.label.substringBefore(':') }
            val folded = variants.values.flatten().mapTo(hashSetOf()) { it.text }
            EmojiGroup(labels[name] ?: name, entries.filterNot { it.text in folded }, variants)
        }
    }
}

internal fun preloadEmojiCatalog(assets: AssetManager) {
    EmojiCatalog.searchIndex(assets)
}

private val EmojiFont = FontFamily(Font(R.font.noto_emoji))

private val ToolbarEmoji = listOf(
    Emoji("\uD83D\uDE00", "Grinning face"), Emoji("\uD83D\uDE02", "Tears of joy"), Emoji("\u2764\uFE0F", "Red heart"),
    Emoji("\uD83D\uDC4D", "Thumbs up"), Emoji("\u2728", "Sparkles"), Emoji("\uD83D\uDE4F", "Folded hands"),
    Emoji("\uD83C\uDF89", "Party popper")
)

internal data class EmojiMatches(val typed: String, val entries: List<Emoji>)

private val emojiTerms = Regex("""[\p{L}]+(?:[ -][\p{L}]+){0,3}$""")
private val shortcode = Regex("""(?<!\S):[\p{L}_-]*(?: [\p{L}_-]+){0,3}$""")
internal fun emojiShortcode(before: String): String? = shortcode.find(before)?.value
private val emojiAliases = mapOf(
    "smile" to "grinning face", "happy" to "smiling face with smiling eyes",
    "laugh" to "face with tears of joy", "lol" to "face with tears of joy",
    "cry" to "loudly crying face", "sad" to "crying face", "love" to "red heart",
    "thanks" to "folded hands", "pray" to "folded hands",
    "party" to "party popper", "fire" to "fire", "wow" to "face with open mouth"
)

internal fun matchEmojiName(assets: AssetManager, before: String): EmojiMatches? {
    val explicit = emojiShortcode(before)
    if (explicit != null) {
        val query = explicit.drop(1).lowercase().replace('_', ' ').replace('-', ' ')
        if (query.isBlank()) return null
        return EmojiMatches(explicit, rankedEmojis(EmojiCatalog.searchIndex(assets), query))
    }
    val ending = emojiTerms.find(before)?.value ?: return null
    val emojis = EmojiCatalog.searchIndex(assets)
    val words = ending.split(' ', '-')
    for (size in minOf(3, words.size) downTo 1) {
        val length = words.takeLast(size).sumOf(String::length) + size - 1
        val typed = ending.takeLast(length)
        val query = typed.lowercase().replace('-', ' ')
        if (query.length < 3) continue
        val matches = rankedEmojis(emojis, query)
        if (matches.isNotEmpty()) return EmojiMatches(typed, matches)
    }
    return null
}

private fun rankedEmojis(emojis: List<EmojiSearchEntry>, query: String): List<Emoji> {
    val alias = emojiAliases[query]
    return emojis.mapNotNull { (emoji, name) ->
        val score = when {
            name == alias -> 0
            name == query -> 1
            name.startsWith(query) -> 2
            ' ' !in query && (name.endsWith(" $query") || name.contains(" $query ")) -> 3
            else -> return@mapNotNull null
        }
        emoji to score
    }.sortedWith(compareBy({ it.second }, { it.first.label.length })).take(7).map { it.first }
}

@Composable
internal fun EmojiShortcutStrip(recent: List<Emoji>, matches: List<Emoji>? = null,
    compact: Boolean = false, onPick: (Emoji) -> Unit) {
    val choices = matches ?: (recent + ToolbarEmoji).distinctBy(Emoji::text).take(7)
    Row(if (compact) Modifier else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        choices.forEach { emoji ->
            KeyButton(
                modifier = Modifier.width(if (compact) 28.dp else 34.dp).height(40.dp),
                label = emoji.label,
                onClick = { onPick(emoji) }
            ) {
                Text(emoji.text, fontFamily = EmojiFont, fontSize = 18.sp)
            }
        }
    }
}


@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EmojiPicker(height: Dp, recent: List<Emoji>, query: String, onSearch: () -> Unit,
    onClearSearch: () -> Unit, onPick: (Emoji) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val vibration = LocalVibrationHaptics.current
    val catalog by produceState(EmojiCatalog.cached) {
        value = withContext(Dispatchers.IO) { EmojiCatalog.read(context.assets) }
    }
    val groups = catalog.orEmpty()
    var category by remember { mutableStateOf("Faces") }
    var toneFor by remember { mutableStateOf<Emoji?>(null) }
    val group = groups.firstOrNull { it.name == category }
    val tone = toneFor
    val searchResults = remember(groups, query) {
        if (query.isBlank()) emptyList() else groups.asSequence().flatMap { it.entries.asSequence() }
            .filter { it.label.contains(query, ignoreCase = true) || it.text == query }.take(96).toList()
    }
    val entries = when {
        query.isNotBlank() -> searchResults
        tone != null -> listOf(tone) + group?.variants?.get(tone.label).orEmpty()
        category == "Recent" -> recent
        else -> group?.entries.orEmpty()
    }

    Column(Modifier.fillMaxWidth().height(height)) {
        if (tone != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconControl(Glyph.BACK, "Back to emoji categories", { toneFor = null })
                Text(tone.label, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(listOf("Recent") + groups.map { it.name }) { name ->
                        PickerTab(name, category == name && query.isBlank()) {
                            onClearSearch()
                            category = name
                        }
                    }
                }
                PickerSearch(query.ifBlank { "Search emoji" }, onSearch)
            }
        }
        if (query.isNotBlank()) Text(query, Modifier.padding(start = 6.dp), color = PaperKeyText, fontSize = 11.sp)
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(when {
                    catalog == null -> "Loading emoji…"
                    query.isNotBlank() -> "No matching emoji"
                    category == "Recent" -> "Recently used emoji appear here."
                    else -> "No emoji available"
                }, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            key(category, tone?.text, query) {
                LazyVerticalGrid(columns = GridCells.Adaptive(42.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.text }) { emoji ->
                        val hasTones = query.isBlank() && tone == null && group?.variants?.containsKey(emoji.label) == true
                        Box(
                            Modifier.height(44.dp).semantics { contentDescription = emoji.label }
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = "Choose skin tone",
                                    onLongClick = if (hasTones) ({ toneFor = emoji }) else null
                                ) {
                                    triggerHapticFeedback(view, enabled = vibration)
                                    onPick(emoji)
                                    toneFor = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji.text, Modifier.clearAndSetSemantics {}, fontFamily = EmojiFont, fontSize = 23.sp)
                            if (hasTones) {
                                Box(
                                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(3.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
