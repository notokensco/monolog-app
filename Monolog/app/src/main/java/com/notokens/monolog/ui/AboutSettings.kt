package com.notokens.monolog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.notokens.monolog.BuildConfig
import com.notokens.monolog.R
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AboutDocument(val title: String, val asset: String)

private val policyDocuments = listOf(
    AboutDocument("Privacy policy", "policies/privacy.txt"),
    AboutDocument("Terms of use", "policies/terms.txt"),
    AboutDocument("KLIPY media and ads", "policies/klipy.txt")
)

private val credits = listOf(
    AboutDocument("Third-party acknowledgments", "licenses/Third-party-acknowledgments.txt"),
    AboutDocument("Indic dictionaries and transliteration", "licenses/Indic-typing-data.txt"),
    AboutDocument("Creative Commons BY-SA 4.0", "licenses/CC-BY-SA-4.0.txt"),
    AboutDocument("English dictionary", "licenses/English-dictionary.txt"),
    AboutDocument("Noto Emoji", "noto-emoji-license.txt"),
    AboutDocument("Unicode emoji data", "unicode-license.txt"),
    AboutDocument("Science Gothic", "licenses/Science-Gothic-OFL.txt"),
    AboutDocument("Gemma terms", "licenses/Gemma-Terms.md"),
    AboutDocument("Gemma acknowledgments", "licenses/Gemma-NOTICE.txt"),
    AboutDocument("Apache License 2.0", "licenses/Shruti-Apache-2.0.txt"),
    AboutDocument("Shruti model acknowledgment", "licenses/Shruti-NOTICE.txt"),
    AboutDocument("Moonshine Voice", "licenses/Moonshine-MIT.txt"),
    AboutDocument("Moonshine native dependency notices", "licenses/Moonshine-ThirdPartyNotices.txt"),
    AboutDocument("ONNX Runtime", "licenses/ONNX-Runtime-MIT.txt"),
    AboutDocument("ONNX Runtime third-party notices", "licenses/ONNX-Runtime-ThirdPartyNotices.txt"),
    AboutDocument("LiteRT-LM third-party notices", "licenses/LiteRT-LM-ThirdPartyNotices.txt"),
    AboutDocument("MediaPipe Tasks notices", "licenses/MediaPipe-NOTICE.txt"),
    AboutDocument("llama.cpp", "licenses/Llama-cpp-MIT.txt"),
    AboutDocument("JSON for Modern C++", "licenses/Llama-cpp-JSON-MIT.txt"),
    AboutDocument("CommonMark", "licenses/Commonmark-BSD.txt"),
    AboutDocument("Protocol Buffers", "licenses/Protobuf-BSD.txt"),
    AboutDocument("Checker Framework qualifiers", "licenses/Checker-Qual-MIT.txt"),
    AboutDocument("Checker Framework compatibility qualifiers", "licenses/Checker-Compat-Qual-NOTICE.txt"),
    AboutDocument("Rust typing dependencies", "licenses/Rust-typing-notices.txt")
)

@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val document = (policyDocuments + credits).firstOrNull { it.asset == selected }
    if (document != null) {
        var page by rememberSaveable(document.asset) { mutableStateOf(0) }
        val pages by produceState<List<String>?>(null, document.asset) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(document.asset).bufferedReader().use { it.readText() }.chunked(7000)
                }.getOrDefault(listOf("This document is unavailable."))
            }
        }
        val content = pages
        TextButton({ selected = null }) { Text("Back to About") }
        SettingsCard(document.title) {
            if (content != null && content.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ page-- }, enabled = page > 0) { Text("Previous") }
                    Text("${page + 1} / ${content.size}", color = PaperKeySubText)
                    OutlinedButton({ page++ }, enabled = page < content.lastIndex) { Text("Next") }
                }
            }
            SelectionContainer {
                Text(content?.getOrNull(page) ?: "Loading…", color = PaperKeyText,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    SettingsCard("Monolog") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painter = painterResource(R.drawable.notokens_logo),
                contentDescription = null,
                modifier = Modifier.size(43.dp).clip(RoundedCornerShape(10.dp))
            )
            Column {
                Text("NoTokens", color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
                Text("Version ${BuildConfig.VERSION_NAME}", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton({ browser.openUri("https://notokens.co") }) { Text("NoTokens") }
        TextButton({ browser.openUri("https://monolog.notokens.co") }) { Text("Official Monolog website") }
        TextButton({ browser.openUri("mailto:monolog@notokens.co") }) { Text("Contact support") }
    }
    SettingsCard("Privacy and terms") {
        policyDocuments.forEach { item -> TextButton({ selected = item.asset }) { Text(item.title) } }
        TextButton({ browser.openUri("https://klipy.com/support/privacy-policy") }) { Text("KLIPY privacy policy") }
        TextButton({ browser.openUri("https://klipy.com/support/terms-services") }) { Text("KLIPY terms of service") }
        TextButton({ browser.openUri("https://monolog.notokens.co/privacy") }) { Text("Latest privacy policy online") }
    }
    SettingsCard("Ownership and licenses") {
        Text("NoTokens owns Monolog’s branding, original app design, code, and authored content. " +
            "Third-party data, models, fonts, and libraries are available under their respective terms.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        credits.forEach { item -> TextButton({ selected = item.asset }) { Text(item.title) } }
        Text("Keyboard translation uses Google Translate with downloaded on-device models.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        TextButton({ browser.openUri("https://cloud.google.com/translate") }) { Text("Google Translate attribution") }
        TextButton({ browser.openUri("https://docs.api.met.no/doc/License.html") }) { Text("Weather data license") }
    }
}
