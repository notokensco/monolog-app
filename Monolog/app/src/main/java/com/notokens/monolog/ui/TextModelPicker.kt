package com.notokens.monolog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    SettingsCard("Offline text model") {
        Text("Install one model for offline answers and rewrites. A verified download replaces the previous model.",
            color = PaperKeySubText)
        TextModel.entries.forEach { model ->
            val supported = !model.isGguf || GgufRuntime.available
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName)
                    Text(if (model == active) "Installed" else
                        if (supported) model.downloadSize else "GGUF runtime unavailable",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                }
                if (model != active && supported) {
                    TextButton(onClick = { store.download(model) }, enabled = target == null) {
                        Text(if (model == target) "Downloading…" else "Download")
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
