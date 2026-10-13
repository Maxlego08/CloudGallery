package fr.eva.cloudgallery.data.dedup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.local.readMediaAccess
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.MediaAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes the QuickXorHash of phone files (read-only, never writes to a file): in batches
 * ([nextHashBatch]: likely duplicates first), within a time budget per pass, streaming through
 * the ContentResolver. Each hash is stored only if the file did not change during the
 * computation; `media.contentHash` follows. After each batch: deduplication ([MediaMerger]).
 * One pass at a time.
 */
@Singleton
class LocalHasher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val merger: MediaMerger,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dedupDao = db.dedupDao()
    private val running = Mutex()

    /** Outcome of a pass. */
    enum class Outcome { DONE, MORE_WORK, SKIPPED }

    /**
     * Computes hashes for at most [budgetMs]. MORE_WORK: budget exhausted with files remaining;
     * SKIPPED: no media access, or another pass is running.
     */
    suspend fun run(budgetMs: Long = PASS_BUDGET_MS): Outcome = withContext(io) {
        if (readMediaAccess(context) == MediaAccess.NONE) return@withContext Outcome.SKIPPED
        if (!running.tryLock()) return@withContext Outcome.SKIPPED
        try {
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            // Files unreadable or modified during the computation: set aside for the rest of this
            // pass (retried on the next one), never re-read in a loop.
            val skipped = HashSet<Long>()
            var outcome = Outcome.DONE
            while (true) {
                val targets = dedupDao.staleLocalHashes().filter { it.sourceId !in skipped }
                if (targets.isEmpty()) break
                if (SystemClock.elapsedRealtime() >= deadline) {
                    outcome = Outcome.MORE_WORK
                    break
                }
                val batch = nextHashBatch(targets, dedupDao.visibleCloudSizes().toSet(), BATCH_COUNT, BATCH_BYTES)
                var written = 0
                for (target in batch) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    val hash = hashOf(target)
                    if (hash != null && record(target, hash)) written++ else skipped += target.sourceId
                }
                if (written > 0) merger.reconcile()
            }
            outcome
        } finally {
            running.unlock()
        }
    }

    /** Hash of the file, null if it is unreadable (deleted, volume unmounted, access revoked). */
    private suspend fun hashOf(target: HashTarget): String? {
        val job = currentCoroutineContext().job
        return try {
            context.contentResolver.openInputStream(Uri.parse(target.uri))?.use { input ->
                // Work stopped (charger unplugged): read interrupted between two chunks.
                QuickXorHash.of(input) { if (!job.isActive) throw CancellationException("Hash computation stopped") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read a file to hash it: ${e.javaClass.simpleName}")
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** True if the hash was stored (file unchanged since its signature was read). */
    private suspend fun record(target: HashTarget, hash: String): Boolean {
        if (dedupDao.setLocalHash(target.sourceId, hash, target.signature) == 0) return false
        dedupDao.setContentHash(target.mediaId, contentHashOf(hash))
        return true
    }

    private companion object {
        const val TAG = "LocalHasher"

        /** Pass capped below the 10 min limit of a WorkManager job. */
        const val PASS_BUDGET_MS = 8L * 60 * 1000
        const val BATCH_COUNT = 100
        const val BATCH_BYTES = 1_000_000_000L
    }
}

/**
 * WorkManager job computing hashes (while charging and battery not low, user decision). If files
 * remain at the end of the budget, a follow-up pass is chained.
 */
@HiltWorker
class LocalHashWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val hasher: LocalHasher,
    private val scheduler: LocalHashScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            if (hasher.run() == LocalHasher.Outcome.MORE_WORK) scheduler.continueLater()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Hash computation interrupted", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "LocalHashWorker"
    }
}

/**
 * Scheduling of hash computation: a one-off pass as soon as there are files to process (after a
 * MediaStore sync), plus a daily catch-up pass. Always while charging and battery not low;
 * nothing is scheduled without media access.
 */
@Singleton
class LocalHashScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val constraints: Constraints
        get() = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

    /** After a successful MediaStore sync: work scheduled if hashes remain to be computed. */
    suspend fun onLocalIndexUpdated() = withContext(io) {
        if (readMediaAccess(context) == MediaAccess.NONE) return@withContext
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            DAILY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalHashWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        if (db.dedupDao().hasStaleLocalHashes()) enqueue(ExistingWorkPolicy.KEEP)
    }

    /** Next pass, chained after the current pass (budget exhausted). */
    fun continueLater() = enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            policy,
            OneTimeWorkRequestBuilder<LocalHashWorker>().setConstraints(constraints).build(),
        )
    }

    private companion object {
        const val ONE_TIME_WORK = "local_hash"
        const val DAILY_WORK = "local_hash_daily"
    }
}
