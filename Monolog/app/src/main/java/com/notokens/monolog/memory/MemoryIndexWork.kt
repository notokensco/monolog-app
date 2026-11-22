package com.notokens.monolog.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.MonologDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

class MemoryIndexWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val encoder = SentenceEncoder.get(applicationContext)
        if (!encoder.ready()) return Result.success()
        return try {
            val db = MonologDatabase.get(applicationContext)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (com.notokens.monolog.data.SettingsStore(applicationContext).incognito.first()) return Result.success()
                val batch = db.memories().unindexed(SentenceEncoder.VERSION, 16)
                if (batch.isEmpty()) break
                for (row in batch) {
                    val evidence = MemoryRepository.decode(row)
                    val source = row.noteId?.let { db.notes().observe(it).first() }?.let {
                        val decoded = com.notokens.monolog.capture.TagMetadataCodec.decode(JournalCipher.open(it.body, "note-body"))
                        if (decoded.hasMetadata) decoded.bottomComment + " " + decoded.tags.joinToString(" ") { tag -> tag.text }
                        else decoded.cleanBody
                    }.orEmpty()
                    val vector = encoder.embed(evidence.summary + " " + source.ifBlank { evidence.excerpt.orEmpty() }) ?: break
                    db.memories().vector(row.id, row.revision, SentenceEncoder.VERSION,
                        JournalCipher.seal(vector, "memory-vector"))
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            encoder.release()
        }
    }
    companion object {
        internal fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("monolog-memory-index",
                ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<MemoryIndexWork>().build())
        }
    }
}
