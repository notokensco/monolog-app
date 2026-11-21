package com.notokens.monolog.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.backup.BackupAccountStore
import com.notokens.monolog.backup.BackupCoordinator
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun visibleBackupYears(notes: List<Note>): List<Note> {
    val context = LocalContext.current
    val account by BackupAccountStore.get(context).state.collectAsState()
    val archives by remember(account.id) {
        MonologDatabase.get(context).backups().archives(account.id)
    }.collectAsState(initial = emptyList())
    val year = LocalDate.now(ZoneId.systemDefault()).year
    return remember(notes, archives, account, year) {
        if (!account.connected || account.id.isBlank()) notes else {
            val saved = archives.associate { it.noteId to it.fingerprint }
            notes.filter { note ->
                val noteYear = BackupCoordinator.year(note)
                noteYear >= year || noteYear in account.loadedYears ||
                    saved[note.id] != BackupCoordinator.fingerprint(note)
            }
        }
    }
}

@Composable
internal fun ArchivedYearsLink(onOpen: () -> Unit) {
    val account by BackupAccountStore.get(LocalContext.current).state.collectAsState()
    if (account.connected && account.lastBackup > 0) TextButton(onOpen) { Text("Load past years from Drive") }
}
