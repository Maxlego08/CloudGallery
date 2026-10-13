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
 * Sizes downloaded for this device: thumbnail tier of the Photos grid at the default zoom, and
 * the screen's long side for the viewer preview (same computation as the viewer:
 * `screenWidthDp` / `screenHeightDp` × density).
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

/** Thumbnail reference of a row (same version as the display path: cTag, otherwise modification date). */
private fun PrecacheRow.ref() = cloudThumbRef(cloudItemId, cloudCTag, dateModified)!!

/**
 * Pass that caches all of OneDrive: for each item whose thumbnail comes from OneDrive (most
 * recent first), grid thumbnail then viewer preview, skipping what is already saved (resume
 * without re-downloading). Foreground service (dataSync type, notification with "Cancel") when
 * launched while the app is visible; otherwise an ordinary work, interrupted and resumed by
 * WorkManager.
 *
 * Stops cleanly below the minimum free space ([precacheHasRoom]), on sign-out, after too many
 * consecutive network failures (resumed later) or on Graph throttling. A complete pass then
 * removes the files of items that disappeared or changed. In [KEY_CLEANUP_ONLY] mode, only this
 * cleanup is done (no network request).
 *
 * Logs contain no URL, token, item id or account name.
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
            // "Clear cache" during the pass: nothing to resume, no summary.
            Result.success()
        } catch (e: CloudAuthRequiredException) {
            Result.success(outcomeData(PrecacheOutcome.SIGNED_OUT, Counters()))
        } catch (e: Exception) {
            Log.w(TAG, "OneDrive caching interrupted, will resume later", e)
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
                Log.i(TAG, "Caching stopped: not enough free space")
                return Result.success(outcomeData(PrecacheOutcome.LOW_SPACE, counters))
            }
            page.forEach { keep += precacheHash(it.ref()) }
            coroutineScope {
                page.map { row ->
                    async { permits.withPermit { precacheRow(row, targets, token, counters, consecutiveFailures) } }
                }.awaitAll()
            }
            if (precacheShouldPause(consecutiveFailures.get())) {
                Log.w(TAG, "Caching paused: too many consecutive failures")
                return Result.retry()
            }
            report(counters)
        }
        val removed = store.retainOnly(keep)
        Log.i(TAG, "Caching finished: ${counters.done.get()} item(s), ${counters.failed.get()} without thumbnail or failed, $removed obsolete file(s) removed")
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
            // Pause requested by Graph: the pass will resume later (WorkManager).
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

    /** Cleanup only: files of items that disappeared, changed or are now present on the phone. */
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
        if (removed > 0) Log.i(TAG, "Caching: $removed obsolete file(s) removed")
    }

    private suspend fun report(counters: Counters) {
        setProgress(counters.toData())
        if (foreground) {
            try {
                setForeground(foregroundInfo(counters))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Notification not updated: no effect on the pass.
                Log.w(TAG, "Caching notification not updated", e)
            }
        }
    }

    /**
     * Foreground service (dataSync): only possible while the app is visible (Android 12+). When
     * launched in the background ("Keep up to date", resume), the pass continues as an ordinary work.
     */
    private suspend fun enterForeground(counters: Counters) {
        try {
            PrecacheNotifications.ensureChannel(applicationContext)
            setForeground(foregroundInfo(counters))
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Caching without a foreground service (launched in the background)")
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

    /** Progress of the pass (shared between the parallel downloads). */
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

/** WorkManager works for caching (complete pass, cleanup only). */
internal object PrecacheWork {
    const val FULL = "onedrive_precache"
    const val CLEANUP = "onedrive_precache_cleanup"

    /**
     * Complete pass. [append]: added after the running pass ("Keep up to date" after a sync)
     * instead of being ignored if there already is one. Unmetered network if the downloads'
     * Wi-Fi setting ([wifiOnly]) requires it; battery and storage not low.
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

    /** Cleanup only (no network), merged with an already scheduled cleanup. */
    fun enqueueCleanup(context: Context) {
        val request = OneTimeWorkRequestBuilder<OneDrivePrecacheWorker>()
            .setInputData(workDataOf(OneDrivePrecacheWorker.KEY_CLEANUP_ONLY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CLEANUP, ExistingWorkPolicy.KEEP, request)
    }

    /** Stops everything ("Clear cache", OneDrive data removed). */
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
                Log.w(OneDrivePrecacheWorker.TAG, "OneDrive caching not scheduled", e)
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
 * Complete clearing of the all-of-OneDrive cache: passes stopped, then files deleted (a running
 * download no longer saves anything, see [CloudPrecacheStore.writeToken]).
 * I/O dispatcher.
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
 * "Keep up to date": after each successful OneDrive sync (foreground or background), a pass is
 * added in the background for new items and new versions. Without "Keep up to date", only a
 * cleanup (no network) removes files that are no longer needed.
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

    /** Tracks syncs for the lifetime of the process (Application.onCreate). */
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
                        Log.w(OneDrivePrecacheWorker.TAG, "Cache update not scheduled", e)
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
