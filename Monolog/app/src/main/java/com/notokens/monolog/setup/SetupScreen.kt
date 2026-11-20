package com.notokens.monolog.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notokens.monolog.ui.theme.MonologBorder
import com.notokens.monolog.ui.theme.MonologInkBackground
import com.notokens.monolog.ui.theme.MonologInkSurface
import com.notokens.monolog.ui.theme.MonologKeyAccent
import com.notokens.monolog.ui.theme.MonologKeyText
import com.notokens.monolog.ui.theme.MonologKeyTextMuted

@Composable
fun SetupScreen(
    onRequestMicPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var isSelected by remember { mutableStateOf(checkImeSelected(context)) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Re-check IME & mic status whenever user returns from Settings or Keyboard Picker
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = checkImeEnabled(context)
                isSelected = checkImeSelected(context)
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var normalText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var numberText by remember { mutableStateOf("") }
    var multilineText by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MonologInkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Branding
        Text(
            text = "Monolog",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Text(
            text = "Type. Speak. Act. Remember.",
            color = MonologKeyTextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // Status Banner
        StatusBanner(isEnabled = isEnabled, isSelected = isSelected)

        Spacer(modifier = Modifier.height(20.dp))

        // Step 1: Enable Monolog
        StepCard(
            stepNumber = "1",
            title = "Enable Monolog Keyboard",
            description = "Allow Monolog in Android Settings to make it available.",
            isComplete = isEnabled,
            buttonText = "Open Keyboard Settings",
            onAction = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Step 2: Select Monolog
        StepCard(
            stepNumber = "2",
            title = "Select Input Method",
            description = "Switch your active keyboard to Monolog.",
            isComplete = isSelected,
            isEnabled = isEnabled,
            buttonText = "Switch Keyboard",
            onAction = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Step 3: Enable Vox Voice Typing (Microphone permission)
        StepCard(
            stepNumber = "3",
            title = "Microphone Access (Vox Mode)",
            description = "Allow voice input for on-device dictation and transcript review.",
            isComplete = hasMicPermission,
            isEnabled = true,
            buttonText = "Grant Microphone Access",
            onAction = onRequestMicPermission
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Step 4: Interactive Verification Arena
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MonologBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MonologInkSurface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C3E50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "4", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Interactive Test Drive",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Tap each field to test responsive QWERTY typing, password privacy shielding, and numbers.",
                    color = MonologKeyTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
                )

                // Standard field
                TestInputField(
                    label = "Standard Field (Chat / Note)",
                    value = normalText,
                    onValueChange = { normalText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Password field
                TestInputField(
                    label = "Password Field (Protected Incognito)",
                    value = passwordText,
                    onValueChange = { passwordText = it },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Number field
                TestInputField(
                    label = "Number Field",
                    value = numberText,
                    onValueChange = { numberText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Multiline field
                TestInputField(
                    label = "Multiline Field (Enter creates newline)",
                    value = multilineText,
                    onValueChange = { multilineText = it },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default)
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun StatusBanner(isEnabled: Boolean, isSelected: Boolean) {
    val isReady = isEnabled && isSelected
    val bgColor = if (isReady) Color(0xFF1B382B) else Color(0xFF2B2519)
    val textColor = if (isReady) Color(0xFF81C784) else Color(0xFFFFB74D)
    val statusText = when {
        isReady -> "✓ Monolog is active and ready to type!"
        !isEnabled -> "Action needed: Enable Monolog in Settings below"
        else -> "Action needed: Select Monolog as active keyboard"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = statusText,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StepCard(
    stepNumber: String,
    title: String,
    description: String,
    isComplete: Boolean,
    isEnabled: Boolean = true,
    buttonText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MonologBorder, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MonologInkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isComplete) Color(0xFF2E7D32) else Color(0xFF373944)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isComplete) "✓" else stepNumber,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = MonologKeyTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                if (!isComplete) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onAction,
                        enabled = isEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = MonologKeyAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = buttonText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TestInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp, color = MonologKeyTextMuted) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = MonologKeyText,
            focusedBorderColor = MonologKeyAccent,
            unfocusedBorderColor = MonologBorder,
            focusedContainerColor = MonologInkBackground,
            unfocusedContainerColor = MonologInkBackground
        )
    )
}

fun checkImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
    val list = imm.enabledInputMethodList
    return list.any { it.packageName == context.packageName }
}

fun checkImeSelected(context: Context): Boolean {
    val currentIme = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    ) ?: return false
    return currentIme.startsWith(context.packageName)
}
