package fr.eva.cloudgallery.data.cloud.offline

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.OfflineThumbnail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Clé de cache mémoire : média + version de la copie (aucun chemin de fichier). */
private class OfflineThumbnailKeyer : Keyer<OfflineThumbnail> {
    override fun key(data: OfflineThumbnail, options: Options): String = "offline:${data.mediaId}:${data.version}"
}

/**
 * Miniature tirée de la copie hors ligne d'un média (élément conservé hors ligne dont la
 * miniature OneDrive n'est pas en cache, ou qui n'existe plus sur OneDrive). Le fichier est
 * retrouvé en base puis lu sur le dispatcher d'E/S ; Coil le décode à la taille demandée (image,
 * ou image d'une vidéo par VideoFrameDecoder). Rien n'est écrit dans le cache disque.
 */
class OfflineThumbnailFetcher private constructor(
    private val data: OfflineThumbnail,
    private val options: Options,
    private val factory: Factory,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(factory.io) {
        val row = factory.cachedFiles.get(data.mediaId, CacheKind.OFFLINE) ?: throw FileNotFoundException("Copie hors ligne absente")
        val file = factory.store.fileOf(row.path)?.takeIf { it.isFile } ?: throw FileNotFoundException("Copie hors ligne absente")
        SourceFetchResult(
            source = ImageSource(file.toOkioPath(), options.fileSystem),
            mimeType = data.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    @Singleton
    class Factory @Inject constructor(
        internal val cachedFiles: CachedFileDao,
        internal val store: OfflineStore,
        @param:IoDispatcher internal val io: CoroutineDispatcher,
    ) : Fetcher.Factory<OfflineThumbnail> {

        override fun create(data: OfflineThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            OfflineThumbnailFetcher(data, options, this)

        /** Enregistre le Keyer et cette fabrique dans les composants de l'ImageLoader. */
        fun register(components: ComponentRegistry.Builder) {
            components.add(OfflineThumbnailKeyer(), OfflineThumbnail::class)
            components.add(this, OfflineThumbnail::class)
        }
    }
}
