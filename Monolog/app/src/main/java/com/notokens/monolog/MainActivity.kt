package com.notokens.monolog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.CompanionApp
import com.notokens.monolog.ui.CompanionDestination
import com.notokens.monolog.ui.MonologLaunchScreen
import com.notokens.monolog.ui.theme.LocalMonologDark
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.notokens.monolog.ime.vox.OfflineMoonshine

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_REQUEST_MIC_PERMISSION = "com.notokens.monolog.REQUEST_MIC_PERMISSION"
        const val ACTION_OPEN_JOURNAL = "com.notokens.monolog.OPEN_JOURNAL"
        const val ACTION_NEW_NOTE = "com.notokens.monolog.NEW_NOTE"
        const val ACTION_NEW_REMINDER = "com.notokens.monolog.NEW_REMINDER"
        const val ACTION_OPEN_TODAY = "com.notokens.monolog.OPEN_TODAY"
        const val ACTION_OPEN_VOICE = "com.notokens.monolog.OPEN_VOICE"
        const val ACTION_OPEN_PERSONAL_MATCH = "com.notokens.monolog.OPEN_PERSONAL_MATCH"
        const val ACTION_OPEN_APPEARANCE = "com.notokens.monolog.OPEN_APPEARANCE"
        const val ACTION_OPEN_SETTINGS = "com.notokens.monolog.OPEN_SETTINGS"
    }

    private var destination by mutableStateOf<CompanionDestination?>(null)
    private var personalRoute by mutableStateOf<String?>(null)
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Microphone enabled for Vox" else "Microphone permission required for Vox", Toast.LENGTH_SHORT).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Reminders are saved, but notifications are disabled", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchFromHome = savedInstanceState == null &&
            (intent?.action == Intent.ACTION_MAIN || intent?.action == null)
        setTheme(R.style.Theme_Monolog)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splashScreen.setOnExitAnimationListener { view -> view.remove() }
        lifecycleScope.launch(Dispatchers.IO) { OfflineMoonshine.removeRetiredModels(applicationContext) }
        if (savedInstanceState == null) handleIncomingIntent(intent)
        var readyToDraw = false
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!readyToDraw) return false
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            val settings = remember { SettingsStore(applicationContext) }
            val appearance by settings.appearance.collectAsState(initial = null)
            var useDefaultAppearance by remember { mutableStateOf(false) }
            if (appearance == null && !useDefaultAppearance) {
                LaunchedEffect(Unit) {
                    delay(600)
                    useDefaultAppearance = true
                }
                return@setContent
            }
            val current = appearance ?: Appearance()
            var showLaunch by remember { mutableStateOf(launchFromHome) }
            val launchVisible = showLaunch && destination == null && personalRoute == null
            MonologTheme(appearance = current) {
                val lightBars = !launchVisible && !LocalMonologDark.current
                SideEffect {
                    readyToDraw = true
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                if (launchVisible) MonologLaunchScreen { showLaunch = false }
                else CompanionApp(
                    settings = settings,
                    appearance = current,
                    destination = destination,
                    personalRoute = personalRoute,
                    onDestinationOpened = { destination = null; personalRoute = null },
                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.component?.className == "$packageName.KeyboardSettings") {
            destination = CompanionDestination.SETTINGS
            return
        }
        personalRoute = null
        when (intent?.action) {
            ACTION_OPEN_PERSONAL_MATCH -> {
                val note = intent.getLongExtra("note", 0)
                val reminder = intent.getLongExtra("reminder", 0)
                val day = intent.getStringExtra("day")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                personalRoute = when {
                    note > 0 -> "note/$note"
                    reminder > 0 -> "reminder/edit/$reminder"
                    day != null -> "journal?day=$day"
                    else -> "journal"
                }
            }
            ACTION_REQUEST_MIC_PERMISSION -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            ACTION_OPEN_JOURNAL -> destination = CompanionDestination.JOURNAL
            ACTION_NEW_NOTE -> destination = CompanionDestination.NEW_NOTE
            ACTION_NEW_REMINDER -> destination = CompanionDestination.NEW_REMINDER
            ACTION_OPEN_TODAY -> destination = CompanionDestination.TODAY
            ACTION_OPEN_VOICE -> destination = CompanionDestination.VOICE
            ACTION_OPEN_APPEARANCE -> destination = CompanionDestination.APPEARANCE
            ACTION_OPEN_SETTINGS -> destination = CompanionDestination.SETTINGS
        }
    }
}
