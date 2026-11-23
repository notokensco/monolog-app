package com.notokens.monolog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.VoiceInputEngine
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBorder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun KeyboardBehaviorSettings(settings: SettingsStore) {
    val scope = rememberCoroutineScope()
    val vox by settings.defaultVox.collectAsState(false)
    val phoneActions by settings.phoneActions.collectAsState(true)
    val spellCheck by settings.systemSpellChecker.collectAsState(false)
    val popups by settings.keyPopups.collectAsState(false)
    val popupMs by settings.keyPopupMs.collectAsState(70)
    val height by settings.keyHeightPercent.collectAsState(100)
    val savedTriggers by settings.sensitiveWords.collectAsState("")
    var triggers by remember(savedTriggers) { mutableStateOf(savedTriggers) }
    var previewHeight by remember(height) { mutableIntStateOf(height) }
    var previewDelay by remember(popupMs) { mutableIntStateOf(popupMs) }
    SettingsCard("Keyboard layout") {
        KeyboardSwitch(if (vox) "Default Vox" else "Default Keys", vox) {
            scope.launch { settings.setDefaultVox(it) }
        }
        KeyboardSwitch("Android spell checker", spellCheck) { scope.launch { settings.setSystemSpellChecker(it) } }
        if (spellCheck) Text("Offers extra corrections from the spell checker selected in Android settings. Private fields stay local to Monolog.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        KeyboardSwitch("Phone actions in toolbar and AI Ask", phoneActions) { scope.launch { settings.setPhoneActions(it) } }
        if (phoneActions) Text("Suggests actions from typed text or the current clipboard without saving clipboard history.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        KeyboardSwitch("Key popups", popups) { scope.launch { settings.setKeyPopups(it) } }
        if (popups) {
            Text("Popup delay · $previewDelay ms")
            Slider(previewDelay.toFloat(), { previewDelay = it.roundToInt() },
                onValueChangeFinished = { scope.launch { settings.setKeyPopupMs(previewDelay) } },
                valueRange = 0f..250f, steps = 9)
        }
        Text("Keyboard height · $previewHeight%")
        Slider(previewHeight.toFloat(), { previewHeight = it.roundToInt() },
            onValueChangeFinished = { scope.launch { settings.setKeyHeightPercent(previewHeight) } },
            valueRange = 75f..125f, steps = 9)
    }
    SettingsCard("Sensitive drafts") {
        Text("Matching drafts stay out of saved memory. Separate your own trigger words or phrases with commas.",
            color = PaperKeySubText)
        OutlinedTextField(triggers, { triggers = it.take(1500) },
            label = { Text("Custom triggers") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        TextButton(enabled = triggers != savedTriggers,
            onClick = { scope.launch { settings.setSensitiveWords(triggers) } }) { Text("Save triggers") }
    }
}

@Composable
private fun KeyboardSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
internal fun ToneSettings(settings: SettingsStore) {
    val smartTone by settings.smartTone.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    SettingsCard("Smart Toning") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Suggest a tone for this app", Modifier.weight(1f))
            Switch(smartTone, { value -> scope.launch { settings.setSmartTone(value) } })
        }
        Text("Professional in Teams, Slack and mail. Casual in apps like WhatsApp, Signal and Telegram.",
            color = PaperKeySubText)
        Text("Monolog uses your selected offline text model. Tap a preview to replace the draft.", color = PaperKeySubText)
    }
}

@Composable
internal fun PersonalDictionarySettings(settings: SettingsStore) {
    val words by settings.personalWords.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    SettingsCard("Personal dictionary") {
        Text("Add names and words to your suggestions. Saved spellings are protected from autocorrect.",
            color = PaperKeySubText)
        OutlinedTextField(input, {
            input = it.take(40)
            invalid = false
        }, label = { Text("Word or name") }, singleLine = true, isError = invalid,
            supportingText = if (invalid) ({ Text("Enter one word, up to 24 characters. The dictionary holds up to 500 words.") }) else null,
            modifier = Modifier.fillMaxWidth())
        Button(enabled = input.isNotBlank(), onClick = {
            val candidate = input
            scope.launch {
                if (settings.addPersonalWord(candidate)) {
                    if (input == candidate) input = ""
                } else if (input == candidate) invalid = true
            }
        }) { Text("Add word") }
        words.sorted().forEach { word ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word, Modifier.weight(1f))
                TextButton(onClick = { scope.launch { settings.removePersonalWord(word) } }) { Text("Remove") }
            }
        }
    }
}

@Composable
internal fun VoiceInputSettings(settings: SettingsStore) {
    val engine by settings.voiceInputEngine.collectAsState(initial = VoiceInputEngine.NATIVE)
    val pauseMs by settings.voxPause.collectAsState(3200)
    var previewPause by remember(pauseMs) { mutableIntStateOf(pauseMs) }
    val swapped by settings.swapVoxAndAi.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    SettingsCard("Vox and AI shortcuts") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Swap Vox and AI Mode")
                Text("Hold Space for Vox. Open AI from the toolbar.", color = PaperKeySubText,
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(swapped, { value -> scope.launch { settings.setSwapVoxAndAi(value) } })
        }
    }
    SettingsCard("Voice input engine") {
        Text(
            "Choose your speech recognition engine for Vox typing.",
            color = PaperKeySubText,
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(engine == VoiceInputEngine.REGIONAL, {
                scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.REGIONAL) }
            })
            Column {
                Text("VOX AI India")
                Text("Indian English, Hindi and mixed speech. Download it under On-device AI.", color = PaperKeySubText)
            }
        }
        Text("Finish after ${"%.1f".format(previewPause / 1000f)} seconds of silence")
        Slider(value = previewPause.toFloat(), onValueChange = { previewPause = it.roundToInt() },
            onValueChangeFinished = { scope.launch { settings.setVoxPause(previewPause) } },
            valueRange = 1800f..6000f, steps = 14)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.NATIVE) } }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = engine == VoiceInputEngine.NATIVE,
                onClick = { scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.NATIVE) } }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Native real-time streaming", color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Instant on-device transcription with automatic filtering for filler sounds (umm, aa, uhh).",
                    color = PaperKeySubText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        HorizontalDivider(color = PaperPillBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.MOONSHINE) } }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = engine == VoiceInputEngine.MOONSHINE,
                onClick = { scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.MOONSHINE) } }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("VOX AI", color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Live English captions with the downloadable VOX AI model.",
                    color = PaperKeySubText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
