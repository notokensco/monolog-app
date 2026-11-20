package com.notokens.monolog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.notokens.monolog.ui.CompanionLoadingScreen
import com.notokens.monolog.ui.CompanionDestination
import com.notokens.monolog.ui.theme.LocalMonologDark
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_REQUEST_MIC_PERMISSION = "com.notokens.monolog.REQUEST_MIC_PERMISSION"
        const val ACTION_OPEN_JOURNAL = "com.notokens.monolog.OPEN_JOURNAL"
        const val ACTION_OPEN_TODAY = "com.notokens.monolog.OPEN_TODAY"
        const val ACTION_OPEN_SETTINGS = "com.notokens.monolog.OPEN_SETTINGS"
    }

    private var destination by mutableStateOf<CompanionDestination?>(null)
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Microphone enabled for Vox" else "Microphone permission required for Vox", Toast.LENGTH_SHORT).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Reminders are saved, but notifications are disabled", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Monolog)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            val settings = remember { SettingsStore(applicationContext) }
            val appearanceFlow = remember(settings) { settings.appearance.map<Appearance, Appearance?> { it } }
            val appearance by appearanceFlow.collectAsState(initial = null)
            val current = appearance
            MonologTheme(appearance = current ?: Appearance()) {
                val lightBars = !LocalMonologDark.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                if (current == null) {
                    CompanionLoadingScreen()
                } else {
                    CompanionApp(
                        settings = settings,
                        appearance = current,
                        destination = destination,
                        onDestinationOpened = { destination = null },
                        onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                }
            }
        }
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN &&
            intent?.component?.className != "$packageName.KeyboardSettings" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        when (intent?.action) {
            ACTION_REQUEST_MIC_PERMISSION -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            ACTION_OPEN_JOURNAL -> destination = CompanionDestination.JOURNAL
            ACTION_OPEN_TODAY -> destination = CompanionDestination.TODAY
            ACTION_OPEN_SETTINGS -> destination = CompanionDestination.SETTINGS
        }
    }
}
