package com.notokens.monolog.ui

import androidx.compose.foundation.layout.Column
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
import com.notokens.monolog.ime.vox.VoxLanguage
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.launch

@Composable
internal fun RegionalVoiceSettings(onReady: (Boolean) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SettingsStore(context) }
    val selected by settings.voiceInputEngine.collectAsState(VoiceInputEngine.NATIVE)
    val languageCode by settings.voxLanguage.collectAsState(null)
    val activeLanguage = VoxLanguage.resolve(languageCode, "en")
    val scope = rememberCoroutineScope()
    val install by RegionalVox.installation.collectAsState()
    val pack = remember { mutableStateOf<VoxLanguage?>(null) }
    val choice = pack.value ?: VoxLanguage.resolve(activeLanguage.pack, "hi")
    val completed = install as? RegionalVox.Install.Ready
    val downloaded by produceState(emptySet<VoxLanguage>(), completed) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            VoxLanguage.downloadChoices.filter { RegionalVox.ready(context, it) }.toSet()
        }
    }
    val ready = choice in downloaded
    val anyReady = downloaded.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(anyReady) { onReady(anyReady) }
    SettingsCard("VOX AI India") {
        Text("Download only the languages you use. Hindi also includes English and Hinglish.", color = PaperKeySubText)
        TextButton({ expanded = !expanded }) { Text("Choose language  ·  ${choice.title}  ${if (expanded) "▴" else "▾"}") }
        if (expanded) Column {
            VoxLanguage.downloadChoices.sortedBy { it.title }.forEach { language ->
                TextButton({ pack.value = language; expanded = false }) {
                    Text(language.title + if (language in downloaded) " · Downloaded" else "")
                }
            }
        }
        val modelSize by produceState<Long?>(null, choice) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { RegionalVox.packSize(context, choice) }.getOrNull()?.takeIf { it > 0 }
                    ?.let { (it + 999_999) / 1_000_000 }
            }
        }
        if (modelSize != null) Text("$modelSize MB · On-device", color = PaperKeySubText)
        val state = install
        when {
            ready && selected == VoiceInputEngine.REGIONAL && choice.pack == activeLanguage.pack ->
                Text("Active in Vox", color = MaterialTheme.colorScheme.primary)
            ready -> TextButton({
                scope.launch {
                    settings.setVoxLanguage(choice.code)
                    settings.setVoiceInputEngine(VoiceInputEngine.REGIONAL)
                }
            }) { Text("Use in Vox") }
            state is RegionalVox.Install.Downloading && state.pack == choice.pack -> {
                Text("Downloading · ${state.percent}%", color = PaperKeySubText)
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            state is RegionalVox.Install.Downloading -> Text("Another language is downloading.", color = PaperKeySubText)
            state is RegionalVox.Install.Failed && state.pack == choice.pack -> {
                Text(state.reason, color = MaterialTheme.colorScheme.error)
                TextButton({ RegionalVox.startDownload(context, choice) }) { Text("Retry") }
            }
            else -> TextButton({ RegionalVox.startDownload(context, choice) }) { Text("Download") }
        }
    }
}
