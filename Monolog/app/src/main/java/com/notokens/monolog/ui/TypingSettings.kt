package com.notokens.monolog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.notokens.monolog.data.KeyboardPlacement
import com.notokens.monolog.data.KeyboardOption
import com.notokens.monolog.data.KeyboardTypeface
import com.notokens.monolog.data.KeyTapSound
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
    val placement by settings.keyboardPlacement.collectAsState(KeyboardPlacement.FULL)
    val borders by settings.keyBorders.collectAsState(false)
    val compact by settings.compactKeys.collectAsState(false)
    val onlineMedia by settings.onlineMedia.collectAsState(false)
    val phoneActions by settings.phoneActions.collectAsState(true)
    val capitalize by settings.autoCapitalize.collectAsState(false)
    val spellCheck by settings.systemSpellChecker.collectAsState(false)
    val cursorOnSpace by settings.cursorOnSpace.collectAsState(false)
    val popups by settings.keyPopups.collectAsState(true)
    val correct by settings.autocorrect.collectAsState(true)
    val swipe by settings.swipeTyping.collectAsState(true)
    val options by settings.keyboardOptions.collectAsState(KeyboardOption.entries.toSet())
    val popupMs by settings.keyPopupMs.collectAsState(70)
    val height by settings.keyHeightPercent.collectAsState(100)
    val vibrationHaptics by settings.vibrationHaptics.collectAsState(true)
    val keyHoldMs by settings.keyLongPressMs.collectAsState(350)
    val longPressCharacters by settings.longPressCharacters.collectAsState(true)
    val trail by settings.swipeTrail.collectAsState(120)
    val savedTriggers by settings.sensitiveWords.collectAsState("")
    var triggers by remember(savedTriggers) { mutableStateOf(savedTriggers) }
    var previewHeight by remember(height) { mutableIntStateOf(height) }
    var previewDelay by remember(popupMs) { mutableIntStateOf(popupMs) }
    var previewKeyHoldMs by remember(keyHoldMs) { mutableIntStateOf(keyHoldMs) }
    var previewTrail by remember(trail) { mutableIntStateOf(trail) }
    SettingsCard("Keyboard layout") {
        KeyboardSwitch("Left-handed layout", placement == KeyboardPlacement.LEFT) {
            scope.launch { settings.setKeyboardPlacement(if (it) KeyboardPlacement.LEFT else KeyboardPlacement.FULL) }
        }
        if (borders) KeyboardSwitch("Compact keys", compact) { scope.launch { settings.setCompactKeys(it) } }
        KeyboardSwitch("Vibration haptics", vibrationHaptics) { scope.launch { settings.setVibrationHaptics(it) } }
        for (option in listOf(KeyboardOption.NUMBERS, KeyboardOption.PASSWORD_NUMBERS, KeyboardOption.PERIOD, KeyboardOption.COMMA)) {
            KeyboardSwitch(option.label, option in options) { scope.launch { settings.setKeyboardOption(option, it) } }
        }
        KeyboardSwitch("Hold space to move the cursor", cursorOnSpace) { scope.launch { settings.setCursorOnSpace(it) } }
        KeyboardSwitch("Touch and hold for numbers and symbols", longPressCharacters) {
            scope.launch { settings.setLongPressCharacters(it) }
        }
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
        Text("Key hold delay · $previewKeyHoldMs ms")
        Slider(previewKeyHoldMs.toFloat(), { previewKeyHoldMs = it.roundToInt() },
            onValueChangeFinished = { scope.launch { settings.setKeyLongPressMs(previewKeyHoldMs) } },
            valueRange = 150f..800f, steps = 12)
    }
    SettingsCard("Glide typing") {
        KeyboardSwitch("Glide typing", swipe) { scope.launch { settings.setSwipeTyping(it) } }
        KeyboardSwitch(KeyboardOption.GLIDE_DELETE.label, KeyboardOption.GLIDE_DELETE in options) {
            scope.launch { settings.setKeyboardOption(KeyboardOption.GLIDE_DELETE, it) }
        }
        Text(if (previewTrail == 0) "Swipe trail · Off" else "Swipe trail · ${previewTrail * 100 / 120}%")
        Slider(previewTrail.toFloat(), { previewTrail = it.roundToInt() },
            onValueChangeFinished = { scope.launch { settings.setSwipeTrail(previewTrail) } },
            valueRange = 0f..240f, steps = 11)
    }
    SettingsCard("Typing and suggestions") {
        KeyboardSwitch("Autocorrect", correct) { scope.launch { settings.setAutocorrect(it) } }
        if (correct) Text("Press Backspace immediately to undo an autocorrection.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        KeyboardSwitch("Auto-capitalize sentences", capitalize) { scope.launch { settings.setAutoCapitalize(it) } }
        KeyboardSwitch("Android spell checker", spellCheck) { scope.launch { settings.setSystemSpellChecker(it) } }
        if (spellCheck) Text("Offers extra corrections from the spell checker selected in Android settings. Private fields stay local to Monolog.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        for (option in listOf(KeyboardOption.WORD_STRIP, KeyboardOption.GRAMMAR, KeyboardOption.EMOJI, KeyboardOption.GIF, KeyboardOption.STICKER)) {
            KeyboardSwitch(option.label, option in options) { scope.launch { settings.setKeyboardOption(option, it) } }
        }
        KeyboardSwitch("Online GIFs and stickers", onlineMedia) { scope.launch { settings.setOnlineMedia(it) } }
        KeyboardSwitch("Text actions for addresses, phone numbers and email", phoneActions) {
            scope.launch { settings.setPhoneActions(it) }
        }
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
internal fun KeyboardSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked, onChange)
    }
}

@Composable
internal fun KeyboardPrivacySettings(settings: SettingsStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val learn by settings.learnWords.collectAsState(true)
    val history by settings.keyboardClipboard.collectAsState(false)
    val rescue by settings.draftRescue.collectAsState(false)
    val options by settings.keyboardOptions.collectAsState(KeyboardOption.entries.toSet())
    val screenshotsFolder by settings.screenshotFolder.collectAsState(null)
    var screenshotError by remember { mutableStateOf(false) }
    val chooseScreenshots = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (uri != null) scope.launch {
            try {
                val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                val resolver = context.contentResolver
                resolver.takePersistableUriPermission(uri, read)
                settings.setScreenshotFolder(uri.toString())
                screenshotsFolder?.takeIf { it != uri.toString() }?.let { previous ->
                    runCatching { resolver.releasePersistableUriPermission(android.net.Uri.parse(previous), read) }
                }
                screenshotError = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { screenshotError = true }
        }
    }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete keyboard learning?") },
        text = { Text("This clears learned words, typing patterns, and your personal dictionary on this device. Usage totals are kept. Drive copies can be deleted under Account & backup.") },
        confirmButton = { TextButton(onClick = {
            scope.launch { settings.deleteKeyboardLearning(); confirm = false }
        }) { Text("Delete learning") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
    SettingsCard("Personalisation") {
        KeyboardSwitch("Personalise for you", learn) { scope.launch { settings.setLearnWords(it) } }
        Text("Use words and word combinations you type to improve suggestions on this device.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirm = true }) { Text("Delete keyboard learning") }
    }
    SettingsCard("Clipboard and drafts") {
        KeyboardSwitch(KeyboardOption.COPIED.label, KeyboardOption.COPIED in options) {
            scope.launch { settings.setKeyboardOption(KeyboardOption.COPIED, it) }
        }
        KeyboardSwitch("Clipboard history", history) { scope.launch { settings.setKeyboardClipboard(it) } }
        Text("Link your Screenshots folder to offer each new screenshot once and keep it in Clipboard for 15 minutes.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        TextButton({
            val picker = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(android.content.Intent.EXTRA_LOCAL_ONLY, true)
            screenshotsFolder?.let { folder ->
                val tree = android.net.Uri.parse(folder)
                picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                        android.provider.DocumentsContract.getTreeDocumentId(tree)))
            }
            chooseScreenshots.launch(picker)
        }) {
            Text(if (screenshotsFolder == null) "Choose Screenshots folder" else "Change Screenshots folder")
        }
        screenshotsFolder?.let { folder ->
            TextButton(onClick = { scope.launch {
                settings.setScreenshotFolder(null)
                runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(folder),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                screenshotError = false
            } }) { Text("Disconnect Screenshots folder") }
        }
        if (screenshotError) Text("Couldn’t link that folder. Try choosing your Screenshots folder again.",
            color = MaterialTheme.colorScheme.error)
        KeyboardSwitch("Draft Rescue", rescue) { scope.launch { settings.setDraftRescue(it) } }
        Text("Draft Rescue holds up to 10 drafts in memory for 30 minutes. Drafts disappear when Monolog is closed by Android.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun KeyboardStyleSettings(settings: SettingsStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val font by settings.keyboardTypeface.collectAsState(KeyboardTypeface.MONOLOG)
    val size by settings.keyboardFontSize.collectAsState(100)
    val emojiColumns by settings.emojiColumns.collectAsState(8)
    val sound by settings.keyTapSound.collectAsState(KeyTapSound.OFF)
    val soundRevision by settings.keyTapSoundRevision.collectAsState(0L)
    val previewSound = com.notokens.monolog.ime.ui.rememberKeySound(sound, soundRevision)
    var sizePreview by remember(size) { mutableIntStateOf(size) }
    var columnsPreview by remember(emojiColumns) { mutableIntStateOf(emojiColumns) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                com.notokens.monolog.ime.ui.importKeySound(context.applicationContext, uri)
                settings.setKeyTapSound(KeyTapSound.CUSTOM)
                message = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message?.take(120) ?: "Couldn’t load that sound."
            }
        }
    }
    SettingsCard("Keyboard font and emoji size") {
        for (choice in KeyboardTypeface.entries) {
            FilterChip(selected = font == choice, onClick = { scope.launch { settings.setKeyboardTypeface(choice) } },
                label = { Text(choice.label) })
        }
        Text("Keyboard font size · $sizePreview%")
        Slider(sizePreview.toFloat(), { sizePreview = it.roundToInt() }, valueRange = 80f..130f, steps = 9,
            onValueChangeFinished = { scope.launch { settings.setKeyboardFontSize(sizePreview) } })
        Text("Emoji per row · $columnsPreview")
        Slider(columnsPreview.toFloat(), { columnsPreview = it.roundToInt() }, valueRange = 5f..10f, steps = 4,
            onValueChangeFinished = { scope.launch { settings.setEmojiColumns(columnsPreview) } })
    }
    SettingsCard("Key tap sound") {
        for (choice in KeyTapSound.entries) {
            FilterChip(selected = sound == choice, onClick = {
                if (choice == KeyTapSound.CUSTOM) picker.launch(arrayOf("audio/*"))
                else scope.launch { settings.setKeyTapSound(choice) }
            }, label = { Text(choice.label) })
        }
        if (previewSound != null) TextButton(onClick = previewSound) { Text("Play preview") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    val profile by settings.typingProfile.collectAsState(com.notokens.monolog.data.TypingProfile())
    var learnedShown by remember { mutableIntStateOf(0) }
    val learned = remember(profile.words, words) {
        val manual = words.mapTo(hashSetOf(), { com.notokens.monolog.data.wordKey(it) })
        profile.words.filter { (key, item) -> item.uses > 0 && key !in manual }.values.sortedByDescending { it.at }
    }
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
        if (learned.isNotEmpty()) TextButton({
            learnedShown = if (learnedShown == 0) 20 else 0
        }) { Text(if (learnedShown == 0) "Show learned words" else "Hide learned words") }
        learned.take(learnedShown).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.text, Modifier.weight(1f))
                Text(item.uses.toString(), color = PaperKeySubText)
                TextButton(onClick = { scope.launch { settings.removePersonalWord(item.text) } }) { Text("Remove") }
            }
        }
        if (learnedShown in 1 until learned.size) {
            TextButton({ learnedShown += 20 }) { Text("Show more") }
        }
        words.sorted().forEach { word ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word, Modifier.weight(1f))
                TextButton(onClick = { scope.launch { settings.removePersonalWord(word) } }) { Text("Remove") }
            }
        }
    }
}

@Composable
internal fun VoiceInputSettings(settings: SettingsStore, onRequestMicPermission: () -> Unit) {
    val engine by settings.voiceInputEngine.collectAsState(VoiceInputEngine.NATIVE)
    val pauseMs by settings.voxPause.collectAsState(3200)
    var previewPause by remember(pauseMs) { mutableIntStateOf(pauseMs) }
    val swapped by settings.swapVoxAndAi.collectAsState(false)
    val defaultVox by settings.defaultVox.collectAsState(false)
    val smartFilter by settings.smartVoxFilter.collectAsState(true)
    val scope = rememberCoroutineScope()
    SettingsCard("Voice controls") {
        KeyboardSwitch("Open the keyboard in Vox", defaultVox) { scope.launch { settings.setDefaultVox(it) } }
        KeyboardSwitch("Swap Vox and AI shortcuts", swapped) { scope.launch { settings.setSwapVoxAndAi(it) } }
        KeyboardSwitch("Smart Filter for filler sounds", smartFilter) { scope.launch { settings.setSmartVoxFilter(it) } }
        Text("Finish after ${"%.1f".format(previewPause / 1000f)} seconds of silence")
        Slider(value = previewPause.toFloat(), onValueChange = { previewPause = it.roundToInt() },
            onValueChangeFinished = { scope.launch { settings.setVoxPause(previewPause) } },
            valueRange = 1800f..6000f, steps = 14)
        TextButton(onRequestMicPermission) { Text("Microphone permission") }
    }
    if (com.notokens.monolog.ime.vox.OfflineMoonshine.available) OfflineVoxSettings()
    RegionalVoiceSettings()
    SettingsCard("Android speech") {
        Text("Use the on-device speech engine supplied by Android. Language support depends on the device.",
            color = PaperKeySubText)
        if (engine == VoiceInputEngine.NATIVE) com.notokens.monolog.ime.vox.ActiveVoxLabel()
        else TextButton({ scope.launch { settings.setVoiceInputEngine(VoiceInputEngine.NATIVE) } }) {
            Text("Use Android speech")
        }
    }
}

@Composable
internal fun KeyboardUsageSettings(settings: SettingsStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val storage by androidx.compose.runtime.produceState(emptyList<Pair<String, Long>>(), context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.notokens.monolog.data.storageBreakdown(context) }
    }
    if (storage.isNotEmpty()) SettingsCard("Storage on this phone") {
        for ((label, bytes) in storage) {
            Text("$label · ${android.text.format.Formatter.formatFileSize(context, bytes)}", color = PaperKeyText)
        }
        TextButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open Android app storage settings") }
    }
    val profile by settings.typingProfile.collectAsState(com.notokens.monolog.data.TypingProfile())
    val manualWords by settings.personalWords.collectAsState(emptySet())
    val personal by settings.personalProfile.collectAsState(com.notokens.monolog.data.PersonalProfile())
    SettingsCard("Last browser sign-in") {
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for ((label, time) in listOf("Local Connect" to personal.localSignIn, "Web Connect" to personal.driveSignIn)) {
            Text("$label · ${if (time > 0) format.format(java.util.Date(time)) else "No sign-in yet"}", color = PaperKeyText)
        }
    }
    SettingsCard("Keyboard activity") {
        Text("Counts exclude incognito and protected fields. Enable Keyboard profile in Account & backup to include them in Drive backups.",
            style = MaterialTheme.typography.bodySmall, color = PaperKeySubText)
        for (metric in com.notokens.monolog.data.TypingMetric.entries) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label, Modifier.weight(1f), color = PaperKeyText)
                Text(java.text.NumberFormat.getIntegerInstance().format(profile.total(metric)), color = PaperKeyText)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Words learned", Modifier.weight(1f), color = PaperKeyText)
            Text(profile.words.values.count { it.uses > 0 }.toString(), color = PaperKeyText)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Words added manually", Modifier.weight(1f), color = PaperKeyText)
            Text(manualWords.size.toString(), color = PaperKeyText)
        }
    }
}
