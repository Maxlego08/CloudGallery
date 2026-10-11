package fr.eva.cloudgallery.data.cloud

import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.sync.CloudSyncThrottleStore
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.dao.CloudFolderDao
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
 * Données OneDrive de l'index local (base Room uniquement). Ne touche jamais MediaStore, les
 * fichiers de l'appareil ni OneDrive : seules les lignes issues de OneDrive sont retirées.
 */
@Singleton
class CloudDataPurger @Inject constructor(
    private val db: AppDatabase,
    private val mediaDao: MediaDao,
    private val sourceDao: MediaSourceDao,
    private val albumDao: AlbumDao,
    private val syncCursorDao: SyncCursorDao,
    private val cloudFolderDao: CloudFolderDao,
    private val thumbnails: CloudThumbnailCache,
    private val throttle: CloudSyncThrottleStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Vrai s'il reste des données OneDrive dans l'index, y compris des sources masquées
     * (éléments supprimés en délai de grâce), des albums ou dossiers OneDrive ou un curseur de synchro.
     */
    suspend fun hasData(): Boolean = withContext(io) {
        sourceDao.existsAny(SourceKind.ONEDRIVE) ||
            albumDao.existsByType(AlbumType.CLOUD_FOLDER) ||
            cloudFolderDao.existsAny() ||
            syncCursorDao.get(ONEDRIVE_PROVIDER) != null
    }

    /**
     * Retire toutes les données OneDrive en une transaction : sources, médias devenus orphelins
     * (avec, par cascade, leurs favoris, ajouts aux albums et positions de lecture), albums
     * OneDrive, dossiers OneDrive et curseur. Un média qui a aussi une source locale est conservé.
     * Les miniatures OneDrive en cache (disque et mémoire) sont ensuite vidées, ainsi que la
     * pause de synchronisation demandée par Graph pour ce compte.
     */
    suspend fun purgeAll() = withContext(io) {
        db.withTransaction {
            val ids = sourceDao.mediaIdsByKind(SourceKind.ONEDRIVE)
            sourceDao.deleteByKind(SourceKind.ONEDRIVE)
            ids.chunked(CHUNK).forEach { mediaDao.refreshSourceFlags(it) }
            mediaDao.deleteOrphans()
            albumDao.deleteByType(AlbumType.CLOUD_FOLDER)
            albumDao.clearDanglingCovers()
            cloudFolderDao.deleteAll()
            syncCursorDao.delete(ONEDRIVE_PROVIDER)
        }
        thumbnails.clear()
        throttle.clear()
    }

    private companion object {
        const val CHUNK = 500
    }
}
