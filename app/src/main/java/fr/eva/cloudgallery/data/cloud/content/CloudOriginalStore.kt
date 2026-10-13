package fr.eva.cloudgallery.data.cloud.content

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.offline.OfflineSpace
import fr.eva.cloudgallery.data.cloud.offline.SpaceAction
import fr.eva.cloudgallery.data.cloud.offline.spaceAction
import fr.eva.cloudgallery.data.cloud.transfer.CloudFileDownloader
import fr.eva.cloudgallery.data.cloud.transfer.ORPHAN_MIN_AGE_MS
import fr.eva.cloudgallery.data.cloud.transfer.PART_SUFFIX
import fr.eva.cloudgallery.data.cloud.transfer.SweepEntry
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.cloud.transfer.filesToSweep
import fr.eva.cloudgallery.data.cloud.transfer.shouldEmitProgress
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudDownloadException
import fr.eva.cloudgallery.domain.model.DEFAULT_ORIGINALS_CACHE_BYTES
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** OneDrive item whose full file is wanted. */
data class CloudFileTarget(
    val mediaId: Long,
    val itemId: String,
    /** Content version (cTag) known to the index; null if unknown (copy never reused). */
    val cTag: String?,
    val sizeBytes: Long,
    val displayName: String,
    val mimeType: String?,
)

/**
 * OneDrive originals downloaded on demand (deep zoom, sharing, "Open with"): temporary app
 * files in `cacheDir/onedrive/originals/`, outside the Coil cache, each recorded in
 * `cached_file` (type ORIGINAL, unpinned, version = cTag) for the LRU cleanup ([trim]) under
 * the adjustable cap `AppSettings.originalsCacheMaxBytes`, at startup and after each download.
 * No user file is involved.
 *
 * - Only one download per item and version at a time, whatever the number of requesters
 *   (viewer, sharing); it runs in the app scope and stops when the last requester gives up.
 * - Space checked before the download (other apps' cache freed by the system if needed),
 *   otherwise [DownloadError.NO_SPACE].
 * - Written to a `.part` file, renamed once complete (under another name if a file with the
 *   same name already exists: it is never overwritten before the new one is saved), then
 *   recorded in a transaction that re-checks that the OneDrive data was not wiped in the
 *   meantime and that the item is still the one in the index (same id, same version).
 *   Otherwise the new file is deleted; the old one is deleted only after the new one is saved.
 * - Reading an existing original and cleanup never overlap ([accessLock]).
 * - All work on the I/O dispatcher.
 */
@Singleton
class CloudOriginalStore internal constructor(
    private val root: File,
    private val downloader: CloudFileDownloader,
    private val db: AppDatabase,
    private val cachedFiles: CachedFileDao,
    private val sources: MediaSourceDao,
    private val generation: CloudDataGeneration,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    /** Space of the originals volume; null: no check (tests). */
    private val space: OfflineSpace? = null,
    private val nonce: () -> String = { java.lang.Long.toHexString(Random.nextLong()) },
    /** Current cache cap (setting), read on every cleanup. */
    private val maxBytesSetting: suspend () -> Long = { DEFAULT_ORIGINALS_CACHE_BYTES },
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloader: CloudFileDownloader,
        db: AppDatabase,
        cachedFiles: CachedFileDao,
        sources: MediaSourceDao,
        generation: CloudDataGeneration,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
        settings: SettingsRepository,
    ) : this(
        root = File(context.cacheDir, ORIGINALS_DIR),
        downloader = downloader,
        db = db,
        cachedFiles = cachedFiles,
        sources = sources,
        generation = generation,
        scope = scope,
        io = io,
        clock = System::currentTimeMillis,
        space = StorageSpace(context, File(context.cacheDir, ORIGINALS_DIR)),
        maxBytesSetting = { settings.settings.first().originalsCacheMaxBytes },
    )

    /** Space of the volume of [dir] (StorageManager), calls made on the I/O dispatcher by the caller. */
    private class StorageSpace(private val context: Context, private val dir: File) : OfflineSpace {
        private val storage: StorageManager? get() = context.getSystemService(StorageManager::class.java)

        override suspend fun usableBytes(): Long = dir.usableSpace

        override suspend fun allocatableBytes(): Long {
            val sm = storage ?: return dir.usableSpace
            return try {
                sm.getAllocatableBytes(sm.getUuidForPath(dir))
            } catch (_: IOException) {
                dir.usableSpace
            }
        }

        override suspend fun reserve(bytes: Long) {
            if (bytes <= 0) return
            val sm = storage ?: return
            sm.allocateBytes(sm.getUuidForPath(dir), bytes)
        }
    }

    /** Download shared by its requesters ([waiters]); [progress] = bytes written. */
    private class Flight(val name: String, val deferred: Deferred<File>, val total: Long) {
        var waiters = 0
        val progress = MutableStateFlow(0L)
    }

    private val lock = Any()
    private val flights = HashMap<String, Flight>()

    /**
     * Last download started for each file name: a new download of the same file waits for the
     * previous one to end (cancelled but still closing the partial file).
     */
    private val lastJobs = HashMap<String, Deferred<File>>()

    /**
     * Reading an existing original ([existing]) and cleanup deletions ([trim]): never at the
     * same time (a file returned to the viewer or to sharing is not deleted right away).
     */
    private val accessLock = Mutex()

    /** Only one cleanup at a time; [trimPending] groups the requests made in the meantime. */
    private val trimLock = Mutex()
    private val trimPending = AtomicBoolean(false)

    /** Original already present and up to date for [target] (its access date is refreshed), otherwise null. */
    suspend fun existing(target: CloudFileTarget): File? = withContext(io) {
        val version = target.cTag ?: return@withContext null
        accessLock.withLock {
            val row = cachedFiles.get(target.mediaId, CacheKind.ORIGINAL) ?: return@withLock null
            if (row.version != version) return@withLock null
            val file = resolve(row.path) ?: return@withLock null
            if (!file.isFile || file.length() != row.sizeBytes) return@withLock null
            cachedFiles.touch(row.id, clock())
            file
        }
    }

    /**
     * Original of [target], downloaded if needed. [onProgress] receives the bytes written and the
     * total size. Throws [CloudDownloadException] (including [DownloadError.AUTH_REQUIRED] if the
     * OneDrive data was wiped during the download); cancellation by the caller only interrupts
     * the download if it has no other requester left.
     */
    suspend fun ensureOriginal(target: CloudFileTarget, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        existing(target)?.let { return it }
        val key = "${target.mediaId}:${target.itemId}:${target.cTag}"
        val flight = synchronized(lock) {
            val found = flights[key] ?: run {
                val name = cacheFileName(target.itemId, target.cTag, target.displayName, target.mimeType)
                val previous = lastJobs[name]
                val deferred = scope.async(io, start = CoroutineStart.LAZY) {
                    previous?.join()
                    download(key, name, target)
                }
                lastJobs[name] = deferred
                Flight(name, deferred, target.sizeBytes).also { flights[key] = it }
            }
            found.waiters++
            found
        }
        flight.deferred.start()
        try {
            return coroutineScope {
                val relay = launch { flight.progress.collect { onProgress(it, flight.total) } }
                try {
                    flight.deferred.await()
                } finally {
                    relay.cancel()
                }
            }
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters <= 0) {
                    if (flights[key] === flight) flights.remove(key)
                    // Nobody is waiting anymore: the download stops (the partial file stays for a
                    // resume of the same version, deleted at the next cleanup otherwise).
                    if (!flight.deferred.isCompleted) flight.deferred.cancel()
                }
            }
        }
    }

    private suspend fun download(key: String, name: String, target: CloudFileTarget): File {
        val self = kotlin.coroutines.coroutineContext.job
        try {
            val startGeneration = generation.value
            if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
                throw CloudDownloadException(DownloadError.NO_SPACE, IOException("Originals folder inaccessible"))
            }
            val part = File(root, "$name$PART_SUFFIX")
            ensureSpace((target.sizeBytes - (if (part.isFile) part.length() else 0L)).coerceAtLeast(0L))
            val flight = synchronized(lock) { flights[key]?.takeIf { it.deferred === self } }
            var lastEmitMs = 0L
            var lastBytes = 0L
            val result = downloader.download(target.itemId, part, target.cTag) { done, total ->
                val now = clock()
                if (shouldEmitProgress(lastEmitMs, now, lastBytes, done, total)) {
                    lastEmitMs = now
                    lastBytes = done
                    flight?.progress?.value = done
                }
            }
            // End of download: renaming and recording are never interrupted halfway
            // (otherwise a file without a row, or a row without a file).
            return withContext(NonCancellable) {
                // Name already taken (unknown version, or previous file not reusable): another
                // name, the old file stays intact until the new one is recorded.
                val usual = File(root, name)
                val dest = if (usual.exists()) File(root, alternateFileName(name, nonce())) else usual
                if (!part.renameTo(dest)) {
                    part.delete()
                    throw CloudDownloadException(DownloadError.NO_SPACE, IOException("Rename failed"))
                }
                val outcome = try {
                    commit(target, dest.name, result.sizeBytes, result.cTag, startGeneration)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to record the original", e)
                    CommitOutcome.Failed(DownloadError.SERVER)
                }
                when (outcome) {
                    is CommitOutcome.Committed -> {
                        // Old file deleted only now (and only if it differs).
                        outcome.replacedPath?.let { resolve(it)?.delete() }
                        requestTrim()
                        dest
                    }
                    is CommitOutcome.Failed -> {
                        dest.delete()
                        throw CloudDownloadException(outcome.error)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (flights[key]?.deferred === self) flights.remove(key)
                if (lastJobs[name] === self) lastJobs.remove(name)
            }
        }
    }

    /**
     * Space for [needed] more bytes (margin included): nothing to do, or other apps' cache freed
     * by the system (StorageManager.allocateBytes), otherwise [DownloadError.NO_SPACE].
     */
    private suspend fun ensureSpace(needed: Long) {
        val space = space ?: return
        val enough = try {
            when (spaceAction(space.usableBytes(), space.allocatableBytes(), needed, ORIGINAL_SPACE_MARGIN_BYTES)) {
                SpaceAction.ENOUGH -> true
                SpaceAction.ALLOCATE -> {
                    space.reserve(needed + ORIGINAL_SPACE_MARGIN_BYTES)
                    true
                }
                SpaceAction.NO_SPACE -> false
            }
        } catch (_: IOException) {
            false
        }
        if (!enough) throw CloudDownloadException(DownloadError.NO_SPACE)
    }

    private sealed interface CommitOutcome {
        class Committed(val replacedPath: String?) : CommitOutcome
        class Failed(val error: DownloadError) : CommitOutcome
    }

    private suspend fun commit(
        target: CloudFileTarget,
        name: String,
        sizeBytes: Long,
        cTag: String?,
        startGeneration: Long,
    ): CommitOutcome = db.withTransaction {
        // OneDrive data wiped (sign-out, account change) since the start: nothing is kept.
        if (generation.value != startGeneration) return@withTransaction CommitOutcome.Failed(DownloadError.AUTH_REQUIRED)
        val source = sources.find(SourceKind.ONEDRIVE, target.itemId)
        if (source == null || source.mediaId != target.mediaId || source.missingSince != null) {
            return@withTransaction CommitOutcome.Failed(DownloadError.NOT_FOUND)
        }
        // Content changed on OneDrive (sync ran in the meantime): this copy is already stale.
        if (source.cTag != null && cTag != null && source.cTag != cTag) {
            return@withTransaction CommitOutcome.Failed(DownloadError.CONTENT_CHANGED)
        }
        val now = clock()
        val previous = cachedFiles.replace(
            CachedFileEntity(
                mediaId = target.mediaId,
                sourceId = source.id,
                kind = CacheKind.ORIGINAL,
                path = name,
                sizeBytes = sizeBytes,
                createdAt = now,
                lastAccessAt = now,
                pinned = false,
                version = cTag,
            ),
        )
        CommitOutcome.Committed(previous?.path?.takeIf { it != name })
    }

    /**
     * Background cleanup (app scope), after a download or a cap change. A request made during a
     * cleanup triggers a single new one at the end.
     */
    fun requestTrim() {
        if (!trimPending.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                trimLock.withLock {
                    trimPending.set(false)
                    trimNow(maxBytesSetting(), MAX_AGE_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Originals cleanup failed", e)
            }
        }
    }

    /**
     * LRU cleanup ([planOriginalTrim]): rows without a file, originals unused for more than
     * [maxAgeMs], then the least recently used ones beyond [maxBytes] (the setting by default),
     * except those used less than 30 min ago; files without a row (older than one hour) and
     * abandoned partial files (older than 24 h). Downloads in progress are never touched.
     */
    suspend fun trim(maxBytes: Long? = null, maxAgeMs: Long = MAX_AGE_MS): Unit = withContext(io) {
        trimLock.withLock { trimNow(maxBytes ?: maxBytesSetting(), maxAgeMs) }
    }

    /**
     * "Clear cache" (Storage page, confirmed by the user): all originals, even recently used,
     * and files without a row older than one minute (a row removed by the sync leaves its file
     * behind), except downloads in progress and their recent partial files. Downloaded again
     * when needed.
     */
    suspend fun clearCache(): Unit = withContext(io) {
        trimLock.withLock { trimNow(maxBytes = 0L, maxAgeMs = 0L, inUseGraceMs = 0L, orphanMinAgeMs = CLEAR_ORPHAN_MIN_AGE_MS) }
    }

    private suspend fun trimNow(
        maxBytes: Long,
        maxAgeMs: Long,
        inUseGraceMs: Long = ORIGINAL_IN_USE_GRACE_MS,
        orphanMinAgeMs: Long = ORPHAN_MIN_AGE_MS,
    ) {
        val now = clock()
        val busy = synchronized(lock) { flights.values.map { it.name }.toSet() }
        val rows = cachedFiles.listByKind(CacheKind.ORIGINAL).map { row ->
            TrimRow(row.id, row.path, row.sizeBytes, row.lastAccessAt, fileExists = resolve(row.path)?.isFile == true, pinned = row.pinned)
        }
        val plan = planOriginalTrim(rows, busy, now, maxBytes, maxAgeMs, inUseGraceMs)
        // Row-by-row deletion, only if the row has not changed since it was read: an original
        // reopened (access date), replaced by a new version (other file) or being read
        // ([accessLock]) in the meantime is kept.
        val keptAnyway = HashSet<String>()
        if (plan.drop.isNotEmpty()) {
            accessLock.withLock {
                for (row in plan.drop) {
                    if (cachedFiles.deleteIfUnchanged(row.id, row.path, row.lastAccessAt) > 0) {
                        resolve(row.path)?.delete()
                    } else {
                        keptAnyway += row.path
                    }
                }
            }
        }
        val known = plan.keep.map { it.path }.toSet() + keptAnyway
        val files = root.listFiles().orEmpty().associateBy { it.name }
        val entries = files.values.map { SweepEntry(it.name, it.isFile, now - it.lastModified()) }
        filesToSweep(entries, known, busy, orphanMinAgeMs = orphanMinAgeMs).forEach { files[it]?.delete() }
    }

    /**
     * Deletes all originals (OneDrive data purge, after the rows are deleted) and stops the
     * downloads in progress.
     */
    suspend fun clearAll(): Unit = withContext(io) {
        val running = synchronized(lock) {
            (flights.values.map { it.deferred } + lastJobs.values).distinct().also { flights.clear() }
        }
        running.forEach { it.cancel() }
        // Waits (briefly) for them to close their files; their recording will fail anyway
        // (wipe counter changed before the purge).
        withTimeoutOrNull(CLEAR_JOIN_TIMEOUT_MS) { running.forEach { it.join() } }
        root.listFiles()?.forEach { it.delete() }
    }

    /** File of a `cached_file` row, only if it really is in the originals folder. */
    private fun resolve(path: String): File? {
        if (path.isEmpty() || path.contains('/') || path.contains('\\') || path == "." || path == "..") return null
        val file = File(root, path)
        return try {
            if (file.canonicalFile.parentFile == root.canonicalFile) file else null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** Originals folder under `cacheDir` (exposed to sharing through FileProvider). */
        const val ORIGINALS_DIR = "onedrive/originals"
        private const val TAG = "CloudOriginalStore"

        /** Originals unused for more than 7 days are deleted at startup. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val CLEAR_JOIN_TIMEOUT_MS = 5_000L

        /** "Clear cache": a freshly renamed original is recorded within a few milliseconds. */
        private const val CLEAR_ORPHAN_MIN_AGE_MS = 60_000L
    }
}

/**
 * OneDrive file of [media] to download in order to display or share it: visible OneDrive
 * item, without a usable local file. Null otherwise.
 */
fun cloudFileTargetOf(media: MediaDetails): CloudFileTarget? {
    if (media.hasLocal && media.uri != null) return null
    val cloud = media.cloudSource ?: return null
    return CloudFileTarget(
        mediaId = media.id,
        itemId = cloud.externalId,
        cTag = cloud.cTag,
        sizeBytes = cloud.sizeBytes.takeIf { it > 0 } ?: media.sizeBytes,
        displayName = media.displayName,
        mimeType = media.mimeType,
    )
}

/** Download error carried by [e] or its cause chain, otherwise null. */
fun downloadErrorOf(e: Throwable): DownloadError? =
    generateSequence(e) { it.cause }.take(8).filterIsInstance<CloudDownloadException>().firstOrNull()?.error
