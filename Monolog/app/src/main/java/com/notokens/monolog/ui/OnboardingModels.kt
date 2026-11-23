package com.notokens.monolog.ui

import android.os.Process
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ime.vox.VoxLanguage
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ime.vox.OfflineMoonshine
import com.notokens.monolog.ime.vox.RegionalVox
import com.notokens.monolog.memory.BriefModelStore
import com.notokens.monolog.ui.theme.PaperKeySubText

@Composable
internal fun OnboardingModels(onReady: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val gemma = remember(context) { ModelStore.get(context) }
    val brief = remember(context) { BriefModelStore.get(context) }
    val gemmaState by gemma.state.collectAsState()
    val briefState by brief.state.collectAsState()
    val settings = remember(context) { SettingsStore(context) }
    val engine by settings.voiceInputEngine.collectAsState(VoiceInputEngine.NATIVE)
    val language by settings.voxLanguage.collectAsState(null)
    val layout by settings.keyboardLanguage.collectAsState("en")
    val supportsText = Process.is64Bit()
    val supportsBrief = BriefModelStore.supported
    val supportsVox = OfflineMoonshine.available
    var wantsVoice by rememberSaveable { mutableStateOf(true) }
    var wantsText by rememberSaveable { mutableStateOf(supportsText) }
    var wantsBrief by rememberSaveable { mutableStateOf(false) }
    var moonshineReady by remember { mutableStateOf(OfflineMoonshine.ready(context)) }
    val regionalInstall by RegionalVox.installation.collectAsState()
    val regionalReady = remember(regionalInstall, language, layout) {
        RegionalVox.ready(context, VoxLanguage.resolve(language, com.notokens.monolog.ai.KeyboardLanguage.of(layout).speechTag))
    }
    val voiceReady = when (engine) {
        VoiceInputEngine.REGIONAL -> regionalReady
        VoiceInputEngine.MOONSHINE -> moonshineReady
        VoiceInputEngine.NATIVE -> false
    }
    val ready = (!wantsVoice || voiceReady) &&
        (!wantsText || !supportsText || gemmaState == ModelState.Ready) &&
        (!wantsBrief || !supportsBrief || briefState == ModelState.Ready)
    LaunchedEffect(ready) { onReady(ready) }

    SettingsCard("Choose what to set up") {
        Text("Choose the features you want. Finish their downloads before continuing.", color = PaperKeySubText)
        FeatureDownloadToggle("Voice typing and voice notes", wantsVoice, { wantsVoice = it })
        if (supportsText) FeatureDownloadToggle("On-device Ask and rewrites", wantsText, { wantsText = it })
        if (supportsBrief) FeatureDownloadToggle("AI daily briefs", wantsBrief, { wantsBrief = it })
    }
    if (wantsVoice) {
        if (!voiceReady) Text("Choose and activate one voice pack to continue.", color = PaperKeySubText)
        if (supportsVox) OfflineVoxSettings { moonshineReady = it }
        RegionalVoiceSettings()
    }
    if (wantsText && supportsText) TextModelPicker(gemma)
    if (wantsBrief && supportsBrief) OnboardingModel("Daily brief summarizer \u00b7 118 MB", briefState, brief::download)
}

@Composable
private fun FeatureDownloadToggle(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(enabled, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun OnboardingModel(title: String, state: ModelState, download: () -> Unit) {
    SettingsCard(title) {
        when (state) {
            ModelState.Ready -> Text("Ready")
            ModelState.Missing -> GemmaDownloadButton("Download", download = download)
            is ModelState.Failed -> {
                Text(state.message, color = PaperKeySubText)
                GemmaDownloadButton("Retry download", download = download)
            }
            is ModelState.Downloading -> {
                Text("Downloading ${state.percent}%", color = PaperKeySubText)
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
