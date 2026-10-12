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
 * Données OneDrive de l'index local (base Room) et copies de fichiers OneDrive faites par l'appli
 * (miniatures, originaux téléchargés, copies hors ligne). Ne touche jamais MediaStore, les
 * fichiers de l'appareil ni OneDrive : seules les lignes issues de OneDrive et les fichiers
 * privés de l'appli sont retirés.
 *
 * L'appelant arrête d'abord le moteur des copies hors ligne et retire son travail planifié
 * (CloudSessionCoordinator, sous l'exclusion de la synchro).
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
     * Vrai s'il reste des données OneDrive dans l'index, y compris des sources masquées
     * (éléments supprimés en délai de grâce), des albums ou dossiers OneDrive, un curseur de synchro,
     * des fichiers OneDrive copiés sur l'appareil (copies hors ligne comprises) ou des
     * téléchargements en file.
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
     * Retire toutes les données OneDrive en une transaction : sources OneDrive et copies hors
     * ligne, médias devenus orphelins (avec, par cascade, leurs favoris, ajouts aux albums et
     * positions de lecture), albums OneDrive, dossiers OneDrive, curseur, lignes des fichiers
     * OneDrive copiés et file de téléchargement. Un média qui a aussi une source locale est
     * conservé. Les copies hors ligne, les originaux téléchargés, les miniatures OneDrive en
     * cache (disque et mémoire) et la mise en cache de tout OneDrive (passages arrêtés) sont
     * ensuite supprimés, ainsi que la pause de synchronisation
     * demandée par Graph pour ce compte.
     *
     * Avant tout, le numéro de vidage est incrémenté (un téléchargement en cours n'enregistre plus
     * rien) et les URL de téléchargement temporaires gardées en mémoire sont oubliées (une lecture
     * en cours n'en obtient plus pour l'ancien compte).
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
