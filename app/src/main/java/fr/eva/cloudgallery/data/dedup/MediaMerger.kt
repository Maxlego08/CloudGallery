package fr.eva.cloudgallery.data.dedup

import android.util.Log
import androidx.room.withTransaction
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local / OneDrive deduplication in the index (no file is ever touched).
 *
 * - Merge: a OneDrive item and a phone file with the same QuickXorHash and the same size
 *   ([planMerges]) become a single media item, the local file's (its metadata, its
 *   EXIF/MediaStore date, its file for display). The OneDrive media is deleted after its
 *   sources (OneDrive, offline copy), favorite, albums, album cover, cached files, downloads and
 *   playback position have been transferred.
 * - Unmerge ([shouldSplit]): the OneDrive source (with its offline copy, its temporary original
 *   and its downloads) moves to a new media item; favorites and albums stay on the local file's
 *   media, which is the one the user was seeing.
 *
 * Called after a MediaStore sync, after a OneDrive pass and after a batch of hashes; one pass at
 * a time, in batches of short transactions (each pair is rechecked within its transaction).
 */
@Singleton
class MediaMerger @Inject constructor(
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dedupDao = db.dedupDao()
    private val mediaDao = db.mediaDao()
    private val sourceDao = db.mediaSourceDao()
    private val mutex = Mutex()

    /** Unmerges, then merges. Returns the number of merges performed (tests, log). */
    suspend fun reconcile(): Int = withContext(io) {
        mutex.withLock {
            val splits = dedupDao.mergedMedia().filter(::shouldSplit)
            for (chunk in splits.chunked(CHUNK)) db.withTransaction { chunk.forEach { split(it) } }
            if (splits.isNotEmpty()) Log.i(TAG, "${splits.size} merge(s) undone: different hashes")

            val plans = planMerges(dedupDao.mergeCandidates())
            var merged = 0
            for (chunk in plans.chunked(CHUNK)) {
                db.withTransaction {
                    for (plan in chunk) {
                        if (!dedupDao.mergeStillValid(plan.localMediaId, plan.cloudMediaId, plan.cloudSourceId)) continue
                        merge(plan.cloudMediaId, plan.localMediaId)
                        merged++
                    }
                }
            }
            if (merged > 0) Log.i(TAG, "$merged OneDrive item(s) attached to their local file")
            merged
        }
    }

    private suspend fun merge(from: Long, to: Long) {
        dedupDao.moveCloudSources(from, to)
        dedupDao.copyFavorite(from, to)
        dedupDao.copyAlbumMemberships(from, to)
        dedupDao.moveAlbumCovers(from, to)
        dedupDao.moveCachedFiles(from, to)
        dedupDao.moveDownloadTasks(from, to)
        dedupDao.copyPlaybackPosition(from, to)
        dedupDao.deleteSearchEntry(from)
        // Any leftovers (a cache row of the same kind already present) are deleted by cascade.
        dedupDao.deleteMedia(from)
        refresh(listOf(to))
    }

    private suspend fun split(row: MergedRow) {
        val media = mediaDao.getById(row.mediaId) ?: return
        val cloud = sourceDao.getById(row.cloudSourceId) ?: return
        val newId = mediaDao.insert(cloudOnlyCopyOf(media, cloud.sizeBytes, cloud.dateModified, row.cloudQuickXorHash))
        dedupDao.detachCloudSource(row.mediaId, newId, row.cloudSourceId, row.cloudItemId)
        dedupDao.detachCloudFiles(row.mediaId, newId)
        dedupDao.detachDownloadTasks(row.mediaId, newId, row.cloudItemId)
        refresh(listOf(row.mediaId, newId))
    }

    private suspend fun refresh(ids: List<Long>) {
        sourceDao.syncOfflineCopyVisibility(ids)
        mediaDao.refreshSourceFlags(ids)
    }

    private companion object {
        const val TAG = "MediaMerger"
        const val CHUNK = 200
    }
}

/**
 * OneDrive media recreated on unmerge: metadata of the merged media (the next change of the item
 * on OneDrive replaces them with Graph's), size, modification date and hash of the OneDrive item.
 * Graph thumbnails are already oriented: orientation 0. The source flags are recomputed
 * afterwards.
 */
internal fun cloudOnlyCopyOf(media: MediaEntity, sizeBytes: Long, dateModified: Long, quickXorHash: String?) = media.copy(
    id = 0,
    sizeBytes = sizeBytes,
    dateModified = dateModified,
    orientation = 0,
    primaryUri = null,
    hasLocal = false,
    hasCloud = true,
    hasOffline = false,
    contentHash = contentHashOf(quickXorHash),
    cloudItemId = null,
    cloudCTag = null,
)
