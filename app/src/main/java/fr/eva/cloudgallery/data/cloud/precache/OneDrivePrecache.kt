package fr.eva.cloudgallery.data.cloud.precache

import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailFetcher
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.PrecacheRow
import fr.eva.cloudgallery.data.mapper.cloudThumbRef
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.PrecacheEstimate
import fr.eva.cloudgallery.domain.model.PrecacheOutcome
import fr.eva.cloudgallery.domain.model.PrecacheState
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.OneDrivePrecacheRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Tailles téléchargées pour cet appareil : palier de vignette de la grille Photos au zoom par
 * défaut, et grand côté de l'écran pour l'aperçu de la visionneuse (même calcul que la
 * visionneuse : `screenWidthDp` / `screenHeightDp` × densité).
 */
internal class PrecacheTargets(val gridBucket: Int, private val viewportLongPx: Int) {

    fun previewSide(row: PrecacheRow, maxAcceptedSide: Int): Int = precachePreviewSide(
        viewportLongPx = viewportLongPx,
        imageLongPx = max(row.width, row.height).takeIf { it > 0 },
        isVideo = row.mediaType == MediaType.VIDEO,
        maxAcceptedSide = maxAcceptedSide,
    )

    companion object {
        fun of(resources: Resources): PrecacheTargets {
            val configuration = resources.configuration
            val density = resources.displayMetrics.density
            val shortPx = (min(configuration.screenWidthDp, configuration.screenHeightDp) * density).toInt()
            val longPx = (max(configuration.screenWidthDp, configuration.screenHeightDp) * density).toInt()
            return PrecacheTargets(precacheGridBucket(shortPx), longPx)
        }
    }
}

/** Référence de miniature d'une ligne (même version que l'affichage : cTag, sinon date de modification). */
private fun PrecacheRow.ref() = cloudThumbRef(cloudItemId, cloudCTag, dateModified)!!

/**
 * Passage de mise en cache de tout OneDrive : pour chaque élément dont la miniature vient de
 * OneDrive (les plus récents d'abord), vignette de grille puis aperçu de la visionneuse, en
 * sautant ce qui est déjà enregistré (reprise sans retéléchargement). Service de premier plan
 * (type dataSync, notification avec « Annuler ») quand il est lancé appli visible ; sinon
 * travail ordinaire, interrompu et repris par WorkManager.
 *
 * S'arrête proprement sous l'espace libre minimal ([precacheHasRoom]), à la déconnexion, après
 * trop d'échecs réseau consécutifs (reprise plus tard) ou sur une limitation Graph. Un passage
 * complet retire ensuite les fichiers des éléments disparus ou modifiés. En mode
 * [KEY_CLEANUP_ONLY], seul ce nettoyage est fait (aucune requête réseau).
 *
 * Journal sans URL, jeton, identifiant d'élément ni nom de compte.
 */
@HiltWorker
class OneDrivePrecacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaDao: MediaDao,
    private val store: CloudPrecacheStore,
    private val precacher: CloudPrecacher,
    private val guard: CloudAccessGuard,
    private val thumbs: CloudThumbnailFetcher.Factory,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {

    private var foreground = false

    override suspend fun doWork(): Result = withContext(io) {
        try {
            if (inputData.getBoolean(KEY_CLEANUP_ONLY, false)) {
                cleanup()
                Result.success()
            } else {
                precacheAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrecacheClearedException) {
            // « Vider le cache » pendant le passage : rien à reprendre, aucun bilan.
            Result.success()
        } catch (e: CloudAuthRequiredException) {
            Result.success(outcomeData(PrecacheOutcome.SIGNED_OUT, Counters()))
        } catch (e: Exception) {
            Log.w(TAG, "Mise en cache de OneDrive interrompue, reprise plus tard", e)
            Result.retry()
        }
    }

    private suspend fun precacheAll(): Result {
        guard.ensureSignedIn()
        val counters = Counters()
        counters.total = mediaDao.countPrecacheCandidates()
        enterForeground(counters)
        val targets = PrecacheTargets.of(applicationContext.resources)
        val token = store.writeToken()
        val keep = HashSet<String>(counters.total * 2)
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        val consecutiveFailures = AtomicInteger(0)
        var before = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = mediaDao.precacheCandidates(before, PAGE_SIZE)
            if (page.isEmpty()) break
            before = page.last().id
            val (usable, total) = store.volumeSpace()
            if (!precacheHasRoom(usable, total)) {
                Log.i(TAG, "Mise en cache arrêtée : espace libre insuffisant")
                return Result.success(outcomeData(PrecacheOutcome.LOW_SPACE, counters))
            }
            page.forEach { keep += precacheHash(it.ref()) }
            coroutineScope {
                page.map { row ->
                    async { permits.withPermit { precacheRow(row, targets, token, counters, consecutiveFailures) } }
                }.awaitAll()
            }
            if (precacheShouldPause(consecutiveFailures.get())) {
                Log.w(TAG, "Mise en cache en pause : trop d'échecs consécutifs")
                return Result.retry()
            }
            report(counters)
        }
        val removed = store.retainOnly(keep)
        Log.i(TAG, "Mise en cache terminée : ${counters.done.get()} élément(s), ${counters.failed.get()} sans miniature ou en échec, $removed fichier(s) obsolète(s) retiré(s)")
        return Result.success(outcomeData(PrecacheOutcome.COMPLETED, counters))
    }

    private suspend fun precacheRow(
        row: PrecacheRow,
        targets: PrecacheTargets,
        token: CloudPrecacheStore.WriteToken,
        counters: Counters,
        consecutiveFailures: AtomicInteger,
    ) {
        val ref = row.ref()
        try {
            val results = listOf(
                precacher.grid(ref, targets.gridBucket, token),
                precacher.preview(ref, targets.previewSide(row, thumbs.maxPreviewSide), token),
            )
            results.forEach { if (it is PrecacheFileResult.Saved) counters.bytes.addAndGet(it.bytes) }
            if (results.any { it is PrecacheFileResult.NoThumbnail }) counters.failed.incrementAndGet()
            consecutiveFailures.set(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrecacheClearedException) {
            throw e
        } catch (e: CloudAuthRequiredException) {
            throw e
        } catch (e: CloudThrottledException) {
            // Pause demandée par Graph : le passage reprendra plus tard (WorkManager).
            throw e
        } catch (e: CloudException) {
            counters.failed.incrementAndGet()
            consecutiveFailures.incrementAndGet()
        } catch (e: IOException) {
            counters.failed.incrementAndGet()
            consecutiveFailures.incrementAndGet()
        } finally {
            counters.done.incrementAndGet()
        }
    }

    /** Nettoyage seul : fichiers des éléments disparus, modifiés ou désormais présents sur le téléphone. */
    private suspend fun cleanup() {
        if (store.isEmpty()) return
        val keep = HashSet<String>()
        var before = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = mediaDao.precacheCandidates(before, PAGE_SIZE * 10)
            if (page.isEmpty()) break
            before = page.last().id
            page.forEach { keep += precacheHash(it.ref()) }
        }
        val removed = store.retainOnly(keep)
        if (removed > 0) Log.i(TAG, "Mise en cache : $removed fichier(s) obsolète(s) retiré(s)")
    }

    private suspend fun report(counters: Counters) {
        setProgress(counters.toData())
        if (foreground) {
            try {
                setForeground(foregroundInfo(counters))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Notification non mise à jour : sans effet sur le passage.
                Log.w(TAG, "Notification de mise en cache non mise à jour", e)
            }
        }
    }

    /**
     * Service de premier plan (dataSync) : seulement possible appli visible (Android 12+). Lancé
     * en arrière-plan (« Garder à jour », reprise), le passage continue en travail ordinaire.
     */
    private suspend fun enterForeground(counters: Counters) {
        try {
            PrecacheNotifications.ensureChannel(applicationContext)
            setForeground(foregroundInfo(counters))
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Mise en cache sans service de premier plan (lancée en arrière-plan)")
        }
    }

    private fun foregroundInfo(counters: Counters): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = PrecacheNotifications.progress(
            applicationContext,
            counters.done.get(),
            counters.total,
            counters.bytes.get(),
            cancel,
        )
        return ForegroundInfo(PrecacheNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun outcomeData(outcome: PrecacheOutcome, counters: Counters): Data =
        Data.Builder().putAll(counters.toData()).putString(KEY_OUTCOME, outcome.name).build()

    /** Avancement du passage (partagé entre les téléchargements parallèles). */
    private class Counters {
        @Volatile
        var total = 0
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val bytes = AtomicLong(0)

        fun toData(): Data = workDataOf(
            KEY_DONE to done.get(),
            KEY_TOTAL to total,
            KEY_BYTES to bytes.get(),
            KEY_FAILED to failed.get(),
        )
    }

    internal companion object {
        const val TAG = "OneDrivePrecache"
        const val KEY_CLEANUP_ONLY = "cleanup_only"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES = "bytes"
        const val KEY_FAILED = "failed"
        const val KEY_OUTCOME = "outcome"
        const val PAGE_SIZE = 60
        const val PARALLEL_DOWNLOADS = 3
    }
}

/** Travaux WorkManager de la mise en cache (passage complet, nettoyage seul). */
internal object PrecacheWork {
    const val FULL = "onedrive_precache"
    const val CLEANUP = "onedrive_precache_cleanup"

    /**
     * Passage complet. [append] : ajouté après le passage en cours (« Garder à jour » après une
     * synchro) au lieu d'être ignoré s'il y en a déjà un. Réseau non facturé si le réglage Wi-Fi
     * des téléchargements ([wifiOnly]) l'exige ; batterie et stockage non faibles.
     */
    fun enqueueFull(context: Context, wifiOnly: Boolean, append: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<OneDrivePrecacheWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(FULL, policy, request)
    }

    /** Nettoyage seul (sans réseau), regroupé avec un nettoyage déjà prévu. */
    fun enqueueCleanup(context: Context) {
        val request = OneTimeWorkRequestBuilder<OneDrivePrecacheWorker>()
            .setInputData(workDataOf(OneDrivePrecacheWorker.KEY_CLEANUP_ONLY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CLEANUP, ExistingWorkPolicy.KEEP, request)
    }

    /** Arrête tout (« Vider le cache », données OneDrive retirées). */
    fun cancelAll(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(FULL)
        work.cancelUniqueWork(CLEANUP)
    }

    private const val BACKOFF_MINUTES = 5L
}

private fun WorkInfo.toSnapshot(): PrecacheWorkSnapshot {
    val data = if (state == WorkInfo.State.SUCCEEDED) outputData else progress
    val status = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> PrecacheWorkStatus.WAITING
        WorkInfo.State.RUNNING -> PrecacheWorkStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> PrecacheWorkStatus.SUCCEEDED
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> PrecacheWorkStatus.STOPPED
    }
    return PrecacheWorkSnapshot(
        status = status,
        done = data.getInt(OneDrivePrecacheWorker.KEY_DONE, 0),
        total = data.getInt(OneDrivePrecacheWorker.KEY_TOTAL, 0),
        bytes = data.getLong(OneDrivePrecacheWorker.KEY_BYTES, 0L),
        failed = data.getInt(OneDrivePrecacheWorker.KEY_FAILED, 0),
        outcome = data.getString(OneDrivePrecacheWorker.KEY_OUTCOME)?.let { name ->
            PrecacheOutcome.entries.firstOrNull { it.name == name }
        },
    )
}

@Singleton
class OneDrivePrecacheRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val precacher: CloudPrecacher,
    private val store: CloudPrecacheStore,
    private val thumbs: CloudThumbnailFetcher.Factory,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : OneDrivePrecacheRepository {

    override val state: Flow<PrecacheState> =
        flow {
            emitAll(WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PrecacheWork.FULL))
        }
            .map { infos -> precacheStateOf(infos.map { it.toSnapshot() }) }
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun estimate(): PrecacheEstimate = withContext(io) {
        val targets = PrecacheTargets.of(context.resources)
        var total = 0
        var missingGrid = 0
        var missingPreview = 0
        var missing = 0
        var before = Long.MAX_VALUE
        while (true) {
            val page = mediaDao.precacheCandidates(before, ESTIMATE_PAGE)
            if (page.isEmpty()) break
            before = page.last().id
            page.forEach { row ->
                currentCoroutineContext().ensureActive()
                total++
                val ref = row.ref()
                val grid = precacher.gridPresent(ref, targets.gridBucket)
                val preview = precacher.previewPresent(ref, targets.previewSide(row, thumbs.maxPreviewSide))
                if (!grid) missingGrid++
                if (!preview) missingPreview++
                if (!grid || !preview) missing++
            }
        }
        val bytes = missingGrid * ESTIMATED_GRID_BYTES + missingPreview * ESTIMATED_PREVIEW_BYTES
        val (usable, volume) = store.volumeSpace()
        PrecacheEstimate(
            total = total,
            missing = missing,
            estimatedBytes = bytes,
            freeBytes = usable,
            enoughSpace = precacheHasRoom(usable - bytes, volume),
        )
    }

    override fun start() {
        scope.launch(io) {
            try {
                PrecacheWork.enqueueFull(context, wifiOnly = settings.settings.first().offlineWifiOnly, append = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(OneDrivePrecacheWorker.TAG, "Mise en cache de OneDrive non planifiée", e)
            }
        }
    }

    override fun cancel() {
        scope.launch(io) { WorkManager.getInstance(context).cancelUniqueWork(PrecacheWork.FULL) }
    }

    private companion object {
        const val ESTIMATE_PAGE = 600
    }
}

/**
 * Vidage complet de la mise en cache de tout OneDrive : passages arrêtés puis fichiers supprimés
 * (un téléchargement en cours n'enregistre plus rien, voir [CloudPrecacheStore.writeToken]).
 * Dispatcher d'E/S.
 */
fun interface PrecacheCleaner {
    fun clearAll()
}

@Singleton
class WorkManagerPrecacheCleaner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: CloudPrecacheStore,
) : PrecacheCleaner {
    override fun clearAll() {
        PrecacheWork.cancelAll(context)
        store.clear()
    }
}

/**
 * « Garder à jour » : après chaque synchronisation OneDrive réussie (premier plan ou arrière-plan),
 * un passage est ajouté en arrière-plan pour les nouveaux éléments et nouvelles versions. Sans
 * « Garder à jour », seul un nettoyage (sans réseau) retire les fichiers devenus inutiles.
 */
@Singleton
class OneDrivePrecacheScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feature: CloudFeature,
    private val syncer: dagger.Lazy<CloudMediaSyncer>,
    private val settings: SettingsRepository,
    private val store: CloudPrecacheStore,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    /** Suivi des synchronisations pour la durée du processus (Application.onCreate). */
    fun start() {
        if (!feature.enabled || !started.compareAndSet(false, true)) return
        scope.launch(io) {
            syncer.get().state
                .filter { !it.running && it.lastError == null && it.lastSyncAt > 0 }
                .map { it.lastSyncAt }
                .distinctUntilChanged()
                .collect {
                    try {
                        afterSync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(OneDrivePrecacheWorker.TAG, "Mise à jour de la mise en cache non planifiée", e)
                    }
                }
        }
    }

    private suspend fun afterSync() {
        val current = settings.settings.first()
        when {
            current.precacheKeepUpdated -> PrecacheWork.enqueueFull(context, current.offlineWifiOnly, append = true)
            !store.isEmpty() -> PrecacheWork.enqueueCleanup(context)
        }
    }
}
