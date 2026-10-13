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
 * Calcul des empreintes QuickXorHash des fichiers du téléphone (lecture seule, jamais
 * d'écriture dans un fichier) : par lots ([nextHashBatch] : doublons probables d'abord), sous un
 * budget de temps par passage, lecture en continu par le ContentResolver. Chaque empreinte est
 * enregistrée seulement si le fichier n'a pas changé pendant le calcul ; `media.contentHash`
 * suit. Après chaque lot : dédoublonnage ([MediaMerger]). Un seul passage à la fois.
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

    /** Issue d'un passage. */
    enum class Outcome { DONE, MORE_WORK, SKIPPED }

    /**
     * Calcule des empreintes pendant au plus [budgetMs]. MORE_WORK : budget épuisé avec des
     * fichiers restants ; SKIPPED : sans accès aux médias, ou un autre passage est en cours.
     */
    suspend fun run(budgetMs: Long = PASS_BUDGET_MS): Outcome = withContext(io) {
        if (readMediaAccess(context) == MediaAccess.NONE) return@withContext Outcome.SKIPPED
        if (!running.tryLock()) return@withContext Outcome.SKIPPED
        try {
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            // Fichiers illisibles ou modifiés pendant le calcul : écartés pour la suite de ce
            // passage (réessayés au prochain), jamais relus en boucle.
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

    /** Empreinte du fichier, null s'il est illisible (supprimé, volume démonté, accès retiré). */
    private suspend fun hashOf(target: HashTarget): String? {
        val job = currentCoroutineContext().job
        return try {
            context.contentResolver.openInputStream(Uri.parse(target.uri))?.use { input ->
                // Travail arrêté (charge débranchée) : lecture interrompue entre deux morceaux.
                QuickXorHash.of(input) { if (!job.isActive) throw CancellationException("Calcul d'empreinte arrêté") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Lecture d'un fichier impossible pour son empreinte : ${e.javaClass.simpleName}")
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** Vrai si l'empreinte est enregistrée (fichier inchangé depuis la lecture de sa signature). */
    private suspend fun record(target: HashTarget, hash: String): Boolean {
        if (dedupDao.setLocalHash(target.sourceId, hash, target.signature) == 0) return false
        dedupDao.setContentHash(target.mediaId, contentHashOf(hash))
        return true
    }

    private companion object {
        const val TAG = "LocalHasher"

        /** Passage borné sous les 10 min d'un travail WorkManager. */
        const val PASS_BUDGET_MS = 8L * 60 * 1000
        const val BATCH_COUNT = 100
        const val BATCH_BYTES = 1_000_000_000L
    }
}

/**
 * Travail WorkManager du calcul des empreintes (en charge et batterie non faible, décision
 * utilisateur). S'il reste des fichiers à la fin du budget, un passage suivant est enchaîné.
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
            Log.w(TAG, "Calcul des empreintes interrompu", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "LocalHashWorker"
    }
}

/**
 * Planification du calcul des empreintes : passage unique dès qu'il y a des fichiers à traiter
 * (après une synchro MediaStore), plus un passage quotidien de rattrapage. Toujours en charge et
 * batterie non faible ; rien n'est planifié sans accès aux médias.
 */
@Singleton
class LocalHashScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val constraints: Constraints
        get() = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

    /** Après une synchro MediaStore réussie : travail planifié s'il reste des empreintes à calculer. */
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

    /** Passage suivant, enchaîné après le passage en cours (budget épuisé). */
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
