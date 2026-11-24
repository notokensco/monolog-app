package com.notokens.monolog.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.RewriteStyle
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ime.WordSuggestion
import com.notokens.monolog.ime.shortcuts.ShortcutPage
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun KeyboardSuggestionStrip(
    ai: ImeAi,
    language: KeyboardLanguage,
    onShortcut: (com.notokens.monolog.ime.shortcuts.ShortcutSuggestion) -> Unit,
    clipboardActions: List<com.notokens.monolog.ime.DeviceAction> = emptyList(),
    emojis: List<Emoji> = emptyList(),
    onEmoji: (Emoji) -> Unit = {}
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val actions = ai.inlineActions.ifEmpty { clipboardActions }
        val removal = ai.suggestionToRemove.takeIf { ai.dictionaryActionsAllowed }
        if (removal != null) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TextButton(ai::removeCurrentSuggestion, Modifier.weight(1f)) {
                    Text("Remove suggestion", maxLines = 1)
                }
                IconButton({ ai.requestSuggestionRemoval(null) }, Modifier.size(36.dp)) {
                    MonologIcon(Glyph.CLOSE, Modifier.size(18.dp))
                }
            }
            return@Row
        }
        if (ai.selectedDictionaryWord != null && ai.dictionaryActionsAllowed) {
            TextButton(ai::addSelectedDictionaryWord, Modifier.weight(1f)) {
                Text("Add to dictionary", maxLines = 1)
            }
            return@Row
        }
        val shortcut = ai.shortcutSuggestion
        if (shortcut != null && !ai.rewriteOpen) {
            Row(Modifier.weight(1f).height(48.dp).glassSurface(13)
                .clickable(role = Role.Button) { onShortcut(shortcut) }.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val glyph = when (shortcut.page) {
                    ShortcutPage.ALARM -> Glyph.ALARM
                    ShortcutPage.REMINDER -> Glyph.BELL
                    else -> Glyph.BOOK
                }
                MonologIcon(glyph, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                Text(shortcut.prompt, color = PaperKeyText, fontSize = 15.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else if (!ai.rewriteOpen && ai.inlineBusy) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.notokens.monolog.ui.ProcessingDots() }
        } else if (!ai.rewriteOpen && ai.toolbar == null && actions.isNotEmpty()) {
            Row(Modifier.weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                DeviceActionButtons(actions)
            }
        } else if (!ai.rewriteOpen && ai.toolbar == null) {
            Row(Modifier.weight(1f).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                SuggestionChoices(Modifier.weight(1f), ai.suggestions, emojis, ai.hasDraft, ai::applyWord, onEmoji,
                    if (ai.dictionaryActionsAllowed) ai::requestSuggestionRemoval else null)
            }
        } else {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                if (ai.rewriteOpen) {
                    val styles = if (language.isEnglish || language.translationCode != null) RewriteStyle.entries
                        else listOf(RewriteStyle.POLISH)
                    styles.forEach { style ->
                        ToolbarAction(style.label, enabled = !ai.busy) { ai.rewrite(style, language) }
                    }
                    if (language.translationCode != null) ToolbarAction("To English", enabled = !ai.busy) {
                        ai.rewrite(RewriteStyle.POLISH, language, translate = true)
                    }
                } else if (ai.toolbar != null) {
                    AnimatedContent(targetState = ai.toolbar,
                        transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut() },
                        label = "Inline question answer") { answer ->
                        answer?.let { card ->
                            var expanded by remember(card) { mutableStateOf(false) }
                            Box {
                                Text(card.text, Modifier.widthIn(max = 224.dp).clickable { expanded = true }.padding(horizontal = 8.dp),
                                    color = PaperKeyText, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                DropdownMenu(expanded, { expanded = false }) {
                                    AiResultCard(card) { expanded = false; ai.apply(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ai.hasDraft && !ai.rewriteOpen) IconButton(onClick = { ai.openRewrite(language) },
            modifier = Modifier.size(36.dp).semantics { contentDescription = "Rewrite" }) {
            MonologIcon(Glyph.REWRITE, Modifier.size(22.dp))
        }
        if (ai.rewriteOpen) ToolbarAction("Close", onClick = ai::closePanel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChoices(
    modifier: Modifier,
    suggestions: List<WordSuggestion>,
    emojis: List<Emoji>,
    rewriteVisible: Boolean,
    onWord: (WordSuggestion) -> Unit,
    onEmoji: (Emoji) -> Unit,
    onRemove: ((WordSuggestion) -> Unit)?
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val bold = style.copy(fontWeight = FontWeight.Bold)
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val width = with(density) { maxWidth.roundToPx() }
        val padding = with(density) { 12.dp.roundToPx() }
        val emojiWidth = with(density) { 28.dp.roundToPx() }
        val unique = suggestions.distinctBy { it.label.lowercase() }
        val preferred = unique.firstOrNull { it.emphasized } ?: unique.firstOrNull()
        val widths = unique.associateWith { word ->
            measurer.measure(word.label, if (word == preferred) bold else style, maxLines = 1,
                softWrap = false).size.width + padding
        }
        val primary = preferred?.takeIf { widths.getValue(it) <= width }
            ?: unique.firstOrNull { widths.getValue(it) <= width }
            ?: preferred
        val mainWidth = primary?.let(widths::getValue)?.coerceAtMost(width) ?: 0
        val other = unique.filter { it != primary }.sortedBy { !it.nextWord }
            .firstOrNull { widths.getValue(it) + mainWidth <= width }
        if (primary != null && other == null) {
            val centerInset = if (rewriteVisible) minOf(36.dp, maxWidth / 4) else 0.dp
            val leadingSpace = (width - with(density) { centerInset.roundToPx() } - mainWidth) / 2
            val sideEmojis = minOf(emojis.size, 3, leadingSpace.coerceAtLeast(0) / emojiWidth.coerceAtLeast(1))
            Box(Modifier.fillMaxSize().padding(start = centerInset), contentAlignment = Alignment.Center) {
                if (sideEmojis >= 2 || sideEmojis == 1 && emojis.size == 1) {
                    Box(Modifier.align(Alignment.CenterStart)) {
                        EmojiShortcutStrip(emptyList(), emojis.take(sideEmojis), compact = true, onPick = onEmoji)
                    }
                }
                SuggestionWord(primary, if (primary == preferred) bold else style, onWord, onRemove)
            }
            return@BoxWithConstraints
        }
        val used = mainWidth + (other?.let(widths::getValue) ?: 0)
        val spare = width - used
        val emojiCount = minOf(emojis.size, 3, spare / emojiWidth.coerceAtLeast(1))
            .takeIf { it >= 2 || emojis.size == 1 } ?: 0
        val extra = if (primary != null && other != null && emojiCount == 0) {
            unique.firstOrNull { it != primary && it != other && widths.getValue(it) <= spare }
        } else null
        val slots = when {
            primary == null -> emptyList()
            emojiCount > 0 && other != null -> listOf(other, primary, null)
            emojiCount > 0 -> listOf(null, primary)
            extra != null -> listOfNotNull(other, primary, extra)
            else -> listOfNotNull(other, primary)
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            if (slots.isEmpty() && emojiCount > 0) {
                EmojiShortcutStrip(emptyList(), emojis.take(emojiCount), compact = true, onPick = onEmoji)
            }
            slots.forEach { word ->
                if (word == null) {
                    EmojiShortcutStrip(emptyList(), emojis.take(emojiCount), compact = true, onPick = onEmoji)
                } else {
                    SuggestionWord(word, if (word == preferred) bold else style, onWord, onRemove)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionWord(
    word: WordSuggestion,
    style: androidx.compose.ui.text.TextStyle,
    onWord: (WordSuggestion) -> Unit,
    onRemove: ((WordSuggestion) -> Unit)?
) {
    Box(Modifier.fillMaxHeight().combinedClickable(
        onClick = { onWord(word) },
        onLongClick = if (onRemove != null && com.notokens.monolog.data.dictionaryWord(word.label) != null)
            ({ onRemove(word) }) else null,
        onLongClickLabel = "Remove suggestion"
    ).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(word.label, color = PaperKeyText, style = style, textAlign = TextAlign.Center,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun KeyboardAiControls(ai: ImeAi, language: KeyboardLanguage) {
    if (ai.rewriteOpen && !ai.busy && ai.preview == null) {
        Text("Professional for work, Friendly for a warmer tone, Shorten for fewer words, or Polish for grammar.",
            Modifier.padding(horizontal = 12.dp, vertical = 3.dp), color = PaperKeyText,
            style = MaterialTheme.typography.bodySmall)
    }
    if (ai.busy) com.notokens.monolog.ui.ProcessingDots(Modifier.padding(horizontal = 12.dp))
    ai.preview?.let { AiResultCard(it, ai::apply) }
}

@Composable
private fun ToolbarAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, Modifier.sizeIn(minHeight = 36.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) PaperKeyText else PaperKeyText.copy(alpha = .45f), maxLines = 1)
}

@Composable
internal fun AiResultCard(preview: AiPreview, onApply: (AiPreview) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val webResults = preview.sources.any { it.snippet.isNotBlank() }
    if (webResults) {
        Column(Modifier.fillMaxWidth()) {
            if (preview.generatedLocally) {
                Text(preview.text, Modifier.padding(5.dp), color = PaperKeyText)
                Text("On-device AI can make mistakes.", Modifier.padding(horizontal = 5.dp),
                    style = MaterialTheme.typography.labelSmall, color = PaperKeyText)
            }
            for (source in preview.sources) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).glassSurface(12).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(source.title, style = MaterialTheme.typography.titleSmall, color = PaperKeyText, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(source.snippet, style = MaterialTheme.typography.bodySmall, color = PaperKeyText, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    TextButton({ runCatching { uriHandler.openUri(source.url) } }) { Text("View in browser") }
                }
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).glassSurface(14)
            .heightIn(max = 108.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)
    ) {
        Text(preview.text + if (preview.generatedLocally) " *" else "", Modifier.padding(top = 6.dp), color = PaperKeyText)
        preview.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PaperKeyText) }
        if (preview.generatedLocally) {
            Text("* On-device AI can make mistakes.", style = MaterialTheme.typography.labelSmall, color = PaperKeyText)
        }
        if (preview.sources.isNotEmpty() || preview.insertable) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (preview.insertable) TextButton({ onApply(preview) }) { Text("Use") }
                preview.sources.forEach { source ->
                    TextButton({ runCatching { uriHandler.openUri(source.url) } }) {
                        Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


@Composable
internal fun DeviceActionButtons(actions: List<com.notokens.monolog.ime.DeviceAction>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    actions.forEach { action ->
        TextButton(onClick = { action.open(context) }) { Text(action.label) }
    }
}
