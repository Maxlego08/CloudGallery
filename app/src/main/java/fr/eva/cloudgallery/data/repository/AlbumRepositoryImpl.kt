package fr.eva.cloudgallery.data.repository

import android.util.Log
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.sync.folderIdOfCloudBucket
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.mapper.toSummary
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepositoryImpl @Inject constructor(
    private val albumDao: AlbumDao,
    private val graphApi: GraphApi,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : AlbumRepository {

    override fun observeAlbums(sort: AlbumSort): Flow<List<AlbumSummary>> =
        albumDao.observeAlbumRows()
            .map { rows -> rows.map { it.toSummary() }.sortedWith(albumComparator(sort)) }
            .distinctUntilChanged()
            .flowOn(io)

    override fun observeAlbum(albumId: Long): Flow<AlbumSummary?> =
        albumDao.observeAlbumRow(albumId)
            .map { it?.toSummary() }
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun createVirtualAlbum(name: String): Long = withContext(io) {
        val now = System.currentTimeMillis()
        albumDao.insert(
            AlbumEntity(
                name = name.trim(),
                type = AlbumType.VIRTUAL,
                sourceKey = null,
                relativePath = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Album virtuel : change son nom. Album-dossier : définit un nom personnalisé (le dossier
     * n'est pas renommé sur le disque) ; un nom vide rétablit le nom du dossier.
     */
    override suspend fun renameAlbum(albumId: Long, name: String): Unit = withContext(io) {
        val album = albumDao.getById(albumId) ?: return@withContext
        val trimmed = name.trim()
        val now = System.currentTimeMillis()
        if (album.type == AlbumType.VIRTUAL) {
            if (trimmed.isNotEmpty()) albumDao.renameVirtual(albumId, trimmed, now)
        } else {
            albumDao.rename(albumId, trimmed.takeIf { it.isNotEmpty() && it != album.name }, now)
        }
    }

    override suspend fun deleteVirtualAlbum(albumId: Long): Unit = withContext(io) { albumDao.deleteVirtual(albumId) }

    /** N'ajoute que des références, et uniquement dans un album virtuel. */
    override suspend fun addToAlbum(albumId: Long, mediaIds: Collection<Long>): Unit = withContext(io) {
        val album = albumDao.getById(albumId) ?: return@withContext
        if (album.type != AlbumType.VIRTUAL) {
            Log.w(TAG, "addToAlbum ignoré : l'album $albumId n'est pas virtuel")
            return@withContext
        }
        val now = System.currentTimeMillis()
        mediaIds.distinct().chunked(CHUNK).forEach { chunk ->
            albumDao.insertMembers(chunk.map { AlbumMediaEntity(albumId = albumId, mediaId = it, addedAt = now) })
        }
    }

    override suspend fun removeFromAlbum(albumId: Long, mediaIds: Collection<Long>): Unit = withContext(io) {
        mediaIds.distinct().chunked(CHUNK).forEach { albumDao.removeMembers(albumId, it) }
    }

    override suspend fun setPinned(albumId: Long, pinned: Boolean): Unit = withContext(io) {
        albumDao.setPinned(albumId, pinned, System.currentTimeMillis())
    }

    override fun observeAllAlbums(): Flow<List<AlbumSummary>> =
        albumDao.observeAllAlbumRows()
            .map { rows -> rows.map { it.toSummary() }.sortedWith(albumComparator(AlbumSort.NAME)) }
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun setVisibility(albumId: Long, visibility: AlbumVisibility): Unit = withContext(io) {
        albumDao.setVisibility(
            id = albumId,
            hidden = visibility != AlbumVisibility.SHOWN,
            hideMedia = visibility == AlbumVisibility.HIDDEN_EVERYWHERE,
            now = System.currentTimeMillis(),
        )
    }

    override suspend fun oneDriveFolderUrl(albumId: Long): String? {
        val album = withContext(io) { albumDao.getById(albumId) } ?: return null
        if (album.type != AlbumType.CLOUD_FOLDER) return null
        val folderId = album.sourceKey?.let(::folderIdOfCloudBucket) ?: return null
        return graphApi.webUrl(folderId)
    }

    private companion object {
        const val TAG = "AlbumRepository"
        const val CHUNK = 500
    }
}
