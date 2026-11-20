package com.notokens.monolog.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notokens.monolog.ui.GlassAccent
import com.notokens.monolog.ui.glassSurface

@Composable
fun SetupScreen(onRequestMicPermission: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var isSelected by remember { mutableStateOf(checkImeSelected(context)) }
    var hasMicPermission by remember { mutableStateOf(hasMicrophonePermission(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = checkImeEnabled(context)
                isSelected = checkImeSelected(context)
                hasMicPermission = hasMicrophonePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepCard(
            title = "Enable Monolog Keyboard",
            description = "Allow Monolog in Android’s keyboard settings.",
            status = if (isEnabled) "Enabled" else null,
            buttonText = "Open Android keyboard settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        )
        StepCard(
            title = "Select your keyboard",
            description = "Switch to Monolog to use it in your apps.",
            status = if (isSelected) "Active keyboard" else null,
            buttonText = "Switch keyboard",
            enabled = isEnabled,
            onAction = { context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        )
        StepCard(
            title = "Microphone access",
            description = "Allow Monolog to listen when you use Vox.",
            status = if (hasMicPermission) "Allowed" else null,
            buttonText = "Grant microphone access",
            onAction = onRequestMicPermission
        )
        Column(
            Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUE).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Try the keyboard", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            TestInputField("Text", KeyboardType.Text, ImeAction.Done)
            TestInputField("Password", KeyboardType.Password, ImeAction.Done)
            TestInputField("Number", KeyboardType.Number, ImeAction.Next)
            TestInputField("Multiline text", KeyboardType.Text, ImeAction.Default, singleLine = false)
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    description: String,
    status: String?,
    buttonText: String,
    onAction: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        Modifier.fillMaxWidth().glassSurface(20).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == null) {
            Button(onClick = onAction, enabled = enabled) { Text(buttonText) }
        } else {
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TestInputField(label: String, type: KeyboardType, action: ImeAction, singleLine: Boolean = true) {
    var value by remember { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = action),
        visualTransformation = if (type == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )
}

private fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun checkImeEnabled(context: Context): Boolean =
    context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList.orEmpty()
        .any { it.packageName == context.packageName }

private fun checkImeSelected(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/') == context.packageName
