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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.notokens.monolog.ui.CompanionApp

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_REQUEST_MIC_PERMISSION = "com.notokens.monolog.REQUEST_MIC_PERMISSION"
        const val ACTION_OPEN_JOURNAL = "com.notokens.monolog.OPEN_JOURNAL"
        const val ACTION_OPEN_TODAY = "com.notokens.monolog.OPEN_TODAY"
    }

    private var startRoute by mutableStateOf("today")
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Microphone enabled for Vox" else "Microphone permission required for Vox", Toast.LENGTH_SHORT).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Reminders are saved, but notifications are disabled", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startRoute = if (intent?.action == ACTION_OPEN_JOURNAL) "journal" else "today"
        setContent {
            key(startRoute) {
                CompanionApp(startRoute = startRoute, onRequestMicPermission = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
        }
        if (intent?.action == ACTION_REQUEST_MIC_PERMISSION) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_JOURNAL) startRoute = "journal"
        if (intent.action == ACTION_OPEN_TODAY) startRoute = "today"
        if (intent.action == ACTION_REQUEST_MIC_PERMISSION) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
