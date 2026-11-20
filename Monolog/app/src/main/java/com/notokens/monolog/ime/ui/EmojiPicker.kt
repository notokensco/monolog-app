package com.notokens.monolog.ime.ui

import android.content.res.AssetManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
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

private object EmojiCatalog {
    private val labels = mapOf(
        "Smileys & Emotion" to "Faces",
        "People & Body" to "People",
        "Animals & Nature" to "Nature",
        "Food & Drink" to "Food",
        "Travel & Places" to "Travel"
    )

    @Volatile private var cached: List<EmojiGroup>? = null

    fun read(assets: AssetManager): List<EmojiGroup> = cached ?: synchronized(this) {
        cached ?: readFile(assets).also { cached = it }
    }

    private fun readFile(assets: AssetManager): List<EmojiGroup> {
        val groups = linkedMapOf<String, MutableList<Emoji>>()
        var group: MutableList<Emoji>? = null
        assets.open("emoji-17.0.tsv").bufferedReader().useLines { lines ->
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

private val EmojiFont = FontFamily(Font(R.font.noto_emoji))

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EmojiPicker(height: Dp, recent: List<Emoji>, onPick: (Emoji) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val catalog by produceState<List<EmojiGroup>?>(null) {
        value = withContext(Dispatchers.IO) { EmojiCatalog.read(context.assets) }
    }
    val groups = catalog.orEmpty()
    var category by remember { mutableStateOf("Faces") }
    var toneFor by remember { mutableStateOf<Emoji?>(null) }
    val group = groups.firstOrNull { it.name == category }
    val tone = toneFor
    val entries = when {
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
            LazyRow(
                contentPadding = PaddingValues(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf("Recent") + groups.map { it.name }) { name ->
                    FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) })
                }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (category == "Recent") "Recently used emoji appear here." else "Loading emoji…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            key(category, tone?.text) {
                LazyVerticalGrid(columns = GridCells.Adaptive(42.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.text }) { emoji ->
                        val hasTones = tone == null && group?.variants?.containsKey(emoji.label) == true
                        Box(
                            Modifier.height(44.dp).semantics { contentDescription = emoji.label }
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = "Choose skin tone",
                                    onLongClick = if (hasTones) ({ toneFor = emoji }) else null
                                ) {
                                    triggerHapticFeedback(view)
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
