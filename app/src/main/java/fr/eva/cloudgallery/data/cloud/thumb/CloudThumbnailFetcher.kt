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
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.precache.CloudPrecacheStore
import fr.eva.cloudgallery.data.cloud.precache.PrecacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
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

/**
 * Clé de cache mémoire : élément + cTag + palier (ou palier d'aperçu effectivement demandé, après
 * repli éventuel : même clé que celle du cache disque).
 */
class CloudThumbnailKeyer internal constructor(private val env: CloudThumbnailFetcher.Factory) : Keyer<CloudThumbnail> {
    override fun key(data: CloudThumbnail, options: Options): String =
        cloudThumbKey(data.ref, bucketOf(options), env.effectivePreviewSide(data.previewSide))
}

/**
 * Charge une miniature Microsoft Graph (`/thumbnails/0/{size}/content`) :
 * 1. cache disque de l'ImageLoader (clé = élément + cTag + palier : fonctionne hors connexion) ;
 * 2. mise en cache de tout OneDrive ([CloudPrecacheStore]) à une taille égale ou supérieure ;
 * 3. sinon, uniquement pour un compte connecté et si la requête autorise le réseau
 *    (`networkCachePolicy`), téléchargement (4 au plus en parallèle, pause globale de Graph
 *    respectée) puis écriture dans le cache disque ; en échec, taille inférieure de la mise en
 *    cache de tout OneDrive s'il y en a une.
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
        val previewSide = env.effectivePreviewSide(data.previewSide)
        val key = cloudThumbKey(data.ref, bucket, previewSide)
        if (options.diskCachePolicy.readEnabled && diskCache != null) {
            diskCache.openSnapshot(key)?.let { return@withContext it.toResult(diskCache, key, null, DataSource.DISK) }
        }
        // Mise en cache de tout OneDrive : taille égale ou supérieure d'abord (aucune perte de netteté).
        precached(bucket, previewSide, allowLower = false)?.let { return@withContext it }
        try {
            fetchFromNetwork(diskCache, bucket, previewSide, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Hors connexion, compte indisponible, miniature introuvable : une taille inférieure
            // mise en cache vaut mieux qu'une case vide.
            precached(bucket, previewSide, allowLower = true) ?: throw e
        }
    }

    /** Fichier de la mise en cache de tout OneDrive pour cette demande, null s'il n'y en a pas. */
    private fun precached(bucket: Int, previewSide: Int?, allowLower: Boolean): FetchResult? {
        val kind = if (previewSide != null) PrecacheKind.PREVIEW else PrecacheKind.GRID
        val file = env.precache.find(data.ref, kind, previewSide ?: bucket, allowLower) ?: return null
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    private suspend fun fetchFromNetwork(diskCache: DiskCache?, bucket: Int, previewSide: Int?, key: String): FetchResult {
        // Requête limitée au cache (copie hors ligne affichée) : jamais de téléchargement.
        if (!options.networkCachePolicy.readEnabled) throw CloudThumbnailUnavailableException()
        val generation = env.generation.value
        if (env.isUnavailable(key, generation)) throw CloudThumbnailUnavailableException()
        env.ensureSignedIn()
        val size = cloudThumbSize(bucket, previewSide)
        return env.permits.withPermit {
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
                        if (previewSide != null && previewSide > CLOUD_PREVIEW_PX) {
                            // Grand palier d'aperçu refusé, palier inférieur accepté : abandonné
                            // jusqu'au redémarrage (les clés suivent le palier effectif).
                            env.maxPreviewSide = CLOUD_PREVIEW_PX
                        } else if (e.status == HTTP_BAD_REQUEST) {
                            // Taille personnalisée refusée mais taille standard acceptée : plus de double requête.
                            env.customSizesRejected = true
                        }
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
        if (env.generation.value != generation) throw CloudAuthRequiredException()
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
        private val guard: CloudAccessGuard,
        internal val graph: dagger.Lazy<GraphApi>,
        internal val generation: CloudDataGeneration,
        internal val precache: CloudPrecacheStore,
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

        /** Plus grand palier d'aperçu accepté par Graph (abaissé après un refus, jusqu'au redémarrage). */
        @Volatile
        internal var maxPreviewSide = CLOUD_PREVIEW_TIERS.last()

        /** Palier d'aperçu réellement demandé pour [requested] (null : vignette de grille). */
        internal fun effectivePreviewSide(requested: Int?): Int? = requested?.let {
            cappedPreviewSide(it, if (customSizesRejected) CLOUD_PREVIEW_PX else maxPreviewSide)
        }

        override fun create(data: CloudThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            CloudThumbnailFetcher(data, options, imageLoader, this)

        /** Enregistre le Keyer et cette fabrique dans les composants de l'ImageLoader. */
        fun register(components: ComponentRegistry.Builder) {
            components.add(CloudThumbnailKeyer(this), CloudThumbnail::class)
            components.add(this, CloudThumbnail::class)
        }

        internal fun isUnavailable(key: String, generation: Long): Boolean {
            syncUnavailableGeneration(generation)
            return key in unavailable
        }

        /** Mémorisé seulement si les données n'ont pas été vidées depuis le début du chargement. */
        internal fun markUnavailable(key: String, generation: Long) {
            if (this.generation.value == generation) unavailable.add(key)
        }

        @Synchronized
        private fun syncUnavailableGeneration(generation: Long) {
            if (unavailableGeneration != generation) {
                unavailable.clear()
                unavailableGeneration = generation
            }
        }

        /**
         * Téléchargement autorisé uniquement pour un compte connecté, jamais pendant une
         * déconnexion ni quand le OneDrive connecté n'est pas celui des données de l'index
         * (règle commune [CloudAccessGuard]) : seules les miniatures déjà en cache disque des
         * éléments d'un autre compte restent affichées.
         */
        internal suspend fun ensureSignedIn() = guard.ensureSignedIn()
    }

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val UNAVAILABLE_TTL_MS = 10 * 60_000L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
