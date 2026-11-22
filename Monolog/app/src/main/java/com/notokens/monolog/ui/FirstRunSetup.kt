package com.notokens.monolog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notokens.monolog.capture.ScreenCaptureService
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.ThemeMode
import com.notokens.monolog.memory.MemoryPreferences
import com.notokens.monolog.setup.checkImeEnabled
import com.notokens.monolog.setup.checkImeSelected
import com.notokens.monolog.setup.hasMicrophonePermission
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import kotlinx.coroutines.launch

@Composable
internal fun FirstRunSetup(settings: SettingsStore, onRequestMic: () -> Unit,
    onFinish: () -> Unit, onAccount: () -> Unit, onModels: () -> Unit, onMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val memory = remember { MemoryPreferences(context) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var offlineReady by remember { mutableStateOf(false) }
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
    fun finish(action: () -> Unit) {
        memory.explained = true
        action()
    }
    BackHandler(page > 0) { page-- }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonologLogo(Modifier.size(44.dp))
            Text("Monolog", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = PaperKeyText)
        }
        LinearProgressIndicator(progress = { (page + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(listOf("A little space for your day", "Make Monolog your keyboard",
                "Capture what matters", "Download offline models", "Your memories, your choice")[page],
                color = PaperKeyText, style = MaterialTheme.typography.headlineMedium)
            when (page) {
                0 -> {
                    Text("Write, save a moment, and find it again in your private journal.", color = PaperKeySubText)
                    SettingsCard("Journal memory") {
                        Text("Memory is on by default. Monolog keeps compact, meaningful moments from text it helps you type, plus the notes and screenshots you save.",
                            color = PaperKeyText)
                        Text("Typed moments are labeled Drafted. Passwords, protected fields, and incognito typing are excluded. You can edit, pin, or delete a moment.",
                            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Remember meaningful moments", Modifier.weight(1f), color = PaperKeyText)
                            Switch(rememberMoments, { rememberMoments = it; memory.enabled = it })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ scope.launch { settings.setThemeMode(ThemeMode.PAPER) } }) { Text("Paper mode") }
                        OutlinedButton({ scope.launch { settings.setThemeMode(ThemeMode.INK) } }) { Text("Ink mode") }
                    }
                }
                1 -> {
                    Text("Enable Monolog in Android, then choose it as your active keyboard.", color = PaperKeySubText)
                    SetupAction("Enable keyboard", enabled, "Open keyboard settings") {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    SetupAction("Choose Monolog", selected, "Choose keyboard") {
                        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }
                    Text("Tap M to capture a screen. Hold M for quick shortcuts. Hold the spacebar to open Ask.",
                        color = PaperKeyText, style = MaterialTheme.typography.bodyLarge)
                }
                2 -> {
                    Text("Enable the tools you want to use. You can change these permissions in Settings.", color = PaperKeySubText)
                    SetupAction("Quick Capture", capture, "Enable screen capture") {
                        com.notokens.monolog.capture.CaptureSetupActivity.openAccessibilitySettings(context)
                    }
                    Text("Turn on the Quick Capture shortcut and choose the floating accessibility button to capture even when the keyboard is closed.",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                    SetupAction("Voice typing & voice notes", microphone, "Enable microphone", onRequestMic)
                    SetupAction("Reminder notifications", notifications, "Enable notifications") {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                3 -> OnboardingModels { offlineReady = it }
                4 -> {
                    Text("You can start as a guest. Google sign-in and encrypted Drive backup are optional.",
                        color = PaperKeyText)
                    SettingsCard("Keep a recovery copy") {
                        Text("Connect Drive from Account & backup. Connect the same Google account on another phone to restore.",
                            color = PaperKeySubText)
                        OutlinedButton({ finish(onAccount) }) { Text("Set up Google Drive backup") }
                    }
                    SettingsCard("Offline intelligence") {
                        Text("Download Gemma and the offline search encoder from Settings when you want local AI and semantic search.",
                            color = PaperKeySubText)
                        TextButton({ finish(onModels) }) { Text("Set up offline AI") }
                        TextButton({ finish(onMemory) }) { Text("Set up journal memory & search") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) OutlinedButton({ page-- }) { Text("Back") }
            Button(onClick = { if (page < 4) page++ else finish(onFinish) },
                enabled = page != 3 || offlineReady, modifier = Modifier.weight(1f)) {
                Text(if (page == 4) "Start using Monolog" else "Continue")
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
