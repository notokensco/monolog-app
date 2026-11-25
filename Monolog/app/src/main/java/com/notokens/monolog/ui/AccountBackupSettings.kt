package com.notokens.monolog.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.Identity
import com.notokens.monolog.backup.BackupAccountStore
import com.notokens.monolog.backup.BackupCoordinator
import com.notokens.monolog.backup.BackupKeyStore
import com.notokens.monolog.backup.DriveBackup
import com.notokens.monolog.backup.DriveConsentRequired
import com.notokens.monolog.backup.GoogleBackupAuth
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
internal fun AccountBackupSettings(onboarding: Boolean = false) {
    val context = LocalContext.current
    val store = remember { BackupAccountStore.get(context) }
    val account by store.state.collectAsState()
    val coordinator = remember { BackupCoordinator(context.applicationContext) }
    val keys = remember { BackupKeyStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<DriveBackup>()) }
    var authorizationSession by remember { mutableStateOf("") }
    var recovering by remember { mutableStateOf(false) }
    var confirmDeleteBackups by remember { mutableStateOf(false) }
    val activity = remember(context) { context.activity() }
    DisposableEffect(activity) {
        val secure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(account.session) {
        files = emptyList()
        recovering = false
        confirmDeleteBackups = false
    }

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                work()
            } catch (_: GetCredentialCancellationException) {
                message = "Sign-in canceled. Your local journal is available."
            } catch (error: CancellationException) {
                throw error
            } catch (_: DriveConsentRequired) {
                message = "Google needs consent again. Tap Reconnect Drive."
            } catch (_: javax.crypto.AEADBadTagException) {
                message = "This backup could not be unlocked. Local notes were kept."
            } catch (error: com.google.android.gms.common.api.ApiException) {
                message = "Google access failed (code ${error.statusCode}). Check the test account, package and signing SHA-1."
            } catch (_: androidx.credentials.exceptions.GetCredentialException) {
                message = "Google sign-in is unavailable. Check Play services and the registered test account."
            } catch (error: Exception) {
                message = when (error) {
                    is IllegalArgumentException, is IllegalStateException, is java.io.IOException ->
                        error.message?.take(180) ?: "The operation failed. Please try again."
                    else -> "The operation failed. Your local notes are available."
                }
            } finally {
                busy = false
            }
        }
    }

    if (confirmDeleteBackups) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDeleteBackups = false },
        title = { Text("Delete Monolog backups from Drive?") },
        text = { Text("This permanently removes Monolog’s encrypted backups and linked restore keys from ${account.email}. Automatic backups will pause. Your local entries stay on this device.") },
        confirmButton = { TextButton(onClick = {
            confirmDeleteBackups = false
            run {
                val removed = coordinator.deleteMonologBackups(account)
                files = emptyList()
                message = if (removed == 0) "No Monolog backups were found." else "Monolog backups deleted from Drive."
            }
        }) { Text("Delete backups") } },
        dismissButton = { TextButton(onClick = { confirmDeleteBackups = false }) { Text("Cancel") } }
    )

    suspend fun mergeCurrentYearAndBackUp() {
        val selected = store.state.value
        val backups = coordinator.files(selected)
        val year = LocalDate.now(ZoneId.systemDefault()).year
        val current = backups.filter { it.year == year }
        if (current.isNotEmpty()) coordinator.restore(selected, current)
        val count = coordinator.backUp(selected)
        files = coordinator.files(selected)
        message = "Backed up $count entries. Existing account entries and this device's notes were merged."
    }

    suspend fun prepareBackup(mergeExisting: Boolean = false) {
        val selected = store.state.value
        coordinator.linkExistingKey(selected)
        files = coordinator.files(selected)
        if (mergeExisting && files.any { it.year == LocalDate.now().year }) {
            try { mergeCurrentYearAndBackUp() }
            catch (error: IllegalStateException) {
                if (!error.message.orEmpty().contains("linked")) throw error
                message = error.message.orEmpty()
                recovering = true
            }
        } else {
            val count = coordinator.backUp(selected)
            files = coordinator.files(selected)
            message = "Backed up $count entries."
        }
    }

    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            busy = false
            message = "Drive connection canceled. Guest use is available."
        } else run {
            store.requireSession(authorizationSession)
            GoogleBackupAuth.token(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data!!),
                store.state.value)
            store.connected(authorizationSession)
            prepareBackup(mergeExisting = store.state.value.lastBackup == 0L)
        }
    }

    SettingsCard("Google account") {
        Text(if (account.id.isBlank()) "Guest" else account.name.ifBlank { account.email },
            style = MaterialTheme.typography.titleMedium, color = PaperKeyText)
        if (account.id.isNotBlank()) Text(account.email, color = PaperKeySubText)
        Text("Your keyboard and local journal work without an account. Drive backup is optional.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = {
            run {
                val (id, email, name) = GoogleBackupAuth.signIn(context)
                store.select(id, email, name)
                message = "Signed in. Connect Drive separately to merge and back up this device's notes."
            }
        }) { Text(if (account.id.isBlank()) "Sign in with Google" else "Switch Google account") }
        if (account.id.isNotBlank()) TextButton(enabled = !busy, onClick = {
            store.signOut()
            run { GoogleBackupAuth.signOut(context); message = "Signed out. Local notes are available as a guest." }
        }) { Text("Sign out") }
    }

    if (account.id.isNotBlank()) SettingsCard("Encrypted Drive backup") {
        Text("Your encrypted backups and a restore key are stored in this Google account’s private Drive app data. Connect the same account on another phone to restore.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        SelectionRow("Journal entries & notes", account.journal, !busy) {
            store.selection(it, account.context, account.attachments)
        }
        SelectionRow("Saved screen context", account.context, !busy) {
            store.selection(account.journal, it, account.attachments)
        }
        SelectionRow("Keyboard profile", account.keyboard, !busy) { store.keyboardBackup(it) }
        Text("Includes your dictionary, aggregate usage, and keyboard preferences in encrypted backups.",
            style = MaterialTheme.typography.bodySmall)
        SelectionRow("Include attachments", account.attachments, !busy) {
            store.selection(account.journal, account.context, it)
        }
        if (!account.connected || account.reconnect) {
            Text("Connect Drive to merge the current year with existing account backups, then back up your selected guest data.",
                color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && account.hasSelection, onClick = {
                run {
                    val selected = store.state.value
                    authorizationSession = selected.session
                    val result = Identity.getAuthorizationClient(context).authorize(GoogleBackupAuth.request(selected)).await()
                    store.requireSession(selected.session)
                    if (result.hasResolution()) {
                        authorization.launch(IntentSenderRequest.Builder(checkNotNull(result.pendingIntent)).build())
                    } else {
                        GoogleBackupAuth.token(result, selected)
                        store.connected(selected.session)
                        prepareBackup(mergeExisting = selected.lastBackup == 0L)
                    }
                }
            }) { Text(if (account.reconnect) "Reconnect Drive" else "Connect Drive") }
        } else {
            Text("Drive connected", color = PaperKeyText)
            Text(if (account.lastBackup == 0L) "No successful backup yet" else "Last backup · ${formatTime(account.lastBackup)}",
                color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
            if (onboarding) return@SettingsCard
            Button(enabled = !busy && account.hasSelection, onClick = {
                run { prepareBackup() }
            }) { Text("Back up now") }
            OutlinedButton(enabled = !busy, onClick = {
                run {
                    coordinator.linkExistingKey(account)
                    files = coordinator.files(account)
                    if (files.isEmpty()) message = "This account has no Monolog backups."
                    else recovering = true
                }
            }) { Text("Restore") }
            TextButton(enabled = !busy, onClick = { confirmDeleteBackups = true }) { Text("Delete Monolog backups from Drive") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Manual", "Daily", "Weekly", "Monthly").forEach { frequency ->
                    FilterChip(account.frequency == frequency, { store.frequency(frequency) },
                        { Text(frequency) }, enabled = !busy && (frequency == "Manual" ||
                            account.lastBackup > 0 && keys.hasKey(account.id) && account.hasSelection))
                }
            }
            if (account.frequency != "Manual") {
                val preferred = java.time.LocalTime.ofSecondOfDay(account.backupMinute * 60L)
                TextButton(enabled = !busy, onClick = {
                    android.app.TimePickerDialog(context, { _, hour, minute -> store.backupTime(hour, minute) },
                        preferred.hour, preferred.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
                }) {
                    Text("Backup time · " + preferred.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)))
                }
                Text("Android runs backups around this time when the network and battery allow.", color = PaperKeySubText,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Years use local creation dates. After a successful backup, older years appear under Restore. Editing an older local entry makes it visible until its new version is backed up.",
                color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = {
                val selected = account
                store.disconnect()
                run {
                    GoogleBackupAuth.revoke(context, selected)
                    message = "Drive disconnected. Your local notes and existing Drive backups were kept."
                }
            }) { Text("Disconnect Drive") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotBlank()) Text(message, color = PaperKeyText, modifier = Modifier.padding(8.dp))

    if (recovering) {
        AlertDialog(onDismissRequest = { if (!busy) { recovering = false } },
            title = { Text("Restore a year") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Existing local entries are kept. Conflicting edits are restored as separate versions.")
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    files.map { it.year }.distinct().sortedDescending().forEach { year ->
                        OutlinedButton(enabled = !busy, onClick = {
                            run {
                                val selected = store.state.value
                                val report = coordinator.restore(selected, files.filter { it.year == year })
                                recovering = false
                                message = "Restored ${report.imported}, skipped ${report.skipped} duplicates, preserved ${report.conflicts} conflicts."
                                if (selected.lastBackup == 0L) {
                                    mergeCurrentYearAndBackUp()
                                }
                            }
                        }) { Text("Load $year") }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { recovering = false }) { Text("Close") } })
    }
}

@Composable
private fun SelectionRow(text: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onChange, enabled = enabled)
        Text(text, color = PaperKeyText, modifier = Modifier.weight(1f))
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
