package com.notokens.monolog.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.theme.MonologTheme

class CaptureSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(applicationContext)
        setContent {
            val appearance by settings.appearance.collectAsState(initial = Appearance())
            MonologTheme(appearance = appearance) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text("Enable quick screenshots") },
                    text = {
                        Text("Enable Monolog Quick Capture in Android Accessibility settings. In Monolog Settings, enable the circular shortcut dock to capture with the keyboard closed. Tap M or the floating button to capture. Toggle the floating dock off to hide it.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            openAccessibilitySettings(this@CaptureSetupActivity)
                            finish()
                        }) { Text("Open Accessibility settings") }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } }
                )
            }
        }
    }

    companion object {
        fun openAccessibilitySettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun open(context: Context) {
            context.startActivity(Intent(context, CaptureSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
