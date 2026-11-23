package com.notokens.monolog.ui

import android.os.Process
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val supportsGemma = Process.is64Bit()
    val supportsBrief = BriefModelStore.supported
    val supportsVox = OfflineMoonshine.available
    var voiceReady by remember(context) {
        mutableStateOf(OfflineMoonshine.ready(context))
    }

    var regionalReady by remember(context) { mutableStateOf(RegionalVox.anyReady(context)) }
    val ready = (regionalReady || voiceReady) &&
        (!supportsGemma || gemmaState == ModelState.Ready) &&
        (!supportsBrief || briefState == ModelState.Ready)
    LaunchedEffect(ready) { onReady(ready) }

    Text("Set up local voice, rewrites, journal search and daily briefs. The downloads stay on this device.",
        color = PaperKeySubText)
    RegionalVoiceSettings { regionalReady = it }
    if (supportsVox) OfflineVoxSettings { voiceReady = it }
    if (supportsGemma) TextModelPicker(gemma)
    if (supportsBrief) OnboardingModel("Daily brief summarizer · 118 MB", briefState, brief::download)
}

@Composable
private fun OnboardingModel(title: String, state: ModelState, download: () -> Unit) {
    SettingsCard(title) {
        when (state) {
            ModelState.Ready -> Text("Ready")
            ModelState.Missing -> TextButton(download) { Text("Download") }
            is ModelState.Failed -> {
                Text(state.message, color = PaperKeySubText)
                TextButton(download) { Text("Retry download") }
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
