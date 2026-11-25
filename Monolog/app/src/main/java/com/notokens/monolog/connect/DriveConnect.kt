package com.notokens.monolog.connect

import android.content.Context
import androidx.work.*
import com.notokens.monolog.backup.BackupAccount
import com.notokens.monolog.backup.BackupAccountStore
import com.notokens.monolog.backup.DriveConsentRequired
import com.notokens.monolog.backup.DriveConnectFile
import com.notokens.monolog.backup.DriveFiles
import com.notokens.monolog.backup.GoogleBackupAuth
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class DriveConnectSession(
    val channel: ConnectChannel,
    val pin: String,
    val expiresAt: Long,
    val accountSession: String,
    val stay: Boolean,
    val lastSync: Long = 0,
    val applied: Int = 0,
    val conflicts: Int = 0
)

internal class DriveConnect(private val context: Context) {
    private val prefs = context.getSharedPreferences("web_connect", Context.MODE_PRIVATE)
    private val accounts = BackupAccountStore.get(context)
    private val records = ConnectRecords(context)

    val state = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SESSION) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(load())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun start(request: ConnectRequest): DriveConnectSession = withContext(Dispatchers.IO) {
        lock.withLock {
            check(load() == null) { "End the current Web Connect session first." }
            val account = accounts.state.value
            val drive = drive(account)
            val pairing = ConnectProtocol.pair(request)
            val session = DriveConnectSession(pairing.channel, pairing.pin, pairing.expiresAt, account.session, request.stay)
            save(session)
            WebConnectRestoreWork.schedule(context)
            publish(drive, session)
            save(session.copy(lastSync = System.currentTimeMillis()))
        }
    }

    suspend fun restore(refreshSnapshot: Boolean = false, end: Boolean = false): DriveConnectSession? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val session = load() ?: return@withLock null
                val account = accounts.state.value
                if (account.session != session.accountSession) {
                    forget(session)
                    return@withLock null
                }
                val drive = drive(account)
                val existing = drive.connectFiles(session.channel.session)
                val receipts = existing.filter { it.role == "receipt" }.mapTo(mutableSetOf()) { it.operation }
                var applied = 0
                var conflicts = 0
                for (file in existing.filter { it.role == "command" }) {
                    accounts.requireSession(session.accountSession)
                    if (!ConnectRecords.validOperation(file.operation) || file.size !in 28..1_650_000 ||
                        file.createdAt > session.expiresAt) {
                        drive.deleteConnect(file)
                        continue
                    }
                    val envelope = drive.downloadConnect(file)
                    val command = try {
                        JSONObject(ConnectProtocol.open(session.channel, "command", envelope).toString(Charsets.UTF_8))
                    } catch (_: javax.crypto.AEADBadTagException) {
                        drive.deleteConnect(file)
                        continue
                    } catch (_: org.json.JSONException) {
                        drive.deleteConnect(file)
                        continue
                    }
                    if (command.optString("operationId") != file.operation ||
                        command.optString("op") !in setOf("save", "delete")) {
                        drive.deleteConnect(file)
                        continue
                    }
                    accounts.requireSession(session.accountSession)
                    val result = records.mutate(session.channel.session, session.expiresAt, command)
                    if (result.optBoolean("ok")) applied++ else conflicts++
                    if (receipts.add(file.operation)) {
                        val reply = ConnectProtocol.seal(session.channel, "receipt", result.toString().toByteArray())
                        accounts.requireSession(session.accountSession)
                        drive.uploadConnect(session.channel.session, "receipt", session.expiresAt, reply, operation = file.operation)
                    }
                    if (result.optBoolean("ok")) {
                        accounts.requireSession(session.accountSession)
                        drive.deleteConnect(file)
                    }
                }
                if (end || session.expiresAt <= System.currentTimeMillis()) {
                    for (file in drive.connectFiles(session.channel.session)) {
                        accounts.requireSession(session.accountSession)
                        drive.deleteConnect(file)
                    }
                    forget(session)
                    return@withLock null
                }
                if (refreshSnapshot || session.lastSync == 0L || applied > 0 && existing.any { it.role == "snapshot" }) {
                    publish(drive, session, existing)
                }
                save(session.copy(lastSync = System.currentTimeMillis(), applied = applied, conflicts = conflicts))
            }
        }

    private suspend fun publish(drive: DriveFiles, session: DriveConnectSession,
        existing: List<DriveConnectFile> = emptyList()
    ) {
        val snapshot = records.snapshot().getJSONObject("snapshot")
        val notes = snapshot.getJSONArray("notes")
        val attachments = existing.filter { it.role == "attachment" }.associateBy { it.item }
        val retainedIds = mutableSetOf<String>()
        for (index in 0 until notes.length()) {
            val note = notes.getJSONObject(index)
            val photos = note.getJSONArray("photos")
            val available = org.json.JSONArray()
            for (photoIndex in 0 until photos.length()) {
                val photo = photos.getJSONObject(photoIndex)
                val id = photo.getString("id")
                val existingId = attachments[id]?.id
                val fileId = if (existingId != null) existingId else {
                    val bytes = records.photoBytes(id) ?: continue
                    accounts.requireSession(session.accountSession)
                    drive.uploadConnect(session.channel.session, "attachment", session.expiresAt,
                        ConnectProtocol.seal(session.channel, "attachment|$id", bytes), item = id)
                }
                retainedIds += fileId
                available.put(photo.put("fileId", fileId))
            }
            note.put("photos", available)
        }
        val clear = snapshot.toString().toByteArray()
        require(clear.size <= ConnectRecords.MAX_SNAPSHOT_BYTES) { "This journal is too large to share in one session." }
        val envelope = ConnectProtocol.seal(session.channel, "snapshot", clear)
        accounts.requireSession(session.accountSession)
        drive.uploadConnect(session.channel.session, "snapshot", session.expiresAt, envelope,
            phoneKey = session.channel.phoneKey)
        for (old in existing) {
            if (old.role == "snapshot" || old.role == "attachment" && old.id !in retainedIds) {
                accounts.requireSession(session.accountSession)
                drive.deleteConnect(old)
            }
        }
    }

    private suspend fun drive(account: BackupAccount): DriveFiles {
        check(account.connected && !account.reconnect && account.id.isNotEmpty()) { "Connect Google Drive from Account & backup first." }
        check(!SettingsStore(context).incognito.first()) { "Turn off incognito before syncing your journal." }
        val token = try { GoogleBackupAuth.accessToken(context, account) }
        catch (error: DriveConsentRequired) {
            accounts.needsConsent(account.session)
            throw error
        } catch (error: com.google.android.gms.common.api.ApiException) {
            if (error.statusCode !in listOf(4, 5, 16)) throw error
            accounts.needsConsent(account.session)
            throw DriveConsentRequired()
        }
        accounts.requireSession(account.session)
        return DriveFiles(token, "web-connect", "")
    }

    private fun save(session: DriveConnectSession): DriveConnectSession {
        val row = JSONObject().put("session", session.channel.session).put("phoneKey", session.channel.phoneKey)
            .put("key", base64Url(session.channel.key)).put("pin", session.pin).put("expiresAt", session.expiresAt)
            .put("account", session.accountSession).put("stay", session.stay).put("lastSync", session.lastSync)
            .put("applied", session.applied).put("conflicts", session.conflicts)
        check(prefs.edit().putString(SESSION, JournalCipher.seal(row.toString(), SESSION)).commit()) {
            "Could not save the Web Connect session."
        }
        return session
    }

    private fun load(): DriveConnectSession? = prefs.getString(SESSION, null)?.let { saved ->
        runCatching {
            val row = JSONObject(JournalCipher.open(saved, SESSION))
            val channel = ConnectChannel(row.getString("session"), row.getString("phoneKey"), decodeUrl(row.getString("key")))
            DriveConnectSession(channel, row.getString("pin"), row.getLong("expiresAt"), row.getString("account"),
                row.optBoolean("stay"), row.optLong("lastSync"), row.optInt("applied"), row.optInt("conflicts"))
        }.getOrNull()
    }

    private suspend fun forget(session: DriveConnectSession) {
        check(prefs.edit().remove(SESSION).commit())
        records.clearReceipts(session.channel.session)
    }

    suspend fun discardLocalSession() = withContext(Dispatchers.IO) {
        lock.withLock {
            WebConnectRestoreWork.cancel(context)
            load()?.let { forget(it) }
        }
    }

    companion object {
        private const val SESSION = "web-connect-session-v1"
        private val lock = Mutex()
    }
}

class WebConnectRestoreWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (DriveConnect(applicationContext).restore() == null) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(NAME)
        }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: DriveConsentRequired) { Result.success() }
    catch (_: IllegalStateException) { Result.success() }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.success() }

    companion object {
        private const val NAME = "monolog-web-connect-restore"
        internal fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebConnectRestoreWork>(3, TimeUnit.HOURS)
                .setInitialDelay(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        internal fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
