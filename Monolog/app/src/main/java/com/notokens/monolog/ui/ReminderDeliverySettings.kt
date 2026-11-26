package com.notokens.monolog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notokens.monolog.reminders.ReminderCalendar
import com.notokens.monolog.reminders.ReminderScheduler
import com.notokens.monolog.ui.theme.PaperKeySubText
import kotlinx.coroutines.launch

@Composable
internal fun ReminderDeliverySettings() {
    val context = LocalContext.current
    val calendar = remember(context) { ReminderCalendar(context.applicationContext) }
    val alarms = remember(context) { ReminderScheduler(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var exact by remember { mutableStateOf(alarms.exactEnabled) }
    var alerts by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<ReminderCalendar.Choice>?>(null) }
    var selected by remember { mutableLongStateOf(calendar.selectedId) }
    var menuOpen by remember { mutableStateOf(false) }
    val requestAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { alerts = it }
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (calendar.hasAccess()) scope.launch {
            choices = calendar.choices()
            menuOpen = true
        }
    }
    DisposableEffect(lifecycle, calendar, alarms) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exact = alarms.exactEnabled
                alerts = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                scope.launch { choices = if (calendar.hasAccess()) calendar.choices() else null }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SettingsCard("Phone calendar and alerts") {
        Text("Reminders use your phone’s alarm sound. Choose a calendar to also show new reminders there. Your calendar account may sync those entries.",
            color = PaperKeySubText)
        Box {
            val name = choices?.firstOrNull { it.id == selected }?.name
            TextButton(onClick = {
                if (!calendar.hasAccess()) requestCalendar.launch(ReminderCalendar.permissions)
                else scope.launch { choices = calendar.choices(); menuOpen = true }
            }) { Text(name?.let { "Calendar: $it" } ?: "Choose phone calendar") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (choices.isNullOrEmpty()) {
                    DropdownMenuItem(text = { Text("No writable calendars available") }, onClick = { menuOpen = false })
                }
                choices.orEmpty().forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.account?.let { "${choice.name} · $it" } ?: "${choice.name} · On this device") },
                        onClick = { calendar.selectedId = choice.id; selected = choice.id; menuOpen = false })
                }
                if (selected >= 0) DropdownMenuItem(text = { Text("Stop adding new calendar entries") }, onClick = {
                    calendar.selectedId = -1L
                    selected = -1L
                    menuOpen = false
                })
            }
        }
        if (!alerts) TextButton({ requestAlerts.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Allow reminder alerts")
        }
        if (!exact) TextButton({
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
            try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { }
        }) { Text("Allow on-time alarms") }
    }
}
