package fr.eva.cloudgallery.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import fr.eva.cloudgallery.data.cloud.offline.OfflineStore
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.data.local.externalIdCandidatesFromMediaStorePath
import fr.eva.cloudgallery.data.local.orderedByListPosition
import fr.eva.cloudgallery.data.local.externalIdFromMediaDocumentId
import fr.eva.cloudgallery.data.mapper.toDetails
import fr.eva.cloudgallery.data.mapper.toSummary
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.OfflineFile
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos (Timeline) et Vidéos suivent le réglage « Afficher les éléments OneDrive dans Photos »
 * (grille, identifiants, nombres) ; Favoris, albums et média unique montrent tout le visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    private val sourceDao: MediaSourceDao,
    private val cachedFileDao: CachedFileDao,
    private val offlineStore: OfflineStore,
    private val indexer: LocalMediaIndexer,
    settingsRepository: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    /** Réglage « Afficher les éléments OneDrive dans Photos ». */
    private val includeCloudOnly: Flow<Boolean> = settingsRepository.settings
        .map { it.showCloudInPhotos }
        .distinctUntilChanged()

    override fun pagedMedia(context: MediaContext): Flow<PagingData<MediaSummary>> = when (context) {
        is MediaContext.Single -> mediaDao.observeRow(context.mediaId)
            .map { row -> PagingData.from(listOfNotNull(row?.toSummary())) }
            .flowOn(io)
        MediaContext.Timeline -> includeCloudOnly.flatMapLatest { cloud -> pager { mediaDao.pagingTimeline(cloud) } }
        MediaContext.Videos -> includeCloudOnly.flatMapLatest { cloud -> pager { mediaDao.pagingVideos(cloud) } }
        MediaContext.Favorites -> pager { mediaDao.pagingFavorites() }
        is MediaContext.Album -> pager { mediaDao.pagingAlbum(context.albumId) }
    }

    private fun pager(source: () -> PagingSource<Int, MediaRow>): Flow<PagingData<MediaSummary>> =
        Pager(PAGING_CONFIG, pagingSourceFactory = source).flow.map { data -> data.map { row -> row.toSummary() } }

    override fun observeMediaIds(context: MediaContext): Flow<List<Long>> = when (context) {
        MediaContext.Timeline -> includeCloudOnly.flatMapLatest { mediaDao.observeTimelineIds(it) }
        MediaContext.Favorites -> mediaDao.observeFavoriteIds()
        MediaContext.Videos -> includeCloudOnly.flatMapLatest { mediaDao.observeVideoIds(it) }
        is MediaContext.Album -> mediaDao.observeAlbumIds(context.albumId)
        is MediaContext.Single -> mediaDao.observeById(context.mediaId).map { listOfNotNull(it?.id) }
    }.distinctUntilChanged().flowOn(io)

    /** Observe aussi la copie hors ligne : la visionneuse bascule sur le fichier dès qu'il est enregistré. */
    override fun observeMedia(id: Long): Flow<MediaDetails?> = combine(
        mediaDao.observeById(id),
        sourceDao.observeForMedia(id),
        mediaDao.observeIsFavorite(id),
        cachedFileDao.observe(id, CacheKind.OFFLINE),
    ) { media, sources, favorite, offline -> media?.toDetails(favorite, sources, offline?.toOfflineFile()) }
        .distinctUntilChanged()
        .flowOn(io)

    override suspend fun getMedia(id: Long): MediaDetails? = withContext(io) {
        val media = mediaDao.getById(id) ?: return@withContext null
        media.toDetails(
            mediaDao.observeIsFavorite(id).first(),
            sourceDao.getForMedia(id),
            cachedFileDao.get(id, CacheKind.OFFLINE)?.toOfflineFile(),
        )
    }

    /** Fichier d'une copie hors ligne enregistrée (chemin relatif vérifié, aucune E/S). */
    private fun CachedFileEntity.toOfflineFile(): OfflineFile? =
        offlineStore.fileOf(path)?.let { OfflineFile(it, sizeBytes, version) }

    override suspend fun getSummaries(ids: List<Long>): List<MediaSummary> = withContext(io) {
        if (ids.isEmpty()) return@withContext emptyList()
        val byId = ids.distinct().chunked(CHUNK).flatMap { mediaDao.getRows(it) }.associateBy { it.id }
        ids.mapNotNull { byId[it]?.toSummary() }
    }

    override suspend fun countWithLocalFile(ids: Collection<Long>): Int = withContext(io) {
        ids.distinct().chunked(CHUNK).sumOf { mediaDao.countWithLocalFile(it) }
    }

    override suspend fun countShareable(ids: Collection<Long>): Int = withContext(io) {
        ids.distinct().chunked(CHUNK).sumOf { mediaDao.countShareable(it) }
    }

    override suspend fun countKeepableOffline(ids: Collection<Long>): Int = withContext(io) {
        ids.distinct().chunked(CHUNK).sumOf { mediaDao.countKeepableOffline(it) }
    }

    override suspend fun countWithOfflineCopy(ids: Collection<Long>): Int = withContext(io) {
        ids.distinct().chunked(CHUNK).sumOf { mediaDao.countWithOfflineCopy(it) }
    }

    override suspend fun idsInPeriod(context: MediaContext, startMs: Long, endMs: Long): List<Long> = withContext(io) {
        when (context) {
            MediaContext.Timeline -> mediaDao.timelineIdsInPeriod(includeCloudOnly.first(), startMs, endMs)
            MediaContext.Favorites -> mediaDao.favoriteIdsInPeriod(startMs, endMs)
            MediaContext.Videos -> mediaDao.videoIdsInPeriod(includeCloudOnly.first(), startMs, endMs)
            is MediaContext.Album -> mediaDao.albumIdsInPeriod(context.albumId, startMs, endMs)
            is MediaContext.Single -> mediaDao.getRows(listOf(context.mediaId))
                .filter { it.dateTaken in startMs until endMs }
                .map { it.id }
        }
    }

    override suspend fun idsBetween(context: MediaContext, a: MediaSummary, b: MediaSummary): List<Long> = withContext(io) {
        val (newer, older) = orderedByListPosition(a, b)
        when (context) {
            MediaContext.Timeline ->
                mediaDao.timelineIdsBetween(includeCloudOnly.first(), newer.dateTaken, newer.id, older.dateTaken, older.id)
            MediaContext.Favorites -> mediaDao.favoriteIdsBetween(newer.dateTaken, newer.id, older.dateTaken, older.id)
            MediaContext.Videos ->
                mediaDao.videoIdsBetween(includeCloudOnly.first(), newer.dateTaken, newer.id, older.dateTaken, older.id)
            is MediaContext.Album ->
                mediaDao.albumIdsBetween(context.albumId, newer.dateTaken, newer.id, older.dateTaken, older.id)
            is MediaContext.Single -> listOf(context.mediaId)
        }
    }

    override fun observeCount(context: MediaContext): Flow<Int> = when (context) {
        MediaContext.Timeline -> includeCloudOnly.flatMapLatest { mediaDao.observeTimelineCount(it) }
        MediaContext.Favorites -> mediaDao.observeFavoriteCount()
        MediaContext.Videos -> includeCloudOnly.flatMapLatest { mediaDao.observeVideoCount(it) }
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
