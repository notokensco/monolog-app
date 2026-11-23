package com.notokens.monolog.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ime.vox.RegionalVox
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RegionalVoiceSettings(onReady: (Boolean) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SettingsStore(context) }
    val selected by settings.voiceInputEngine.collectAsState(VoiceInputEngine.NATIVE)
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var ready by remember { mutableStateOf(RegionalVox.ready(context)) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { onReady(ready) }
    SettingsCard("VOX AI India") {
        Text("Indian English, Hindi and mixed speech · 53 MB", color = PaperKeySubText)
        if (ready) {
            if (selected == VoiceInputEngine.REGIONAL) Text("Active in Vox", color = MaterialTheme.colorScheme.primary)
            else TextButton({ scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.REGIONAL) } }) {
                Text("Use in Vox")
            }
        } else TextButton(enabled = progress == null, onClick = {
            progress = 0
            error = false
            scope.launch {
                try {
                    RegionalVox.download(context) { progress = it }
                    ready = RegionalVox.ready(context)
                    if (ready) settings.setVoiceInputEngine(VoiceInputEngine.REGIONAL)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { progress = null }
            }
        }) { Text(if (progress == null) "Download" else "Downloading…") }
        progress?.let { value ->
            LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (error) Text("Download unavailable. Check your connection and free space.", color = MaterialTheme.colorScheme.error)
    }
}
