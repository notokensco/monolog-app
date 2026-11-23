package com.notokens.monolog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import com.notokens.monolog.ai.GgufRuntime
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ai.TextModel
import com.notokens.monolog.ui.theme.PaperKeySubText

@Composable
internal fun TextModelPicker(store: ModelStore) {
    val active by store.active.collectAsState()
    val target by store.downloading.collectAsState()
    val state by store.state.collectAsState()
    val error by store.error.collectAsState()
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val recommendation = remember(context) {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val gib = info.totalMem / (1024.0 * 1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        when {
            !GgufRuntime.available || manager?.isLowRamDevice == true || gib < 5.5 || cores < 6 -> TextModel.GEMMA
            gib < 9.0 || cores < 8 -> TextModel.QWEN3
            else -> TextModel.QWEN35
        }
    }
    SettingsCard("Offline text model") {
        Text("Install one model for offline answers and rewrites. A verified download replaces the previous model.",
            color = PaperKeySubText)
        TextModel.entries.forEach { model ->
            val supported = !model.isGguf || GgufRuntime.available
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when (model) {
                        TextModel.GEMMA -> "Good"
                        TextModel.QWEN3 -> "Better"
                        TextModel.QWEN35 -> "Best"
                    })
                    if (model == recommendation) Text("Recommended for this phone", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall)
                    Text(if (model == active) "Installed" else
                        if (supported) model.downloadSize else "GGUF runtime unavailable",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                }
                if (model != active && supported) {
                    val label = if (model == target) "Downloading…" else "Download"
                    if (model == TextModel.GEMMA) {
                        GemmaDownloadButton(label, enabled = target == null) { store.download(model) }
                    } else {
                        TextButton(onClick = { store.download(model) }, enabled = target == null) { Text(label) }
                    }
                }
                TextButton({ browser.openUri(model.licenseUrl) }) { Text("Terms") }
            }
        }
        val current = state
        if (current is ModelState.Downloading) {
            LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun GemmaDownloadButton(label: String, enabled: Boolean = true, download: () -> Unit) {
    val browser = LocalUriHandler.current
    var showTerms by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showTerms = true }, enabled = enabled) { Text(label) }
    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("Gemma model terms") },
            text = {
                Column {
                    Text("By continuing, you agree to the Gemma Terms of Use, including the use restrictions in Section 3.2 and the Gemma Prohibited Use Policy.")
                    TextButton({ browser.openUri("https://gemma.ai/resources/terms/") }) { Text("Read terms") }
                    TextButton({ browser.openUri("https://gemma.ai/resources/prohibited-use/") }) {
                        Text("Read prohibited use policy")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    showTerms = false
                    download()
                }) { Text("Agree and download") }
            },
            dismissButton = { TextButton({ showTerms = false }) { Text("Cancel") } }
        )
    }
}
