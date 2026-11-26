package com.notokens.monolog.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.notokens.monolog.data.limitProfileName
import java.time.LocalTime
import kotlinx.coroutines.delay

@Composable
internal fun ProfileNameField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(limitProfileName(it)) }, Modifier.fillMaxWidth(),
        label = { Text("What should we call you?") }, singleLine = true, shape = CircleShape,
        supportingText = { Text("Optional · Up to 15 characters") })
}

@Composable
internal fun personalGreeting(name: String): String {
    val hour by produceState(LocalTime.now().hour) {
        while (true) {
            delay(15_000)
            value = LocalTime.now().hour
        }
    }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
    return if (name.isBlank()) greeting else "$greeting, $name"
}
