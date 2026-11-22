package com.notokens.monolog.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.launch

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
        Text("Monolog uses Gemma on your device. Tap a preview to replace the draft.", color = PaperKeySubText)
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
