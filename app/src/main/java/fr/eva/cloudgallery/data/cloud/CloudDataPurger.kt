package fr.eva.cloudgallery.data.cloud

import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.offline.OfflineStore
import fr.eva.cloudgallery.data.cloud.precache.PrecacheCleaner
import fr.eva.cloudgallery.data.cloud.sync.CloudSyncThrottleStore
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.transfer.CloudDownloadUrlProvider
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.CloudFolderDao
import fr.eva.cloudgallery.data.db.dao.DownloadTaskDao
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.dao.SyncCursorDao
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive data in the local index (Room database) and copies of OneDrive files made by the app
 * (thumbnails, downloaded originals, offline copies). Never touches MediaStore, the device's
 * files or OneDrive: only rows that came from OneDrive and the app's private files are removed.
 *
 * The caller first stops the offline copy engine and removes its scheduled work
 * (CloudSessionCoordinator, under the sync exclusion).
 */
@Singleton
class CloudDataPurger @Inject constructor(
    private val db: AppDatabase,
    private val mediaDao: MediaDao,
    private val sourceDao: MediaSourceDao,
    private val albumDao: AlbumDao,
    private val syncCursorDao: SyncCursorDao,
    private val cloudFolderDao: CloudFolderDao,
    private val cachedFileDao: CachedFileDao,
    private val downloadTaskDao: DownloadTaskDao,
    private val thumbnails: CloudThumbnailCache,
    private val throttle: CloudSyncThrottleStore,
    private val downloadUrls: CloudDownloadUrlProvider,
    private val originals: CloudOriginalStore,
    private val offlineStore: OfflineStore,
    private val precache: PrecacheCleaner,
    private val generation: CloudDataGeneration,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * True if OneDrive data remains in the index, including hidden sources (deleted items in
     * their grace period), OneDrive albums or folders, a sync cursor, OneDrive files copied to
     * the device (offline copies included) or queued downloads.
     */
    suspend fun hasData(): Boolean = withContext(io) {
        sourceDao.existsAny(SourceKind.ONEDRIVE) ||
            sourceDao.existsAny(SourceKind.OFFLINE_COPY) ||
            cachedFileDao.existsAny() ||
            downloadTaskDao.existsAny() ||
            albumDao.existsByType(AlbumType.CLOUD_FOLDER) ||
            cloudFolderDao.existsAny() ||
            syncCursorDao.get(ONEDRIVE_PROVIDER) != null
    }

    /**
     * Removes all OneDrive data in one transaction: OneDrive sources and offline copies, media
     * that became orphaned (with, by cascade, their favorites, album memberships and playback
     * positions), OneDrive albums, OneDrive folders, cursor, rows of copied OneDrive files and
     * the download queue. A media item that also has a local source is kept. Offline copies,
     * downloaded originals, cached OneDrive thumbnails (disk and memory) and the caching of all
     * of OneDrive (passes stopped) are then deleted, as well as the sync pause requested by
     * Graph for this account.
     *
     * First of all, the wipe counter is incremented (a download in progress no longer saves
     * anything) and the temporary download URLs kept in memory are forgotten (a playback in
     * progress no longer obtains any for the old account).
     */
    suspend fun purgeAll() = withContext(io) {
        generation.bump()
        downloadUrls.clear()
        db.withTransaction {
            val ids = (sourceDao.mediaIdsByKind(SourceKind.ONEDRIVE) + sourceDao.mediaIdsByKind(SourceKind.OFFLINE_COPY)).distinct()
            cachedFileDao.deleteAll()
            downloadTaskDao.deleteAll()
            sourceDao.deleteByKind(SourceKind.ONEDRIVE)
            sourceDao.deleteByKind(SourceKind.OFFLINE_COPY)
            ids.chunked(CHUNK).forEach { mediaDao.refreshSourceFlags(it) }
            mediaDao.deleteOrphans()
            albumDao.deleteByType(AlbumType.CLOUD_FOLDER)
            albumDao.clearDanglingCovers()
            cloudFolderDao.deleteAll()
            syncCursorDao.delete(ONEDRIVE_PROVIDER)
        }
        offlineStore.clear()
        originals.clearAll()
        thumbnails.clear()
        precache.clearAll()
        throttle.clear()
    }

    private companion object {
        const val CHUNK = 500
    }
}
