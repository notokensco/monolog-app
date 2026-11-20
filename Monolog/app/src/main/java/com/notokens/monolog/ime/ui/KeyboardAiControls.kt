package com.notokens.monolog.ime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.notokens.monolog.BuildConfig
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.RewriteStyle
import com.notokens.monolog.ime.ImeAi
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun KeyboardAiControls(
    ai: ImeAi,
    language: KeyboardLanguage,
    showSetup: Boolean,
    onSetup: () -> Unit,
    onDismissSetup: () -> Unit,
    onAsk: () -> Unit
) {
    if (showSetup && !ai.rewriteOpen) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).glassSurface(16)) {
            Text("Set up on-device AI", Modifier.padding(start = 12.dp, top = 8.dp), color = PaperKeyText)
            Row(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onSetup) { Text("Choose models") }
                TextButton(onDismissSetup) { Text("Not now") }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onAsk) { Text("Ask") }
        if (ai.rewriteOpen) {
            val styles = if (language.id == "en" || language.translationCode != null) {
                RewriteStyle.entries
            } else listOf(RewriteStyle.POLISH)
            styles.forEach { style ->
                TextButton(onClick = { ai.rewrite(style, language) }, enabled = !ai.busy) { Text(style.label) }
            }
            if (language.translationCode != null) {
                TextButton(onClick = { ai.rewrite(RewriteStyle.POLISH, language, translate = true) },
                    enabled = !ai.busy) { Text("To English") }
            }
            TextButton(ai::closePanel) { Text("Close") }
        } else {
            TextButton({ ai.openRewrite(language) }) { Text("Rewrite") }
            if (BuildConfig.DEBUG) TextButton(ai::probeNano, enabled = !ai.busy) { Text("Probe Nano") }
        }
    }
    if (ai.rewriteOpen && language.translationCode != null) {
        Text("Tone rewrites use the offline translation pack.", Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall, color = PaperKeyText)
    }
    if (ai.busy) Text("Working…", Modifier.padding(horizontal = 12.dp), color = PaperKeyText)
    ai.preview?.let { AiResultCard(it, ai::apply) }
    if (!ai.rewriteOpen && ai.preview == null) ai.toolbar?.let { AiResultCard(it, ai::apply) }
}

@Composable
internal fun AiResultCard(preview: AiPreview, onApply: (AiPreview) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).glassSurface(14)
            .heightIn(max = 108.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)
    ) {
        Text(preview.text + if (preview.generatedLocally) " *" else "", Modifier.padding(top = 6.dp), color = PaperKeyText)
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
