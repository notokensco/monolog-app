package com.notokens.monolog.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException

class BackupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = BackupAccountStore.get(applicationContext)
        val account = store.state.value
        if (account.session != inputData.getString("session") || !account.connected || account.reconnect ||
            account.frequency == "Manual") return Result.success()
        val config = inputData.getString("config")
        if (config == null) {
            // Replace the interval-only worker installed by older app versions.
            schedule(applicationContext, account)
            return Result.success()
        }
        if (config != configFor(account)) return Result.success()
        try {
            BackupCoordinator(applicationContext).backUp(account)
        } catch (error: CancellationException) {
            throw error
        } catch (_: DriveConsentRequired) {
            return Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) return Result.retry()
        }
        val current = store.state.value
        if (current.session == account.session && configFor(current) == config &&
            current.connected && !current.reconnect) {
            enqueue(applicationContext, current, ExistingWorkPolicy.APPEND_OR_REPLACE, afterRun = true).result.await()
        }
        return Result.success()
    }
    companion object {
        private const val OLD_NAME = "monolog-drive-backup"
        private const val NAME = "monolog-drive-backup-clock"
        private fun configFor(account: BackupAccount) =
            "${account.frequency}|${account.backupMinute}|${account.journal}|${account.context}|${account.attachments}|${account.keyboard}"

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(OLD_NAME)
            manager.cancelUniqueWork(NAME)
        }

        internal fun schedule(context: Context, account: BackupAccount) {
            WorkManager.getInstance(context).cancelUniqueWork(OLD_NAME)
            if (account.frequency == "Manual" || !account.connected || account.reconnect ||
                !account.hasSelection) {
                WorkManager.getInstance(context).cancelUniqueWork(NAME)
                return
            }
            enqueue(context, account, ExistingWorkPolicy.REPLACE, afterRun = false)
        }

        private fun enqueue(context: Context, account: BackupAccount, policy: ExistingWorkPolicy, afterRun: Boolean): androidx.work.Operation {
            val now = ZonedDateTime.now()
            val at = LocalTime.ofSecondOfDay(account.backupMinute * 60L)
            val today = now.toLocalDate().atTime(at).atZone(now.zone)
            val next = when (account.frequency) {
                "Monthly" -> {
                    val first = today.withDayOfMonth(1)
                    if (!afterRun && first.isAfter(now)) first else first.plusMonths(1)
                }
                "Weekly" -> if (!afterRun && today.isAfter(now)) today else today.plusDays(7)
                else -> if (!afterRun && today.isAfter(now)) today else today.plusDays(1)
            }
            val request = OneTimeWorkRequestBuilder<BackupWork>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("session" to account.session, "config" to configFor(account)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build()
            return WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
