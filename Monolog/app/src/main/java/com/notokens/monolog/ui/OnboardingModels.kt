package com.notokens.monolog.ui

import android.os.Process
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ime.vox.OfflineMoonshine
import com.notokens.monolog.ime.vox.RegionalVox
import com.notokens.monolog.ime.vox.VoxLanguage
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ModelSetupStatus { OPTIONAL, DOWNLOADING, READY }

@Composable
internal fun OnboardingVoice(settings: SettingsStore, onStatus: (ModelSetupStatus) -> Unit) {
    val context = LocalContext.current.applicationContext
    val engine by settings.voiceInputEngine.collectAsState(VoiceInputEngine.NATIVE)
    val language by settings.voxLanguage.collectAsState(null)
    val layout by settings.keyboardLanguage.collectAsState("en")
    val regionalInstall by RegionalVox.installation.collectAsState()
    var englishReady by remember(context) { mutableStateOf(OfflineMoonshine.ready(context)) }
    var englishDownloading by remember { mutableStateOf(false) }
    val regionalLanguage = VoxLanguage.resolve(language, KeyboardLanguage.of(layout).speechTag)
    val regionalReady by produceState(false, context, regionalInstall, regionalLanguage) {
        value = withContext(Dispatchers.IO) { RegionalVox.ready(context, regionalLanguage) }
    }
    val ready = when (engine) {
        VoiceInputEngine.REGIONAL -> regionalReady
        VoiceInputEngine.MOONSHINE -> englishReady
        VoiceInputEngine.NATIVE -> false
    }
    val status = when {
        englishDownloading || regionalInstall is RegionalVox.Install.Downloading -> ModelSetupStatus.DOWNLOADING
        ready -> ModelSetupStatus.READY
        else -> ModelSetupStatus.OPTIONAL
    }
    LaunchedEffect(status) { onStatus(status) }

    SettingsCard("Voice packs") {
        if (OfflineMoonshine.available) {
            OfflineVoxSettings(embedded = true, onDownloadingChange = { englishDownloading = it }) { englishReady = it }
            HorizontalDivider()
        }
        RegionalVoiceSettings(embedded = true)
    }
}

@Composable
internal fun OnboardingTextModel(onStatus: (ModelSetupStatus) -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ModelStore.get(context) }
    val state by store.state.collectAsState()
    val supported = Process.is64Bit()
    val status = when {
        !supported -> ModelSetupStatus.OPTIONAL
        state is ModelState.Downloading -> ModelSetupStatus.DOWNLOADING
        state == ModelState.Ready -> ModelSetupStatus.READY
        else -> ModelSetupStatus.OPTIONAL
    }
    LaunchedEffect(status) { onStatus(status) }

    if (supported) TextModelPicker(store)
    else Text("Offline text models require a 64-bit device.", color = PaperKeySubText)
}
