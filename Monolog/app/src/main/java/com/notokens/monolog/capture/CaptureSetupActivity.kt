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
                        Text(disclosure)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            launchSystemAccessibility(this@CaptureSetupActivity)
                            finish()
                        }) { Text("Continue to Accessibility settings") }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } }
                )
            }
        }
    }

    companion object {
        private const val disclosure =
            "Turn on Monolog Keyboard Context to use the keyboard’s M key for screenshots. Use the optional shortcut on that same screen for Quick Capture from Android’s accessibility button. " +
                "Monolog uses Android Accessibility to take a screenshot when you activate Quick Capture " +
                "and to locate the Android shortcut button so actions can appear beside it. " +
                "Screenshots are processed on your device and kept in your journal only when you save them. " +
                "If you enable Drive backup separately, saved captures can be included in the encrypted backup. " +
                "You can leave the Android shortcut off and still capture with M."

        private fun launchSystemAccessibility(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun openAccessibilitySettings(context: Context) {
            open(context)
        }

        fun open(context: Context) {
            context.startActivity(Intent(context, CaptureSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
