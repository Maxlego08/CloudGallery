package fr.eva.cloudgallery.data.cloud.offline

import android.net.Network
import android.util.Log
import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.content.downloadErrorOf
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.sync.parseCloudCursorVersion
import fr.eva.cloudgallery.data.cloud.transfer.CdnResult
import fr.eva.cloudgallery.data.cloud.transfer.CloudFileDownloader
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.cloud.transfer.shouldEmitProgress
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads a OneDrive file into a partial file (replaceable in tests). */
fun interface OfflineFileFetcher {
    suspend fun fetch(
        itemId: String,
        part: File,
        expectedCTag: String?,
        network: Network?,
        expectedDriveId: String?,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): CdnResult
}

/** Disk space of the copy directory (replaceable in tests). */
interface OfflineSpace {
    /** Free space on the volume (File.getUsableSpace). */
    suspend fun usableBytes(): Long

    /** Space obtainable by freeing other apps' cache (StorageManager.getAllocatableBytes). */
    suspend fun allocatableBytes(): Long

    /** Asks the system to free [bytes] bytes (StorageManager.allocateBytes); [IOException] if impossible. */
    suspend fun reserve(bytes: Long)
}

/** End of an engine pass. */
enum class DrainOutcome {
    /**
     * Nothing left to download right now: queue empty, entirely failed or paused, or remaining
     * tasks waiting for Wi-Fi or for the index to be updated.
     */
    DONE,

    /** Transient error: the job must be relaunched later (backoff handled by the system). */
    RETRY_LATER,
}

/** Progress of a pass, for the notification and JobScheduler. */
data class OfflineProgress(
    /** Display name of the current item (never logged). */
    val displayName: String,
    val itemDone: Long,
    val itemTotal: Long,
    /** Items completed during this pass. */
    val completed: Int,
    /** Remaining items, including the current one. */
    val remaining: Int,
    /** Bytes received during this pass (all items). */
    val sessionBytes: Long,
)

/** Summary of a pass (end notification). */
data class DrainSummary(val completed: Int, val failed: Int)

/**
 * Offline copy engine: drains the `download_task` queue, one item at a time.
 *
 * - One pass at a time ([drain] waits for the previous one to end, cleanup included); at the
 *   start of a pass, any "running" task comes from a dead process and goes back to the queue.
 * - Each task is first checked against the job's network ("Wi-Fi only" setting, "Download now"
 *   batch): the others keep waiting for Wi-Fi.
 * - Each file is written to `offline/<name>.part` (resumed by Range on the same version), renamed
 *   once complete, then recorded in a transaction that rechecks: OneDrive data not cleared in the
 *   meantime, task still running (not freed up or cancelled), item still present in the index
 *   with the same version. Otherwise the file is deleted.
 * - "Free up" / "Cancel" on an item only stops that item ([withItemsStopped]): the pass carries
 *   on with the following ones.
 * - Renaming, recording and deletion are never interrupted halfway.
 * - The old copy (new version) keeps being served until the new one is recorded, and is then
 *   deleted.
 */
@Singleton
class OfflineDownloadEngine internal constructor(
    private val db: AppDatabase,
    private val fetcher: OfflineFileFetcher,
    private val store: OfflineStore,
    private val generation: CloudDataGeneration,
    private val canAccess: suspend () -> Boolean,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val space: OfflineSpace = store,
) {

    @Inject
    constructor(
        db: AppDatabase,
        downloader: CloudFileDownloader,
        store: OfflineStore,
        generation: CloudDataGeneration,
        guard: CloudAccessGuard,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        db = db,
        fetcher = OfflineFileFetcher { itemId, part, cTag, network, driveId, onProgress ->
            downloader.download(itemId, part, cTag, network, driveId, onProgress)
        },
        store = store,
        generation = generation,
        canAccess = guard::canAccess,
        io = io,
        clock = System::currentTimeMillis,
        space = store,
    )

    private val tasks = db.downloadTaskDao()
    private val sources = db.mediaSourceDao()
    private val mediaDao = db.mediaDao()
    private val cachedFiles = db.cachedFileDao()

    /** One pass at a time (P0-4). */
    private val drainMutex = Mutex()

    /**
     * The "nothing left to take, end of pass" decision is made under this lock, like adding a
     * task ([withQueue]).
     */
    private val queueLock = Mutex()

    @Volatile
    private var accepting = false

    @Volatile
    private var drainJob: Job? = null

    private val _currentName = MutableStateFlow<String?>(null)

    /** File name of the copy being downloaded (partial file not to be touched). */
    val currentName: StateFlow<String?> = _currentName.asStateFlow()

    /** Item being processed: its download, and the end of its processing (recording included). */
    private class CurrentItem(val mediaId: Long) {
        var fetch: Job? = null
        val done = CompletableDeferred<Unit>()
    }

    private val itemLock = Any()

    /** Media being freed up or cancelled (number of pending requests per media item). */
    private val stopRequests = HashMap<Long, Int>()
    private var current: CurrentItem? = null

    /** A pass is running in this process. */
    val isDraining: Boolean get() = drainMutex.isLocked

    /** Summary of the last finished pass. */
    @Volatile
    var lastSummary: DrainSummary = DrainSummary(0, 0)
        private set

    /**
     * Runs [block] under the queue lock; [draining] is true if a running pass will still pick up
     * new tasks.
     */
    internal suspend fun <T> withQueue(block: suspend (draining: Boolean) -> T): T =
        queueLock.withLock { block(accepting) }

    /**
     * Runs [block] (deletion of the tasks and copies of [mediaIds]) once the engine no longer
     * works on any of these media: a running download of one of them is stopped (file closed) and
     * its possible recording finished; while [block] runs, the engine neither starts nor records
     * any of them. The pass carries on with the others.
     */
    suspend fun <T> withItemsStopped(mediaIds: Collection<Long>, block: suspend () -> T): T {
        val ids = mediaIds.toSet()
        val running = synchronized(itemLock) {
            ids.forEach { stopRequests[it] = (stopRequests[it] ?: 0) + 1 }
            current?.takeIf { it.mediaId in ids }?.also { it.fetch?.cancel() }
        }
        try {
            running?.done?.await()
            return block()
        } finally {
            synchronized(itemLock) {
                ids.forEach { id ->
                    val left = (stopRequests[id] ?: 1) - 1
                    if (left <= 0) stopRequests.remove(id) else stopRequests[id] = left
                }
            }
        }
    }

    private fun isStopRequested(mediaId: Long): Boolean = synchronized(itemLock) { mediaId in stopRequests }

    /**
     * Drains the queue. [network]: network chosen by JobScheduler ("Wi-Fi only" constraint
     * honoured by the connection itself). [allowed]: tasks allowed on this network, re-read
     * before each task (null: all of them). Cancellable at any time (job stop, purge): the
     * current task goes back to the queue, its partial file is kept for resuming.
     */
    suspend fun drain(
        network: Network?,
        allowed: suspend () -> Set<Long>? = { null },
        onProgress: suspend (OfflineProgress) -> Unit = {},
    ): DrainOutcome =
        drainMutex.withLock {
            drainJob = currentCoroutineContext().job
            try {
                withContext(io) { runDrain(network, allowed, onProgress) }
            } finally {
                accepting = false
                _currentName.value = null
                drainJob = null
            }
        }

    /**
     * Stops the running pass and waits for it to end (files closed, task put back in the queue).
     * Used by the purge and by the notification's "Cancel".
     */
    suspend fun stopAndJoin() {
        drainJob?.cancelAndJoin()
    }

    /** Stops the running pass, then runs [block] without any pass being able to start. */
    suspend fun <T> whileStopped(block: suspend () -> T): T {
        stopAndJoin()
        return drainMutex.withLock { block() }
    }

    /**
     * Outside a pass only: "running" tasks of a dead process are put back in the queue or, if
     * that process was stopped by the user during a download, the whole queue is paused (see
     * [startupRecovery]). No effect during a pass. Returns the number of modified tasks.
     */
    suspend fun recoverIfIdle(firstStart: Boolean, userRequestedExit: Boolean): Int {
        if (!drainMutex.tryLock()) return 0
        try {
            return withContext(io) {
                when (startupRecovery(firstStart, userRequestedExit, tasks.existsRunning())) {
                    StartupRecovery.PAUSE -> tasks.pauseActive(clock())
                    StartupRecovery.REQUEUE -> tasks.resetRunningToQueued(clock())
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    private class DrainState {
        var completed = 0
        var failed = 0
        var sessionBytes = 0L
        var spaceChecked = false

        /** Tasks set aside during this pass (free-up or cancellation in progress). */
        val skipped = HashSet<Long>()
    }

    private suspend fun runDrain(
        network: Network?,
        allowed: suspend () -> Set<Long>?,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): DrainOutcome {
        val state = DrainState()
        try {
            // A single engine per process and its lock is held: any "running" task is stale.
            tasks.resetRunningToQueued(clock())
            accepting = true
            if (!canAccess()) {
                failAll(DownloadError.AUTH_REQUIRED)
                return DrainOutcome.DONE
            }
            val driveId = parseCloudCursorVersion(db.syncCursorDao().get(ONEDRIVE_PROVIDER)?.version)?.driveId
            while (true) {
                // Setting re-read for each task: switching to "Wi-Fi only" applies immediately.
                val permitted = allowed()
                val task = queueLock.withLock {
                    nextTask(permitted, state.skipped).also { if (it == null) accepting = false }
                } ?: break
                when (processTask(task, network, driveId, state, onProgress)) {
                    Step.NEXT -> Unit
                    Step.RETRY_LATER -> return DrainOutcome.RETRY_LATER
                    Step.STOP -> return DrainOutcome.DONE
                }
            }
            return DrainOutcome.DONE
        } finally {
            lastSummary = DrainSummary(state.completed, state.failed)
        }
    }

    private suspend fun nextTask(permitted: Set<Long>?, skipped: Set<Long>): DownloadTaskEntity? {
        // List never empty in the query (impossible id): simpler SQL to read.
        val skip = skipped.toList().ifEmpty { listOf(NO_ID) }
        return when {
            permitted == null -> tasks.nextRunnable(skip)
            permitted.isEmpty() -> null
            else -> tasks.nextRunnableAmong(permitted.toList(), skip)
        }
    }

    private enum class Step { NEXT, RETRY_LATER, STOP }

    /** The task disappeared during its download (freed up, cancelled): download stopped. */
    private class TaskGoneException : Exception()

    private suspend fun processTask(
        task: DownloadTaskEntity,
        network: Network?,
        driveId: String?,
        state: DrainState,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): Step {
        // Item claimed before any write: a free-up or cancellation arriving from now on stops
        // this download (and waits for it).
        val item = CurrentItem(task.mediaId)
        val stopping = synchronized(itemLock) {
            if (task.mediaId in stopRequests) {
                true
            } else {
                current = item
                false
            }
        }
        if (stopping) {
            // Free-up or cancellation in progress: the task is about to be deleted by the caller.
            state.skipped += task.id
            return Step.NEXT
        }
        try {
            return processCurrent(task, item, network, driveId, state, onProgress)
        } finally {
            synchronized(itemLock) { if (current === item) current = null }
            item.done.complete(Unit)
        }
    }

    private suspend fun processCurrent(
        task: DownloadTaskEntity,
        item: CurrentItem,
        network: Network?,
        driveId: String?,
        state: DrainState,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): Step {
        val itemId = task.cloudItemId
        val media = mediaDao.getById(task.mediaId)
        if (media == null) {
            tasks.deleteById(task.id)
            return Step.NEXT
        }
        val source = itemId?.let { sources.find(SourceKind.ONEDRIVE, it) }
        if (itemId == null || source == null || source.mediaId != task.mediaId || source.missingSince != null) {
            // Item deleted or hidden on OneDrive since it was queued.
            tasks.markFailed(task.id, DownloadError.NOT_FOUND.name, task.attempts, clock())
            state.failed++
            return Step.NEXT
        }
        if (tasks.markRunning(task.id, clock()) == 0) return Step.NEXT
        val cTag = source.cTag
        if (cTag != task.cTag || source.sizeBytes != task.bytesTotal) tasks.setVersion(task.id, cTag, source.sizeBytes)
        if (!state.spaceChecked) {
            state.spaceChecked = true
            if (!ensureSpace()) {
                tasks.markFailed(task.id, DownloadError.NO_SPACE.name, task.attempts + 1, clock())
                failAll(DownloadError.NO_SPACE)
                state.failed++
                return Step.STOP
            }
        }
        val name = cacheFileName(itemId, cTag, media.displayName, media.mimeType)
        store.ensureRoot()
        val part = store.partOf(name) ?: return failTask(task, DownloadError.SERVER, state)
        val dest = store.fileOf(name) ?: return failTask(task, DownloadError.SERVER, state)
        _currentName.value = name
        val remaining = tasks.countQueued() + 1
        val startGeneration = generation.value
        var lastEmitMs = 0L
        var lastBytes = 0L
        var itemBytes = -1L
        var progressed = false
        val result = try {
            coroutineScope {
                val fetch = async(start = CoroutineStart.LAZY) {
                    fetcher.fetch(itemId, part, cTag, network, driveId) { done, total ->
                        // Resume: the first reading starts from the partial file's size.
                        if (itemBytes < 0) itemBytes = done
                        if (done > itemBytes) progressed = true
                        state.sessionBytes += (done - itemBytes).coerceAtLeast(0)
                        itemBytes = done
                        val now = clock()
                        if (shouldEmitProgress(lastEmitMs, now, lastBytes, done, total)) {
                            lastEmitMs = now
                            lastBytes = done
                            // Task deleted in the meantime (freed up, cancelled): no point continuing.
                            if (tasks.updateProgress(task.id, done, total, now) == 0) throw TaskGoneException()
                            onProgress(OfflineProgress(media.displayName, done, total, state.completed, remaining, state.sessionBytes))
                        }
                    }
                }
                val stopNow = synchronized(itemLock) {
                    item.fetch = fetch
                    task.mediaId in stopRequests
                }
                if (stopNow) fetch.cancel()
                fetch.await()
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // This item only (freed up or cancelled): the task is deleted by the caller.
                state.skipped += task.id
                return Step.NEXT
            }
            // Job stopped: the task goes back to the queue, the partial file stays for resuming.
            withContext(NonCancellable) { tasks.requeue(task.id, null, task.attempts, clock()) }
            throw e
        } catch (_: TaskGoneException) {
            state.skipped += task.id
            return Step.NEXT
        } catch (e: Exception) {
            val error = downloadErrorOf(e) ?: if (e is IOException) DownloadError.NETWORK else DownloadError.SERVER
            if (error != DownloadError.NETWORK && error != DownloadError.THROTTLED) {
                Log.w(TAG, "Offline copy interrupted: $error")
            }
            return onFailure(task, error, state, progressed)
        } finally {
            _currentName.value = null
        }
        return withContext(NonCancellable) { finish(task, media, itemId, name, part, dest, result, startGeneration, state) }
    }

    /** End of the download: rename then record (never interrupted). */
    private suspend fun finish(
        task: DownloadTaskEntity,
        media: MediaEntity,
        itemId: String,
        name: String,
        part: File,
        dest: File,
        result: CdnResult,
        startGeneration: Long,
        state: DrainState,
    ): Step {
        if (!part.renameTo(dest)) {
            part.delete()
            return onFailure(task, DownloadError.SERVER, state, progressed = false)
        }
        val outcome = try {
            commit(task, media, itemId, name, result, startGeneration)
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the offline copy", e)
            Commit.Failed
        }
        return when (outcome) {
            is Commit.Done -> {
                outcome.replacedPath?.let { store.delete(listOf(it)) }
                state.completed++
                Step.NEXT
            }
            Commit.ContentChanged -> {
                dest.delete()
                onFailure(task, DownloadError.CONTENT_CHANGED, state, progressed = false)
            }
            Commit.Discarded -> {
                // Data cleared, task freed up or cancelled, item gone: nothing is kept.
                dest.delete()
                Step.NEXT
            }
            Commit.Failed -> {
                dest.delete()
                onFailure(task, DownloadError.SERVER, state, progressed = false)
            }
        }
    }

    private sealed interface Commit {
        class Done(val replacedPath: String?) : Commit
        data object ContentChanged : Commit
        data object Discarded : Commit
        data object Failed : Commit
    }

    private suspend fun commit(
        task: DownloadTaskEntity,
        media: MediaEntity,
        itemId: String,
        name: String,
        result: CdnResult,
        startGeneration: Long,
    ): Commit = db.withTransaction {
        if (generation.value != startGeneration) return@withTransaction Commit.Discarded
        // Free-up or cancellation of this item in progress: the copy is not recorded.
        if (isStopRequested(media.id)) return@withTransaction Commit.Discarded
        val current = tasks.getById(task.id)
        if (current == null || current.state != DownloadState.RUNNING) return@withTransaction Commit.Discarded
        if (mediaDao.getById(media.id) == null) return@withTransaction Commit.Discarded
        val source = sources.find(SourceKind.ONEDRIVE, itemId)
        if (source == null || source.mediaId != media.id || source.missingSince != null) {
            tasks.markFailed(task.id, DownloadError.NOT_FOUND.name, task.attempts, clock())
            return@withTransaction Commit.Discarded
        }
        if (!contentMatches(source.cTag, source.sizeBytes, source.eTag, result.cTag, result.sizeBytes, result.eTag)) {
            return@withTransaction Commit.ContentChanged
        }
        val version = result.cTag ?: source.cTag
        val offline = sources.find(SourceKind.OFFLINE_COPY, itemId)
        val fields = MediaSourceEntity(
            mediaId = media.id,
            kind = SourceKind.OFFLINE_COPY,
            // OneDrive id: unique per kind and stable (the file path is in cached_file).
            externalId = itemId,
            uri = null,
            // Never a folder album: membership comes from the OneDrive source.
            bucketKey = null,
            bucketName = null,
            relativePath = null,
            sizeBytes = result.sizeBytes,
            dateModified = source.dateModified,
            eTag = result.eTag ?: source.eTag,
            cTag = version,
            missingSince = null,
        )
        val offlineId = when {
            offline == null -> sources.insert(fields)
            offline.mediaId != media.id -> {
                sources.deleteByIds(listOf(offline.id))
                sources.insert(fields)
            }
            else -> {
                sources.update(fields.copy(id = offline.id))
                offline.id
            }
        }
        val now = clock()
        val previous = cachedFiles.replace(
            CachedFileEntity(
                mediaId = media.id,
                sourceId = offlineId,
                kind = CacheKind.OFFLINE,
                path = name,
                sizeBytes = result.sizeBytes,
                createdAt = now,
                lastAccessAt = now,
                pinned = true,
                version = version,
            ),
        )
        mediaDao.refreshSourceFlags(listOf(media.id))
        tasks.deleteById(task.id)
        Commit.Done(previous?.path?.takeIf { it != name })
    }

    private suspend fun onFailure(task: DownloadTaskEntity, error: DownloadError, state: DrainState, progressed: Boolean): Step {
        val attempts = task.attempts + if (countsAsAttempt(error, progressed)) 1 else 0
        val now = clock()
        return when (failureAction(error, attempts)) {
            FailureAction.RETRY_LATER -> {
                tasks.requeue(task.id, error.name, attempts, now)
                Step.RETRY_LATER
            }
            FailureAction.WAIT_FOR_INDEX -> {
                // Left pending (without counting an attempt) until the next sync; the downloadable
                // tasks query leaves it out until then.
                tasks.requeue(task.id, error.name, attempts, now)
                state.skipped += task.id
                Step.NEXT
            }
            FailureAction.FAIL -> {
                tasks.markFailed(task.id, error.name, attempts, now)
                state.failed++
                Step.NEXT
            }
            FailureAction.FAIL_ALL -> {
                tasks.markFailed(task.id, error.name, attempts, now)
                failAll(error)
                state.failed++
                Step.STOP
            }
        }
    }

    private suspend fun failTask(task: DownloadTaskEntity, error: DownloadError, state: DrainState): Step {
        tasks.markFailed(task.id, error.name, task.attempts + 1, clock())
        state.failed++
        return Step.NEXT
    }

    private suspend fun failAll(error: DownloadError) {
        queueLock.withLock {
            tasks.failAllQueued(error.name, clock())
            accepting = false
        }
    }

    /**
     * Space for the whole queue (a single request to the system per pass): the free space is
     * enough, or the system frees other apps' cache (allocateBytes: an ordinary write would not),
     * or there is not enough space even then.
     */
    private suspend fun ensureSpace(): Boolean {
        val needed = tasks.remainingBytes()
        return try {
            when (spaceAction(space.usableBytes(), space.allocatableBytes(), needed, OFFLINE_SPACE_MARGIN_BYTES)) {
                SpaceAction.ENOUGH -> true
                SpaceAction.ALLOCATE -> {
                    space.reserve(needed + OFFLINE_SPACE_MARGIN_BYTES)
                    true
                }
                SpaceAction.NO_SPACE -> false
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val TAG = "OfflineEngine"
        const val NO_ID = -1L
    }
}
