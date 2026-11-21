package com.notokens.monolog.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class BackupWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = BackupAccountStore.get(applicationContext).state.value
        if (account.session != inputData.getString("session") || !account.connected || account.reconnect ||
            account.frequency == "Manual") return Result.success()
        return try {
            BackupCoordinator(applicationContext).backUp(account)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: DriveConsentRequired) {
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
    companion object {
        private const val NAME = "monolog-drive-backup"
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        internal fun schedule(context: Context, account: BackupAccount) {
            cancel(context)
            if (account.frequency == "Manual" || !account.connected || account.reconnect) return
            val days = if (account.frequency == "Daily") 1L else 7L
            val request = PeriodicWorkRequestBuilder<BackupWork>(days, TimeUnit.DAYS)
                .setInitialDelay(days, TimeUnit.DAYS)
                .setInputData(workDataOf("session" to account.session))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
