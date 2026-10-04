package com.hesi.slog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a week of AI notification messages (7, one per day) for a log with the on-device model.
 *
 * - With SPECIFIC_LOG_ID / SPECIFIC_LOG_NAME (log created, renamed or imported): that log, always.
 * - Without (daily check / app start): only my logs whose week is over, or that have no messages
 *   yet, or were renamed. On most days that's none, and the model isn't even loaded.
 */
class AIBatchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val specificId = inputData.getString("SPECIFIC_LOG_ID")
        val specificName = inputData.getString("SPECIFIC_LOG_NAME")

        val todo: List<Pair<String, String>> = try {
            if (specificId != null && specificName != null) {
                listOf(specificId to specificName)
            } else {
                val user = FirebaseRepo.currentUser ?: return@withContext Result.success()
                FirebaseRepo.fetchLogs(FirebaseRepo.emailOf(user))
                    .filter { AiMessages.needsBatch(ctx, it.id, it.name) }
                    .map { it.id to it.name }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry()
        }
        if (todo.isEmpty()) return@withContext Result.success()

        var inference: ReminderInference? = null
        try {
            val model = ReminderInference(ctx)
            inference = model
            for ((id, name) in todo) {
                val category = AiMessages.categoryFor(ctx, name)
                val batch = model.generateBatch(category, AiMessages.DAYS)
                if (batch.isNotEmpty()) AiMessages.save(ctx, id, name.trim(), category, batch)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // a broken/missing model won't fix itself by retrying forever
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            inference?.close()
        }
    }
}
