package fr.eva.cloudgallery.data.cloud.thumb

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudThumbnail
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Pas de miniature pour cet élément (Graph n'en génère pas, ou introuvable récemment). */
class CloudThumbnailUnavailableException : IOException("Miniature OneDrive indisponible")

/** Palier de taille d'une requête Coil (plus grand côté demandé). */
private fun bucketOf(options: Options): Int {
    val w = options.size.width.pxOrElse { DEFAULT_CLOUD_THUMB_PX }
    val h = options.size.height.pxOrElse { DEFAULT_CLOUD_THUMB_PX }
    return cloudThumbBucket(maxOf(w, h))
}

/** Clé de cache mémoire : élément + cTag + palier (ou aperçu). */
class CloudThumbnailKeyer : Keyer<CloudThumbnail> {
    override fun key(data: CloudThumbnail, options: Options): String =
        cloudThumbKey(data.ref, bucketOf(options), data.preview)
}

/**
 * Charge une miniature Microsoft Graph (`/thumbnails/0/{size}/content`) :
 * 1. cache disque de l'ImageLoader (clé = élément + cTag + palier : fonctionne hors connexion) ;
 * 2. sinon, uniquement pour un compte connecté, téléchargement (4 au plus en parallèle, pause
 *    globale de Graph respectée) puis écriture dans le cache disque.
 * Tout le travail (disque, réseau, MSAL) se fait sur le dispatcher d'E/S.
 */
class CloudThumbnailFetcher private constructor(
    private val data: CloudThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val env: Factory,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(env.io) {
        // Lu sur le dispatcher d'E/S : la première lecture crée le cache disque (répertoire).
        val diskCache = imageLoader.diskCache
        val bucket = bucketOf(options)
        val key = cloudThumbKey(data.ref, bucket, data.preview)
        if (options.diskCachePolicy.readEnabled && diskCache != null) {
            diskCache.openSnapshot(key)?.let { return@withContext it.toResult(diskCache, key, null, DataSource.DISK) }
        }
        val generation = env.cache.generation
        if (env.isUnavailable(key, generation)) throw CloudThumbnailUnavailableException()
        env.ensureSignedIn()
        val size = cloudThumbSize(bucket, data.preview)
        env.permits.withPermit {
            val path = effectiveThumbPath(size, env.customSizesRejected)
            try {
                download(path, key, generation, diskCache)
            } catch (e: GraphHttpException) {
                val fallback = size.fallback
                if (fallback == null || path == fallback || !shouldTryFallbackSize(e.status)) {
                    if (e.status == HTTP_NOT_FOUND) env.markUnavailable(key, generation)
                    throw e
                }
                try {
                    download(fallback, key, generation, diskCache).also {
                        // Taille personnalisée refusée mais taille standard acceptée : plus de double requête.
                        if (e.status == HTTP_BAD_REQUEST) env.customSizesRejected = true
                    }
                } catch (e2: GraphHttpException) {
                    if (e2.status == HTTP_NOT_FOUND) env.markUnavailable(key, generation)
                    throw e2
                }
            }
        }
    }

    private suspend fun download(path: String, key: String, generation: Long, diskCache: DiskCache?): FetchResult =
        env.graph.get().thumbnailContent(data.ref.itemId, path) { body, mimeType ->
            val cache = diskCache?.takeIf { options.diskCachePolicy.writeEnabled }
            val editor = cache?.openEditor(key)
            if (cache != null && editor != null) {
                writeToCache(cache, editor, body, key, mimeType, generation)
            } else {
                // Ecriture déjà en cours pour cette clé (ou cache désactivé) : lecture en mémoire.
                val buffer = Buffer().apply { writeAll(body) }
                checkGeneration(generation)
                SourceFetchResult(ImageSource(buffer, options.fileSystem), mimeType, DataSource.NETWORK)
            }
        }

    private fun writeToCache(
        cache: DiskCache,
        editor: DiskCache.Editor,
        body: BufferedSource,
        key: String,
        mimeType: String?,
        generation: Long,
    ): FetchResult {
        try {
            cache.fileSystem.write(editor.data) { writeAll(body) }
            checkGeneration(generation)
            val snapshot = editor.commitAndOpenSnapshot() ?: throw IOException("Miniature absente du cache disque")
            return snapshot.toResult(cache, key, mimeType, DataSource.NETWORK)
        } catch (e: Throwable) {
            try {
                editor.abort()
            } catch (_: Exception) {
                // Editeur déjà validé ou fermé.
            }
            throw e
        }
    }

    /** Données OneDrive retirées pendant le téléchargement (déconnexion) : rien n'est conservé. */
    private fun checkGeneration(generation: Long) {
        if (env.cache.generation != generation) throw CloudAuthRequiredException()
    }

    private fun DiskCache.Snapshot.toResult(cache: DiskCache, key: String, mimeType: String?, source: DataSource) =
        SourceFetchResult(ImageSource(data, cache.fileSystem, key, this), mimeType, source)

    /**
     * Fabrique unique (Hilt). Construite au démarrage de l'application : ses dépendances lourdes
     * (client Graph, OkHttp, MSAL) sont injectées via dagger.Lazy et ne sont créées qu'au premier
     * téléchargement, sur le dispatcher d'E/S.
     */
    @Singleton
    class Factory @Inject constructor(
        private val feature: CloudFeature,
        internal val graph: dagger.Lazy<GraphApi>,
        private val auth: dagger.Lazy<MsalAuthService>,
        private val syncer: dagger.Lazy<CloudMediaSyncer>,
        internal val cache: CloudThumbnailCache,
        @param:IoDispatcher internal val io: CoroutineDispatcher,
    ) : Fetcher.Factory<CloudThumbnail> {

        /** Téléchargements simultanés (défilement rapide : Coil annule les cellules sorties de l'écran). */
        internal val permits = Semaphore(MAX_PARALLEL_DOWNLOADS)

        /**
         * Miniatures introuvables (404) : pas de nouvelle requête pendant 10 minutes. Oubliées dès
         * que les données OneDrive sont vidées (déconnexion, purge, changement de compte) : les
         * clés ne contiennent rien qui soit propre au compte.
         */
        private val unavailable = ExpiringKeySet(UNAVAILABLE_TTL_MS, System::currentTimeMillis)

        /** Numéro de vidage du cache auquel correspondent les clés de [unavailable]. */
        @Volatile
        private var unavailableGeneration = 0L

        /** Graph a refusé une taille personnalisée : tailles standard uniquement (jusqu'au redémarrage). */
        @Volatile
        internal var customSizesRejected = false

        private val accountMutex = Mutex()

        override fun create(data: CloudThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            CloudThumbnailFetcher(data, options, imageLoader, this)

        /** Enregistre le Keyer et cette fabrique dans les composants de l'ImageLoader. */
        fun register(components: ComponentRegistry.Builder) {
            components.add(CloudThumbnailKeyer(), CloudThumbnail::class)
            components.add(this, CloudThumbnail::class)
        }

        internal fun isUnavailable(key: String, generation: Long): Boolean {
            syncUnavailableGeneration(generation)
            return key in unavailable
        }

        /** Mémorisé seulement si les données n'ont pas été vidées depuis le début du chargement. */
        internal fun markUnavailable(key: String, generation: Long) {
            if (cache.generation == generation) unavailable.add(key)
        }

        @Synchronized
        private fun syncUnavailableGeneration(generation: Long) {
            if (unavailableGeneration != generation) {
                unavailable.clear()
                unavailableGeneration = generation
            }
        }

        /**
         * Téléchargement autorisé uniquement pour un compte connecté (jamais pendant une
         * déconnexion), et jamais quand le OneDrive connecté n'est pas celui des données de
         * l'index : les éléments de l'autre compte ne sont pas demandés avec ce jeton (seules
         * leurs miniatures déjà en cache disque restent affichées).
         */
        internal suspend fun ensureSignedIn() {
            if (!feature.enabled) throw CloudException(CloudErrorCode.CONFIG)
            val service = auth.get()
            val state = service.state.value
            if (state is CloudAccountState.Loading || state is CloudAccountState.Error) {
                // Démarrage du processus ou lecture précédente en échec : une seule relecture du
                // compte MSAL pour toutes les cellules.
                accountMutex.withLock { service.refreshIfNeeded() }
            }
            if (service.state.value !is CloudAccountState.SignedIn) throw CloudAuthRequiredException()
            if (syncer.get().state.value.accountMismatch) throw CloudAuthRequiredException()
        }
    }

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val UNAVAILABLE_TTL_MS = 10 * 60_000L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
