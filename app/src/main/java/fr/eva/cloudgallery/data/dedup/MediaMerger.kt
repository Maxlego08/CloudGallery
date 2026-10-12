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
 * Dédoublonnage local / OneDrive dans l'index (jamais de fichier touché).
 *
 * - Fusion : un élément OneDrive et un fichier du téléphone de même QuickXorHash et même taille
 *   ([planMerges]) deviennent un seul média, celui du fichier local (ses métadonnées, sa date
 *   EXIF/MediaStore, son fichier pour l'affichage). Le média OneDrive est supprimé après le
 *   transfert de ses sources (OneDrive, copie hors ligne), favori, albums, couverture d'album,
 *   fichiers en cache, téléchargements et position de lecture.
 * - Défusion ([shouldSplit]) : la source OneDrive (avec sa copie hors ligne, son original
 *   temporaire et ses téléchargements) repart dans un nouveau média ; favoris et albums restent
 *   sur le média du fichier local, qui est celui que l'utilisateur voyait.
 *
 * Appelé après une synchro MediaStore, après un passage OneDrive et après un lot d'empreintes ;
 * un seul passage à la fois, par lots en transactions courtes (chaque couple est revérifié dans
 * sa transaction).
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

    /** Défusions puis fusions. Renvoie le nombre de fusions faites (tests, journal). */
    suspend fun reconcile(): Int = withContext(io) {
        mutex.withLock {
            val splits = dedupDao.mergedMedia().filter(::shouldSplit)
            for (chunk in splits.chunked(CHUNK)) db.withTransaction { chunk.forEach { split(it) } }
            if (splits.isNotEmpty()) Log.i(TAG, "${splits.size} fusion(s) défaite(s) : empreintes différentes")

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
            if (merged > 0) Log.i(TAG, "$merged élément(s) OneDrive rattaché(s) à leur fichier local")
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
        // Restes éventuels (ligne de cache du même type déjà présente) supprimés en cascade.
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
 * Média OneDrive recréé à la défusion : métadonnées du média fusionné (le prochain changement
 * de l'élément sur OneDrive les remplace par celles de Graph), taille, date de modification et
 * empreinte de l'élément OneDrive. Miniatures Graph déjà orientées : orientation 0. Les
 * indicateurs de sources sont recalculés ensuite.
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
