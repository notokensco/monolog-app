package com.notokens.monolog.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.RewriteStyle
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun KeyboardAiControls(ai: ImeAi, language: KeyboardLanguage) {
    if (ai.rewriteOpen || ai.hasDraft || ai.suggestions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ai.rewriteOpen) {
                val styles = if (language.id == "en" || language.translationCode != null) {
                    RewriteStyle.entries
                } else listOf(RewriteStyle.POLISH)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    styles.forEach { style ->
                        ToolbarAction(style.label, enabled = !ai.busy) { ai.rewrite(style, language) }
                    }
                    if (language.translationCode != null) {
                        ToolbarAction("To English", enabled = !ai.busy) {
                            ai.rewrite(RewriteStyle.POLISH, language, translate = true)
                        }
                    }
                }
                ToolbarAction("Close", onClick = ai::closePanel)
            } else {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    ai.suggestions.forEach { suggestion ->
                        ToolbarAction(suggestion.replacement.trimEnd()) { ai.applyWord(suggestion) }
                    }
                }
                if (ai.hasDraft) ToolbarAction("Rewrite") { ai.openRewrite(language) }
            }
        }
    }
    if (ai.busy) Text("Working…", Modifier.padding(horizontal = 12.dp), color = PaperKeyText)
    ai.preview?.let { AiResultCard(it, ai::apply) }
    AnimatedContent(
        targetState = ai.toolbar.takeIf { !ai.rewriteOpen && ai.preview == null },
        transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() },
        label = "Keyboard answer"
    ) { answer ->
        answer?.let { AiResultCard(it, ai::apply) }
    }
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
