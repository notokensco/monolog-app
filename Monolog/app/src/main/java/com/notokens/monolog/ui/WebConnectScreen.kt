package com.notokens.monolog.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.notokens.monolog.backup.BackupAccountStore
import com.notokens.monolog.backup.DriveConsentRequired
import com.notokens.monolog.connect.ConnectMode
import com.notokens.monolog.connect.ConnectRequest
import com.notokens.monolog.connect.DriveConnect
import com.notokens.monolog.connect.LocalConnect
import com.notokens.monolog.connect.WebConnectRestoreWork
import com.notokens.monolog.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun WebConnectScreen(onAccount: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val accounts = remember(app) { BackupAccountStore.get(app) }
    val account by accounts.state.collectAsState()
    val settings = remember(app) { SettingsStore(app) }
    val incognito by settings.incognito.collectAsState(true)
    val cloud = remember(app) { DriveConnect(app) }
    val stored by cloud.state.collectAsState(null)
    val remote = stored?.takeIf { it.accountSession == account.session }
    val local by LocalConnect.state.collectAsState()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ConnectMode.LOCAL) }
    var request by remember { mutableStateOf<ConnectRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }

    val activity = remember(context) { context.findWindowActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val wasSecure = window?.attributes?.flags?.let { it and WindowManager.LayoutParams.FLAG_SECURE != 0 } == true
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: DriveConsentRequired) { problem = "Reconnect Google Drive from Account & backup and try again." }
            catch (failure: IllegalArgumentException) { problem = failure.message ?: "This pairing code could not be read." }
            catch (failure: IllegalStateException) { problem = failure.message ?: "The connection could not be completed." }
            catch (_: Exception) { problem = "Couldn’t finish. Check the connection and try again." }
            finally { busy = false }
        }
    }

    fun accept(code: String) {
        val scanned = try {
            ConnectRequest.parse(code, mode)
        } catch (_: Exception) {
            request = null
            problem = "Choose the same connection method on both devices and scan the QR again."
            return
        }
        problem = null
        if (scanned.handoff) run { LocalConnect.start(app, scanned); request = null }
        else request = scanned
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::accept)
        if (result.contents == null && result.originalIntent?.getBooleanExtra("MISSING_CAMERA_PERMISSION", false) == true) {
            problem = "Allow camera access to scan the browser’s QR code."
        }
    }

    LaunchedEffect(incognito) {
        if (incognito) {
            LocalConnect.stop()
            request = null
        }
    }

    SettingsCard("Connect a computer") {
        Text("Open web.monolog.notokens.co on your computer and choose the same connection method.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in ConnectMode.entries) FilterChip(selected = mode == choice, enabled = !busy,
                onClick = { mode = choice; request = null; problem = null },
                label = { Text(if (choice == ConnectMode.LOCAL) "Local Connect" else "Web Connect") })
        }
        if (incognito) Text("Turn off incognito to share your journal.", color = MaterialTheme.colorScheme.error)
        if (mode == ConnectMode.LOCAL) {
            Text("Connect both devices to the same Wi-Fi. Guest mode works. Allow local network access if your browser asks.")
            val session = local
            if (session != null) {
                if (session.paired) Text("Computer connected") else {
                    Text(if (session.manual) "Enter this address and code on your computer" else "Enter this code on your computer")
                    if (session.manual) Text(session.address, style = MaterialTheme.typography.titleMedium)
                    Text(session.pin, style = MaterialTheme.typography.headlineMedium)
                }
                Text("Locking the phone or leaving this Wi-Fi ends pairing.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { LocalConnect.stop() }) { Text("Disconnect") }
            } else if (!incognito) {
                val pending = request?.takeIf { it.mode == ConnectMode.LOCAL }
                if (pending == null) Button(enabled = !busy, onClick = {
                    scanner.launch(pairingScanOptions())
                }) { Text("Scan browser QR") }
                else Button(enabled = !busy, onClick = {
                    run { LocalConnect.start(app, pending); request = null }
                }) { Text("Share over Wi-Fi") }
            }
        } else {
            Text("Use the same Google account on both devices. Sync now on this phone, then sign in on the computer and enter the PIN.")
            val driveReady = account.connected && !account.reconnect
            if (!driveReady) {
                OutlinedButton(onClick = onAccount) { Text("Open Account & backup") }
            } else if (!incognito && remote == null) {
                val pending = request?.takeIf { it.mode == ConnectMode.WEB }
                if (pending == null) Button(enabled = !busy, onClick = { scanner.launch(pairingScanOptions()) }) {
                    Text("Scan browser QR")
                } else {
                    Text(if (pending.stay) "The browser requested Stay signed in. Pairing can remain active for up to 30 days."
                        else "Temporary pairing expires in 24 hours.")
                    Button(enabled = !busy, onClick = {
                        run {
                            if (stored != null) cloud.restore()
                            cloud.start(pending)
                            request = null
                        }
                    }) { Text("Sync now") }
                }
            }
            if (remote != null) {
                if (remote.lastSync > 0 && remote.expiresAt > System.currentTimeMillis()) {
                    Text("Pairing PIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(remote.pin, style = MaterialTheme.typography.headlineMedium)
                } else if (remote.lastSync == 0L) {
                    Text("Tap Sync now to finish preparing this pairing.")
                }
                if (remote.lastSync > 0) Text("Last sync " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(remote.lastSync)), style = MaterialTheme.typography.bodySmall)
                if (remote.applied > 0) Text("${remote.applied} browser changes restored.")
                if (remote.conflicts > 0) Text("${remote.conflicts} browser changes conflicted with newer phone data.",
                    color = MaterialTheme.colorScheme.error)
                Button(enabled = !busy && driveReady && !incognito, onClick = {
                    run { cloud.restore(refreshSnapshot = true) }
                }) { Text("Sync now") }
                OutlinedButton(enabled = !busy && driveReady && !incognito, onClick = {
                    run { cloud.restore() }
                }) { Text("Restore web changes") }
                TextButton(enabled = !busy && driveReady && !incognito, onClick = { confirmEnd = true }) {
                    Text("End Web Connect")
                }
            }
            Text("Save each edit on the computer and restore web changes after each session. The phone also checks Drive about every three hours.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End Web Connect?") },
        text = { Text("Monolog will attempt to restore pending changes, then remove the connection’s temporary files from Drive. Check any conflicting edits in your browser before ending the connection.") },
        confirmButton = { TextButton(onClick = {
            confirmEnd = false
            run { cloud.restore(end = true); WebConnectRestoreWork.cancel(app) }
        }) { Text("End connection") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } }
    )
}

private fun pairingScanOptions() = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setCaptureActivity(PairingScannerActivity::class.java)
    .setPrompt("Fit the QR code inside the square").setBeepEnabled(false)
    .setBarcodeImageEnabled(false).setOrientationLocked(false)

private tailrec fun Context.findWindowActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findWindowActivity()
    else -> null
}
