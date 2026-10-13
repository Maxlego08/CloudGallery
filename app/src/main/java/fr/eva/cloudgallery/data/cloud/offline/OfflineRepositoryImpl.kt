package fr.eva.cloudgallery.data.cloud.offline

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Network
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.data.net.NetworkState
import fr.eva.cloudgallery.data.net.NetworkStatus
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.DeletedOnOneDriveCopy
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineQueueState
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.OfflinePurgeImpact
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline copies: download queue ([OfflineDownloadEngine] + [OfflineJobScheduler]), freeing up,
 * and the decision on copies of items deleted from OneDrive (D1). All database and file work goes
 * through the I/O dispatcher.
 *
 * Network (decision D2): with "Wi-Fi only", a task is only downloaded on an unmetered network,
 * unless it was in the queue at the time of a "Download now" (an exemption specific to those
 * tasks: tasks added afterwards, including new versions found by sync, follow the setting
 * again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OfflineRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val engine: OfflineDownloadEngine,
    private val scheduler: OfflineJobScheduler,
    private val jobs: OfflineJobTracker,
    private val store: OfflineStore,
    private val guard: CloudAccessGuard,
    private val network: NetworkStatus,
    private val settings: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : OfflineRepository {

    private val tasks = db.downloadTaskDao()
    private val sources = db.mediaSourceDao()
    private val mediaDao = db.mediaDao()
    private val cachedFiles = db.cachedFileDao()

    /** Tasks of a "Download now" batch (forgotten when the process restarts). */
    private val overrides = MutableStateFlow<Set<OverrideKey>>(emptySet())

    private val startupDone = AtomicBoolean(false)

    private val wifiOnly: Flow<Boolean> = settings.settings.map { it.offlineWifiOnly }.distinctUntilChanged()

    /** Network rule in force and current network, to display the wait for Wi-Fi. */
    private class Policy(val wifiOnly: Boolean, val overrides: Set<OverrideKey>, val net: NetworkState) {
        fun waits(task: DownloadTaskEntity): Boolean =
            task.state == DownloadState.QUEUED &&
                waitsForUnmetered(wifiOnly, OverrideKey(task.id, task.createdAt) in overrides, net.connected, net.unmetered)
    }

    private val policy: Flow<Policy> = combine(wifiOnly, overrides, network.state) { onlyWifi, keys, net ->
        Policy(onlyWifi, keys, net)
    }

    override val queue: Flow<OfflineQueueState> = combine(
        tasks.observeOpen(),
        cachedFiles.observeCount(CacheKind.OFFLINE),
        cachedFiles.observeTotalSize(CacheKind.OFFLINE),
        policy,
    ) { open, count, bytes, rules ->
        val queued = open.count { it.state == DownloadState.QUEUED }
        val running = open.count { it.state == DownloadState.RUNNING }
        OfflineQueueState(
            queued = queued,
            running = running,
            paused = open.count { it.state == DownloadState.PAUSED },
            failed = open.count { it.state == DownloadState.FAILED },
            remainingBytes = open.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
                .sumOf { (it.bytesTotal - it.bytesDownloaded).coerceAtLeast(0) },
            waitingForUnmetered = running == 0 && open.any(rules::waits),
            availableCount = count,
            availableBytes = bytes,
        )
    }.distinctUntilChanged().flowOn(io)

    override fun observeItem(mediaId: Long): Flow<OfflineItemState> = combine(
        tasks.observeOpenForMedia(mediaId),
        mediaDao.observeById(mediaId).map { it?.hasOffline == true }.distinctUntilChanged(),
        policy,
    ) { task, hasOffline, rules ->
        offlineStatus(
            taskState = task?.state?.let(::taskStateOf),
            taskError = downloadErrorOrNull(task?.error),
            bytesDone = task?.bytesDownloaded ?: 0L,
            bytesTotal = task?.bytesTotal ?: 0L,
            hasOffline = hasOffline,
            waitingForUnmetered = task != null && rules.waits(task),
        )
    }.distinctUntilChanged().flowOn(io)

    // --- Keep offline ---------------------------------------------------------------------------

    override suspend fun keepOffline(mediaIds: Collection<Long>, confirmedLargeBatch: Boolean): KeepOfflineResult = withContext(io) {
        if (mediaIds.isEmpty()) return@withContext KeepOfflineResult.NothingToDo
        if (!guard.canAccess()) return@withContext KeepOfflineResult.NotSignedIn
        val ids = mediaIds.distinct()
        // Items without a copy, and items whose update (new version) is stopped.
        val candidates = ids.chunked(CHUNK).flatMap { sources.keepOfflineCandidates(it) + sources.stoppedUpdateCandidates(it) }
            .distinctBy { it.mediaId }
        val open = ids.chunked(CHUNK).flatMap { tasks.openForMedia(it) }.associateBy { it.mediaId }
        // Already in the queue and pending or running, same version: nothing to redo.
        val todo = candidates.filter { c ->
            val task = open[c.mediaId]
            task == null || task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED ||
                task.cTag != c.cTag || task.cloudItemId != c.itemId
        }
        if (todo.isEmpty()) return@withContext KeepOfflineResult.NothingToDo
        val bytes = estimateBytes(todo.map { it.sizeBytes })
        val alreadyQueued = tasks.remainingBytes()
        val missing = missingSpace(store.allocatableBytes(), bytes + alreadyQueued)
        if (missing > 0) return@withContext KeepOfflineResult.NoSpace(missing, store.storageUuid())
        if (!confirmedLargeBatch && isLargeBatch(todo.size, bytes)) {
            return@withContext KeepOfflineResult.NeedsConfirmation(todo.size, bytes, store.allocatableBytes())
        }
        val now = System.currentTimeMillis()
        val entities = todo.map { c ->
            DownloadTaskEntity(
                mediaId = c.mediaId,
                sourceId = null,
                state = DownloadState.QUEUED,
                bytesDownloaded = 0,
                bytesTotal = c.sizeBytes.coerceAtLeast(0),
                keepOffline = true,
                createdAt = now,
                updatedAt = now,
                cloudItemId = c.itemId,
                cTag = c.cTag,
            )
        }
        engine.withQueue { tasks.enqueueIfAbsent(entities) }
        if (ensureScheduled() != ScheduleResult.SCHEDULED) return@withContext KeepOfflineResult.SchedulingRefused(todo.size)
        // Network state read directly (the shared flow starts with an unknown state). A new
        // batch never benefits from an earlier "Download now".
        val net = network.current()
        val waiting = waitsForUnmetered(wifiOnly.first(), overridden = false, connected = net.connected, unmetered = net.unmetered)
        KeepOfflineResult.Enqueued(todo.size, bytes, waiting)
    }

    // --- Scheduling ------------------------------------------------------------------------------

    /** "Download now" tasks still open (reassigned ids ruled out). */
    private suspend fun overriddenTaskIds(): Set<Long> {
        val keys = overrides.value
        if (keys.isEmpty()) return emptySet()
        val live = keys.map { it.taskId }.chunked(CHUNK).flatMap { tasks.getByIds(it) }
            .filter { (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) && OverrideKey(it.id, it.createdAt) in keys }
        val ids = live.mapTo(HashSet()) { it.id }
        // Finished, deleted or replaced tasks: forgotten.
        if (ids.size != keys.size) overrides.update { current -> current.filterTo(HashSet()) { it.taskId in ids } }
        return ids
    }

    /**
     * Network required by the pending tasks: any network as long as a "Download now" task is
     * pending or running (its download is never interrupted to wait for Wi-Fi), otherwise
     * according to the setting.
     */
    private suspend fun requiredNetwork(): JobNetwork {
        if (!wifiOnly.first()) return JobNetwork.ANY
        return jobNetworkFor(wifiOnly = true, overrideNow = overriddenTaskIds().isNotEmpty())
    }

    /**
     * Tasks a job pass may download on [jobNetwork] (null: all of them), re-read before each
     * task: the "Wi-Fi only" setting applies immediately.
     */
    internal suspend fun allowedTaskIds(jobNetwork: Network?): Set<Long>? = withContext(io) {
        val onlyWifi = wifiOnly.first()
        if (!onlyWifi) return@withContext null
        val unmetered = if (jobNetwork != null) network.isUnmetered(jobNetwork) else network.current().unmetered
        allowedOnNetwork(onlyWifi, unmetered, if (unmetered) emptySet() else overriddenTaskIds())
    }

    /** Is there still a task the current job ([jobNetwork]) can download? */
    private suspend fun hasAllowedWork(jobNetwork: Network?): Boolean {
        val allowed = allowedTaskIds(jobNetwork)
        return when {
            allowed == null -> tasks.existsRunnable()
            allowed.isEmpty() -> false
            else -> allowed.toList().chunked(CHUNK).any { tasks.existsRunnableAmong(it) }
        }
    }

    /**
     * Schedules the job if the pending tasks are not already handled by the running job (which
     * rechecks the queue before finishing) or by a job scheduled with the right network.
     * Rescheduling replaces the existing job (which stops, partial file kept).
     */
    private suspend fun ensureScheduled(): ScheduleResult = jobs.lock.withLock {
        val required = requiredNetwork()
        val active = jobs.active
        val reliable = jobs.pendingReliable
        val pending = if (active == null && reliable) scheduler.pendingNetwork() else null
        if (scheduleDecision(required, active, reliable, pending) == ScheduleDecision.KEEP) return@withLock ScheduleResult.SCHEDULED
        val result = scheduler.schedule(tasks.remainingBytes(), required)
        if (result == ScheduleResult.SCHEDULED) {
            jobs.onScheduled()
        } else {
            Log.i(TAG, "Offline copy job not scheduled for now")
        }
        result
    }

    /** Job start: returns its sequence number (see [OfflineJobTracker]). */
    internal suspend fun jobStarted(): Long = withContext(io) {
        // The running job is still returned by JobScheduler: its network constraint.
        val constraint = scheduler.pendingNetwork() ?: JobNetwork.ANY
        jobs.lock.withLock { jobs.onJobStarted(constraint) }
    }

    /**
     * Pass finished: true if there is still downloadable work on this network (the job runs
     * another pass), otherwise the job is marked finished, atomically with respect to
     * [ensureScheduled]: a task added right after is handed to a new job.
     */
    internal suspend fun continueOrEndJob(seq: Long, jobNetwork: Network?): Boolean = withContext(io) {
        jobs.lock.withLock {
            if (hasAllowedWork(jobNetwork)) {
                true
            } else {
                jobs.onJobEnded(seq, rescheduled = false)
                false
            }
        }
    }

    /** End of job [seq] ([rescheduled]: it stays scheduled). */
    internal suspend fun jobEnded(seq: Long, rescheduled: Boolean): Unit = withContext(io) {
        jobs.lock.withLock { jobs.onJobEnded(seq, rescheduled) }
    }

    /** After a job ends normally: tasks still waiting (Wi-Fi) are handed to a new job. */
    internal suspend fun relaunchIfQueued(): Unit = withContext(io) {
        try {
            if (tasks.existsRunnable() && guard.canAccess()) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not relaunch offline copies", e)
        }
    }

    // --- Free up / cancel ------------------------------------------------------------------------

    override suspend fun lastCopiesAmong(mediaIds: Collection<Long>): Int = withContext(io) {
        // Same rule as release(): visible and hidden copies.
        mediaIds.distinct().chunked(CHUNK).sumOf { sources.countOfflineLastCopiesAmong(it) }
    }

    override suspend fun release(mediaIds: Collection<Long>): Int = withContext(io) {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        // Running download of one of them stopped (file closed); the others continue.
        engine.withItemsStopped(ids) {
            val names = ArrayList<String>()
            val parts = ArrayList<String>()
            var released = 0
            db.withTransaction {
                for (chunk in ids.chunked(CHUNK)) {
                    parts += partNames(tasks.openForMedia(chunk))
                    val copies = sources.findForMedia(SourceKind.OFFLINE_COPY, chunk)
                    released += copies.map { it.mediaId }.distinct().size
                    names += cachedFiles.getMany(chunk, CacheKind.OFFLINE).map { it.path }
                    tasks.deleteForMedia(chunk)
                    cachedFiles.deleteForMedia(chunk, CacheKind.OFFLINE)
                    sources.deleteForMedia(SourceKind.OFFLINE_COPY, chunk)
                    mediaDao.refreshSourceFlags(chunk)
                }
                // Copy kept on its own (item deleted from OneDrive): the item disappears with it.
                mediaDao.deleteOrphans()
                db.albumDao().clearDanglingCovers()
            }
            store.delete(names)
            store.deleteParts(parts)
            released
        }
    }

    override suspend fun cancel(mediaIds: Collection<Long>): Unit = withContext(io) {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return@withContext
        engine.withItemsStopped(ids) {
            val parts = ArrayList<String>()
            engine.withQueue {
                for (chunk in ids.chunked(CHUNK)) {
                    parts += partNames(tasks.openForMedia(chunk))
                    tasks.deleteForMedia(chunk)
                }
            }
            store.deleteParts(parts)
        }
    }

    override suspend fun cancelAll(): Unit = withContext(io) {
        scheduler.cancel()
        overrides.value = emptySet()
        val parts = engine.whileStopped {
            val open = tasks.listOpen()
            tasks.deleteOpen()
            partNames(open)
        }
        store.deleteParts(parts)
    }

    /** Names of the tasks' partial files (same name as the final copy, plus ".part"). */
    private suspend fun partNames(open: List<DownloadTaskEntity>): List<String> {
        if (open.isEmpty()) return emptyList()
        val medias = open.map { it.mediaId }.distinct().chunked(CHUNK).flatMap { mediaDao.getByIds(it) }.associateBy { it.id }
        return open.mapNotNull { task ->
            val media = medias[task.mediaId] ?: return@mapNotNull null
            val itemId = task.cloudItemId ?: return@mapNotNull null
            cacheFileName(itemId, task.cTag, media.displayName, media.mimeType)
        }
    }

    // --- Resume ---------------------------------------------------------------------------------

    override suspend fun resume(): Unit = withContext(io) {
        tasks.resumeStopped(System.currentTimeMillis())
        if (guard.canAccess() && tasks.existsRunnable()) ensureScheduled()
    }

    override suspend fun downloadNow(): Unit = withContext(io) {
        // Exemption limited to the tasks present now (pending or running).
        val batch = tasks.listPending().map { OverrideKey(it.id, it.createdAt) }
        overrides.update { it + batch }
        if (guard.canAccess() && tasks.existsRunnable()) ensureScheduled()
    }

    override suspend fun onNetworkPolicyChanged(): Unit = withContext(io) {
        // New setting: no more exemption; the running or scheduled job is replaced if it no longer
        // requires the right network (an "any network" pass that must wait for Wi-Fi stops).
        overrides.value = emptySet()
        try {
            if (guard.canAccess() && (tasks.existsRunnable() || jobs.active != null)) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not reschedule offline copies", e)
        }
    }

    override suspend fun resumeIfNeeded(): Unit = withContext(io) {
        if (!guard.configured) return@withContext
        try {
            val firstTime = startupDone.compareAndSet(false, true)
            // Process stopped by the user during a download (force stop, Task Manager): the
            // queue stays paused until resumed. A simple swipe from recent apps, with no download
            // in progress, changes nothing.
            engine.recoverIfIdle(firstStart = firstTime, userRequestedExit = firstTime && lastExitRequestedByUser())
            if (firstTime) cleanUp()
            if (tasks.existsRunnable() && guard.canAccess()) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resume offline copies", e)
        }
    }

    /** Stopped from the Task Manager during a pass: queue paused (decision D3). */
    internal suspend fun pauseAfterUserStop(): Unit = withContext(io) {
        tasks.pauseActive(System.currentTimeMillis())
        overrides.value = emptySet()
    }

    /**
     * Stops the engine and removes the scheduled job, then runs [block] (sign-out, purge) without
     * any download being able to start in the meantime.
     */
    internal suspend fun <T> whileStoppedForPurge(block: suspend () -> T): T {
        scheduler.cancel()
        overrides.value = emptySet()
        return engine.whileStopped(block)
    }

    private fun lastExitRequestedByUser(): Boolean = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            ?.firstOrNull()?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
    } catch (_: RuntimeException) {
        false
    }

    /** Cleanup the first time the app comes to the foreground: finished rows and files without a row. */
    private suspend fun cleanUp() {
        val now = System.currentTimeMillis()
        tasks.deleteFinishedBefore(now - FINISHED_RETENTION_MS)
        val known = cachedFiles.listByKind(CacheKind.OFFLINE).map { it.path }.toSet()
        val busy = partNames(tasks.listOpen()).toSet() + listOfNotNull(engine.currentName.value)
        val removed = store.sweepOrphans(known, busy)
        if (removed > 0) Log.i(TAG, "Orphan offline files deleted: $removed")
    }

    // --- Items deleted from OneDrive (D1) ---------------------------------------------------------

    override val deletedOnOneDrive: Flow<List<DeletedOnOneDriveCopy>> =
        // Deadline computed on each subscription (an item may reach the 30 days in the meantime).
        flow { emitAll(sources.observeAwaitingDecisionMediaIds(System.currentTimeMillis() - MISSING_GRACE_MS)) }
            .distinctUntilChanged()
            .mapLatest { ids ->
                ids.chunked(CHUNK).flatMap { mediaDao.getByIds(it) }
                    .sortedByDescending { it.dateTaken }
                    .map { DeletedOnOneDriveCopy(it.id, it.displayName, it.mediaType, it.mimeType, it.sizeBytes) }
            }
            .flowOn(io)

    override suspend fun deleteCopiesOfDeletedItems(mediaIds: Collection<Long>): Int = withContext(io) {
        val requested = mediaIds.distinct()
        if (requested.isEmpty()) return@withContext 0
        val names = ArrayList<String>()
        var deleted = 0
        db.withTransaction {
            // Only the items confirmed by the user, and still awaiting a decision in this
            // transaction (an item that came back on OneDrive is not touched).
            val expiredBefore = System.currentTimeMillis() - MISSING_GRACE_MS
            for (chunk in requested.chunked(CHUNK)) {
                val ids = sources.awaitingDecisionAmong(chunk, expiredBefore)
                if (ids.isEmpty()) continue
                deleted += ids.size
                names += cachedFiles.getMany(ids, CacheKind.OFFLINE).map { it.path }
                tasks.deleteForMedia(ids)
                cachedFiles.deleteForMedia(ids, CacheKind.OFFLINE)
                sources.deleteForMedia(SourceKind.OFFLINE_COPY, ids)
                // OneDrive item hidden for more than 30 days: removed as the purge would have done
                // (never a visible OneDrive source).
                sources.deleteHiddenForMedia(SourceKind.ONEDRIVE, ids)
                mediaDao.refreshSourceFlags(ids)
            }
            mediaDao.deleteOrphans()
            db.albumDao().clearDanglingCovers()
        }
        store.delete(names)
        deleted
    }

    override suspend fun keepCopiesOfDeletedItems(mediaIds: Collection<Long>): Int = withContext(io) {
        val requested = mediaIds.distinct()
        if (requested.isEmpty()) return@withContext 0
        var kept = 0
        db.withTransaction {
            val expiredBefore = System.currentTimeMillis() - MISSING_GRACE_MS
            for (chunk in requested.chunked(CHUNK)) {
                val ids = sources.awaitingDecisionAmong(chunk, expiredBefore)
                if (ids.isEmpty()) continue
                kept += ids.size
                // No more hidden OneDrive source: the copy becomes visible again, on its own ("deleted from OneDrive").
                sources.deleteHiddenForMedia(SourceKind.ONEDRIVE, ids)
                sources.syncOfflineCopyVisibility(ids)
                mediaDao.refreshSourceFlags(ids)
            }
        }
        kept
    }

    override suspend fun purgeImpact(): OfflinePurgeImpact = withContext(io) {
        OfflinePurgeImpact(
            copies = sources.countAll(SourceKind.OFFLINE_COPY),
            lastCopies = sources.countOfflineLastCopies(),
        )
    }

    private companion object {
        const val TAG = "OfflineRepository"
        const val CHUNK = 500
        const val FINISHED_RETENTION_MS = 24L * 60 * 60 * 1000

        fun taskStateOf(state: DownloadState): TaskState? = when (state) {
            DownloadState.QUEUED -> TaskState.QUEUED
            DownloadState.RUNNING -> TaskState.RUNNING
            DownloadState.PAUSED -> TaskState.PAUSED
            DownloadState.FAILED -> TaskState.FAILED
            DownloadState.DONE, DownloadState.CANCELLED -> null
        }
    }
}
