package fr.eva.cloudgallery.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.local.externalIdCandidatesFromMediaStorePath
import fr.eva.cloudgallery.data.local.orderedByListPosition
import fr.eva.cloudgallery.data.local.externalIdFromMediaDocumentId
import fr.eva.cloudgallery.data.mapper.toDetails
import fr.eva.cloudgallery.data.mapper.toSummary
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.MediaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    private val sourceDao: MediaSourceDao,
    private val indexer: LocalMediaIndexer,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    override fun pagedMedia(context: MediaContext): Flow<PagingData<MediaSummary>> {
        if (context is MediaContext.Single) {
            return mediaDao.observeRow(context.mediaId)
                .map { row -> PagingData.from(listOfNotNull(row?.toSummary())) }
                .flowOn(io)
        }
        return Pager(PAGING_CONFIG) {
            when (context) {
                MediaContext.Timeline -> mediaDao.pagingTimeline()
                MediaContext.Favorites -> mediaDao.pagingFavorites()
                MediaContext.Videos -> mediaDao.pagingVideos()
                is MediaContext.Album -> mediaDao.pagingAlbum(context.albumId)
                is MediaContext.Single -> error("handled above")
            }
        }.flow.map { data -> data.map { row -> row.toSummary() } }
    }

    override fun observeMediaIds(context: MediaContext): Flow<List<Long>> = when (context) {
        MediaContext.Timeline -> mediaDao.observeTimelineIds()
        MediaContext.Favorites -> mediaDao.observeFavoriteIds()
        MediaContext.Videos -> mediaDao.observeVideoIds()
        is MediaContext.Album -> mediaDao.observeAlbumIds(context.albumId)
        is MediaContext.Single -> mediaDao.observeById(context.mediaId).map { listOfNotNull(it?.id) }
    }.distinctUntilChanged().flowOn(io)

    override fun observeMedia(id: Long): Flow<MediaDetails?> = combine(
        mediaDao.observeById(id),
        sourceDao.observeForMedia(id),
        mediaDao.observeIsFavorite(id),
    ) { media, sources, favorite -> media?.toDetails(favorite, sources) }
        .distinctUntilChanged()
        .flowOn(io)

    override suspend fun getMedia(id: Long): MediaDetails? = withContext(io) {
        val media = mediaDao.getById(id) ?: return@withContext null
        media.toDetails(mediaDao.observeIsFavorite(id).first(), sourceDao.getForMedia(id))
    }

    override suspend fun getSummaries(ids: List<Long>): List<MediaSummary> = withContext(io) {
        if (ids.isEmpty()) return@withContext emptyList()
        val byId = ids.distinct().chunked(CHUNK).flatMap { mediaDao.getRows(it) }.associateBy { it.id }
        ids.mapNotNull { byId[it]?.toSummary() }
    }

    override suspend fun idsInPeriod(context: MediaContext, startMs: Long, endMs: Long): List<Long> = withContext(io) {
        when (context) {
            MediaContext.Timeline -> mediaDao.timelineIdsInPeriod(startMs, endMs)
            MediaContext.Favorites -> mediaDao.favoriteIdsInPeriod(startMs, endMs)
            MediaContext.Videos -> mediaDao.videoIdsInPeriod(startMs, endMs)
            is MediaContext.Album -> mediaDao.albumIdsInPeriod(context.albumId, startMs, endMs)
            is MediaContext.Single -> mediaDao.getRows(listOf(context.mediaId))
                .filter { it.dateTaken in startMs until endMs }
                .map { it.id }
        }
    }

    override suspend fun idsBetween(context: MediaContext, a: MediaSummary, b: MediaSummary): List<Long> = withContext(io) {
        val (newer, older) = orderedByListPosition(a, b)
        when (context) {
            MediaContext.Timeline -> mediaDao.timelineIdsBetween(newer.dateTaken, newer.id, older.dateTaken, older.id)
            MediaContext.Favorites -> mediaDao.favoriteIdsBetween(newer.dateTaken, newer.id, older.dateTaken, older.id)
            MediaContext.Videos -> mediaDao.videoIdsBetween(newer.dateTaken, newer.id, older.dateTaken, older.id)
            is MediaContext.Album ->
                mediaDao.albumIdsBetween(context.albumId, newer.dateTaken, newer.id, older.dateTaken, older.id)
            is MediaContext.Single -> listOf(context.mediaId)
        }
    }

    override fun observeCount(context: MediaContext): Flow<Int> = when (context) {
        MediaContext.Timeline -> mediaDao.observeTimelineCount()
        MediaContext.Favorites -> mediaDao.observeFavoriteCount()
        MediaContext.Videos -> mediaDao.observeVideoCount()
        is MediaContext.Album -> mediaDao.observeAlbumCount(context.albumId)
        is MediaContext.Single -> mediaDao.observeById(context.mediaId).map { if (it != null) 1 else 0 }
    }.distinctUntilChanged().flowOn(io)

    override suspend fun findByContentUri(uri: Uri): Long? = withContext(io) {
        val candidates = externalIdCandidates(uri)
        if (candidates.isEmpty()) return@withContext null
        lookup(candidates)?.let { return@withContext it }
        // Pas encore indexé (photo qui vient d'être prise, par exemple) : synchro incrémentale puis nouvel essai.
        runCatching { indexer.sync(full = false) }
        lookup(candidates)
    }

    private suspend fun lookup(candidates: List<String>): Long? =
        sourceDao.findAll(SourceKind.LOCAL, candidates).firstOrNull { it.missingSince == null }?.mediaId

    private fun externalIdCandidates(uri: Uri): List<String> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return emptyList()
        return when (uri.authority) {
            MediaStore.AUTHORITY -> externalIdCandidatesFromMediaStorePath(uri.pathSegments.orEmpty())
            MEDIA_DOCUMENTS_AUTHORITY -> runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?.let { externalIdFromMediaDocumentId(it) }
                ?.let { listOf(it) }
                .orEmpty()
            // TODO: URI du sélecteur de photos (content://media/picker/...) et URI FileProvider tierces :
            //  non rattachables à une source MediaStore, la visionneuse doit alors les afficher sans index.
            else -> emptyList()
        }
    }

    private companion object {
        const val CHUNK = 500
        const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

        /**
         * Sans emplacements réservés : les en-têtes de jour/mois sont insérés entre éléments chargés,
         * des emplacements vides (comptés comme des cellules) feraient sauter la grille à l'arrivée
         * de chaque page. [PagingConfig.maxSize] borne la mémoire après un long défilement (100k médias).
         */
        val PAGING_CONFIG = PagingConfig(
            pageSize = 150,
            prefetchDistance = 450,
            enablePlaceholders = false,
            initialLoadSize = 450,
            maxSize = 1_800,
        )
    }
}
