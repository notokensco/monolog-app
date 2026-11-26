package com.notokens.monolog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notokens.monolog.capture.ScreenCaptureService
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.KeyboardPlacement
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.ThemeMode
import com.notokens.monolog.ime.vox.VoxWordmarkFont
import com.notokens.monolog.memory.MemoryPreferences
import com.notokens.monolog.setup.checkImeEnabled
import com.notokens.monolog.setup.checkImeSelected
import com.notokens.monolog.setup.hasMicrophonePermission
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import kotlinx.coroutines.launch

private enum class SetupStep(val title: String) {
    WELCOME("Welcome"),
    KEYBOARD("Set up your keyboard"),
    PRIVACY("Choose what to keep"),
    VOICE("VOX"),
    TEXT("Text model"),
    BACKUP("Optional backup")
}

@Composable
internal fun FirstRunSetup(settings: SettingsStore, onRequestMic: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val memory = remember { MemoryPreferences(context) }
    val profile by settings.personalProfile.collectAsState(com.notokens.monolog.data.PersonalProfile())
    var draftName by rememberSaveable { mutableStateOf<String?>(null) }
    val language by settings.keyboardLanguage.collectAsState("en")
    val placement by settings.keyboardPlacement.collectAsState(KeyboardPlacement.FULL)
    val borders by settings.keyBorders.collectAsState(false)
    var pickLanguage by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    val setupState = rememberSaveableStateHolder()
    var voiceStatus by remember { mutableStateOf(ModelSetupStatus.OPTIONAL) }
    var textStatus by remember { mutableStateOf(ModelSetupStatus.OPTIONAL) }
    var rememberMoments by remember { mutableStateOf(memory.enabled) }
    var enabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var selected by remember { mutableStateOf(checkImeSelected(context)) }
    var microphone by remember { mutableStateOf(hasMicrophonePermission(context)) }
    var capture by remember { mutableStateOf(ScreenCaptureService.isReady) }
    var notifications by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = it
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = checkImeEnabled(context)
                selected = checkImeSelected(context)
                microphone = hasMicrophonePermission(context)
                capture = ScreenCaptureService.isReady
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = checkImeEnabled(context)
                selected = checkImeSelected(context)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    BackHandler(page != SetupStep.WELCOME) { page = SetupStep.entries[page.ordinal - 1] }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Monolog setup", color = PaperKeyText, style = MaterialTheme.typography.labelLarge)
            Text("Step ${page.ordinal + 1} of ${SetupStep.entries.size}", color = PaperKeySubText,
                style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(progress = { (page.ordinal + 1f) / SetupStep.entries.size }, modifier = Modifier.fillMaxWidth())
        val scroll = key(page) { rememberScrollState() }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (page != SetupStep.WELCOME) Text(page.title, color = PaperKeyText,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = if (page == SetupStep.VOICE) VoxWordmarkFont else null,
                fontWeight = FontWeight.SemiBold)
            when (page) {
                SetupStep.WELCOME -> {
                    Column(Modifier.fillMaxWidth().glassSurface(28, GlassAccent.LAVENDER).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonologLogo(Modifier.size(72.dp))
                        Text("Welcome to Monolog", color = PaperKeyText, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text("your side of the day.", color = PaperKeySubText, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("A keyboard for what you want to say and a private journal for what you want to keep.",
                        color = PaperKeyText, style = MaterialTheme.typography.bodyLarge)
                    Text("Typing, suggestions, and journal search work offline. Downloaded voice and AI models run on your phone. Web features and Google Drive backup are optional.",
                        color = PaperKeySubText)
                    ProfileNameField(draftName ?: profile.name) { draftName = it }
                }
                SetupStep.KEYBOARD -> {
                    Text("Enable Monolog in Android, then choose it as your active keyboard.", color = PaperKeySubText)
                    SetupAction("Enable Monolog", enabled, "Open keyboard settings") {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    if (enabled) SetupAction("Choose Monolog", selected, "Choose keyboard") {
                        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }
                    SettingsCard("Keyboard preferences") {
                        OutlinedButton({ pickLanguage = true }) {
                            Text(KeyboardLanguage.of(language).label)
                        }
                        if (pickLanguage) AlertDialog(
                            onDismissRequest = { pickLanguage = false },
                            title = { Text("Typing language") },
                            text = {
                                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                                    for (item in KeyboardLanguage.all) TextButton({
                                        scope.launch { settings.setKeyboardLanguage(item.id) }
                                        pickLanguage = false
                                    }) { Text(item.label) }
                                }
                            },
                            confirmButton = { TextButton({ pickLanguage = false }) { Text("Close") } }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ scope.launch { settings.setThemeMode(ThemeMode.PAPER) } }) { Text("Paper") }
                            OutlinedButton({ scope.launch { settings.setThemeMode(ThemeMode.INK) } }) { Text("Ink") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Left-handed layout", Modifier.weight(1f), color = PaperKeyText)
                            Switch(placement == KeyboardPlacement.LEFT, {
                                scope.launch { settings.setKeyboardPlacement(if (it) KeyboardPlacement.LEFT else KeyboardPlacement.FULL) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Key borders", Modifier.weight(1f), color = PaperKeyText)
                            Switch(borders, { scope.launch { settings.setKeyBorders(it) } })
                        }
                    }
                    Text("GIF and sticker search is optional. If enabled, KLIPY receives the searches you submit and may show ads.",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                }
                SetupStep.PRIVACY -> {
                    SettingsCard("Journal memory") {
                        Text("Opt in to save meaningful moments from what you type. Your notes and captures are available either way.",
                            color = PaperKeyText)
                        Text("Typed moments are labeled Drafted. Passwords, protected fields, and incognito typing are excluded. You can edit or delete a saved moment.",
                            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Remember meaningful moments", Modifier.weight(1f), color = PaperKeyText)
                            Switch(rememberMoments, { rememberMoments = it; memory.enabled = it })
                        }
                    }
                    SetupAction("Keyboard Context", capture, "Enable screen capture") {
                        com.notokens.monolog.capture.CaptureSetupActivity.openAccessibilitySettings(context)
                    }
                    Text("Turn on Monolog Keyboard Context for the M key. Its optional Android shortcut lets you capture outside the keyboard.",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                    SetupAction("Reminder notifications", notifications, "Enable notifications") {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                SetupStep.VOICE -> {
                    Text("Choose a voice pack for on-device dictation and captions. You can add languages later in Settings.",
                        color = PaperKeySubText)
                    setupState.SaveableStateProvider("voice") { OnboardingVoice(settings) { voiceStatus = it } }
                    SetupAction("Microphone for VOX and Audio Notes", microphone, "Allow microphone", onRequestMic)
                }
                SetupStep.TEXT -> {
                    Text("Choose a model for Ask, rewrites, and journal briefs. Once downloaded, it runs on this phone.",
                        color = PaperKeySubText)
                    setupState.SaveableStateProvider("text") { OnboardingTextModel { textStatus = it } }
                }
                SetupStep.BACKUP -> {
                    Text("Use your Google Drive to back up Monolog, or finish setup and keep your journal on this phone.",
                        color = PaperKeySubText)
                    AccountBackupSettings(onboarding = true)
                }
            }
        }
        val downloading = (page == SetupStep.VOICE && voiceStatus == ModelSetupStatus.DOWNLOADING) ||
            (page == SetupStep.TEXT && textStatus == ModelSetupStatus.DOWNLOADING)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page != SetupStep.WELCOME) OutlinedButton({ page = SetupStep.entries[page.ordinal - 1] }) { Text("Back") }
            Button(onClick = {
                when (page) {
                    SetupStep.WELCOME -> scope.launch {
                        draftName?.let { settings.setProfileName(it) }
                        page = SetupStep.KEYBOARD
                    }
                    SetupStep.BACKUP -> {
                        memory.explained = true
                        onFinish()
                    }
                    else -> page = SetupStep.entries[page.ordinal + 1]
                }
            }, enabled = !downloading, modifier = Modifier.weight(1f)) {
                Text(when {
                    downloading -> "Downloading…"
                    page == SetupStep.WELCOME -> "Get started"
                    page == SetupStep.KEYBOARD && !selected -> "Continue without keyboard"
                    page == SetupStep.VOICE && voiceStatus == ModelSetupStatus.OPTIONAL -> "Skip voice for now"
                    page == SetupStep.TEXT && textStatus == ModelSetupStatus.OPTIONAL -> "Skip text model for now"
                    page == SetupStep.BACKUP -> "Start using Monolog"
                    else -> "Continue"
                })
            }
        }
    }
}

@Composable
private fun SetupAction(title: String, done: Boolean, label: String, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassSurface(22).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = PaperKeyText)
        if (done) Text("Ready", color = MaterialTheme.colorScheme.primary)
        else OutlinedButton(action) { Text(label) }
    }
}
