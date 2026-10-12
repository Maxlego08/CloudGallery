package fr.eva.cloudgallery.data.cloud.content

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.offline.OfflineSpace
import fr.eva.cloudgallery.data.cloud.offline.SpaceAction
import fr.eva.cloudgallery.data.cloud.offline.spaceAction
import fr.eva.cloudgallery.data.cloud.transfer.CloudFileDownloader
import fr.eva.cloudgallery.data.cloud.transfer.PART_SUFFIX
import fr.eva.cloudgallery.data.cloud.transfer.SweepEntry
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.cloud.transfer.filesToSweep
import fr.eva.cloudgallery.data.cloud.transfer.shouldEmitProgress
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudDownloadException
import fr.eva.cloudgallery.domain.model.DEFAULT_ORIGINALS_CACHE_BYTES
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Elément OneDrive dont on veut le fichier complet. */
data class CloudFileTarget(
    val mediaId: Long,
    val itemId: String,
    /** Version du contenu (cTag) connue de l'index ; null si inconnue (copie jamais réutilisée). */
    val cTag: String?,
    val sizeBytes: Long,
    val displayName: String,
    val mimeType: String?,
)

/**
 * Originaux OneDrive téléchargés à la demande (zoom profond, partage, « Ouvrir avec ») :
 * fichiers temporaires de l'appli dans `cacheDir/onedrive/originals/`, hors du cache Coil, chacun
 * enregistré dans `cached_file` (type ORIGINAL, non épinglé, version = cTag) pour le nettoyage
 * LRU ([trim]) sous le plafond réglable `AppSettings.originalsCacheMaxBytes`, au démarrage et
 * après chaque téléchargement. Aucun fichier de l'utilisateur n'est concerné.
 *
 * - Un seul téléchargement par élément et version à la fois, quel que soit le nombre de
 *   demandeurs (visionneuse, partage) ; il tourne dans la portée de l'appli et s'arrête quand le
 *   dernier demandeur abandonne.
 * - Espace vérifié avant le téléchargement (cache d'autres applis libéré par le système si
 *   nécessaire), sinon [DownloadError.NO_SPACE].
 * - Ecriture dans un fichier `.part`, renommé une fois complet (sous un autre nom si un fichier
 *   du même nom existe déjà : il n'est jamais écrasé avant l'enregistrement du nouveau), puis
 *   enregistrement en une transaction qui revérifie que les données OneDrive n'ont pas été vidées
 *   entre-temps et que l'élément est toujours celui de l'index (même identifiant, même version).
 *   Sinon le nouveau fichier est supprimé ; l'ancien n'est supprimé qu'après l'enregistrement.
 * - Lecture d'un original existant et nettoyage ne se croisent pas ([accessLock]).
 * - Tout le travail sur le dispatcher d'E/S.
 */
@Singleton
class CloudOriginalStore internal constructor(
    private val root: File,
    private val downloader: CloudFileDownloader,
    private val db: AppDatabase,
    private val cachedFiles: CachedFileDao,
    private val sources: MediaSourceDao,
    private val generation: CloudDataGeneration,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    /** Espace du volume des originaux ; null : pas de vérification (tests). */
    private val space: OfflineSpace? = null,
    private val nonce: () -> String = { java.lang.Long.toHexString(Random.nextLong()) },
    /** Plafond courant du cache (réglage), lu à chaque nettoyage. */
    private val maxBytesSetting: suspend () -> Long = { DEFAULT_ORIGINALS_CACHE_BYTES },
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloader: CloudFileDownloader,
        db: AppDatabase,
        cachedFiles: CachedFileDao,
        sources: MediaSourceDao,
        generation: CloudDataGeneration,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
        settings: SettingsRepository,
    ) : this(
        root = File(context.cacheDir, ORIGINALS_DIR),
        downloader = downloader,
        db = db,
        cachedFiles = cachedFiles,
        sources = sources,
        generation = generation,
        scope = scope,
        io = io,
        clock = System::currentTimeMillis,
        space = StorageSpace(context, File(context.cacheDir, ORIGINALS_DIR)),
        maxBytesSetting = { settings.settings.first().originalsCacheMaxBytes },
    )

    /** Espace du volume de [dir] (StorageManager), appels faits sur le dispatcher d'E/S par l'appelant. */
    private class StorageSpace(private val context: Context, private val dir: File) : OfflineSpace {
        private val storage: StorageManager? get() = context.getSystemService(StorageManager::class.java)

        override suspend fun usableBytes(): Long = dir.usableSpace

        override suspend fun allocatableBytes(): Long {
            val sm = storage ?: return dir.usableSpace
            return try {
                sm.getAllocatableBytes(sm.getUuidForPath(dir))
            } catch (_: IOException) {
                dir.usableSpace
            }
        }

        override suspend fun reserve(bytes: Long) {
            if (bytes <= 0) return
            val sm = storage ?: return
            sm.allocateBytes(sm.getUuidForPath(dir), bytes)
        }
    }

    /** Téléchargement partagé par ses demandeurs ([waiters]) ; [progress] = octets écrits. */
    private class Flight(val name: String, val deferred: Deferred<File>, val total: Long) {
        var waiters = 0
        val progress = MutableStateFlow(0L)
    }

    private val lock = Any()
    private val flights = HashMap<String, Flight>()

    /**
     * Dernier téléchargement lancé pour chaque nom de fichier : un nouveau téléchargement du même
     * fichier attend la fin du précédent (annulé mais encore en train de fermer le fichier partiel).
     */
    private val lastJobs = HashMap<String, Deferred<File>>()

    /**
     * Lecture d'un original existant ([existing]) et suppressions du nettoyage ([trim]) : jamais
     * en même temps (un fichier renvoyé à la visionneuse ou au partage n'est pas supprimé aussitôt).
     */
    private val accessLock = Mutex()

    /** Un seul nettoyage à la fois ; [trimPending] regroupe les demandes faites pendant ce temps. */
    private val trimLock = Mutex()
    private val trimPending = AtomicBoolean(false)

    /** Original déjà présent et à jour pour [target] (sa date d'accès est rafraîchie), sinon null. */
    suspend fun existing(target: CloudFileTarget): File? = withContext(io) {
        val version = target.cTag ?: return@withContext null
        accessLock.withLock {
            val row = cachedFiles.get(target.mediaId, CacheKind.ORIGINAL) ?: return@withLock null
            if (row.version != version) return@withLock null
            val file = resolve(row.path) ?: return@withLock null
            if (!file.isFile || file.length() != row.sizeBytes) return@withLock null
            cachedFiles.touch(row.id, clock())
            file
        }
    }

    /**
     * Original de [target], téléchargé si nécessaire. [onProgress] reçoit les octets écrits et la
     * taille totale. Lève [CloudDownloadException] (dont [DownloadError.AUTH_REQUIRED] si les
     * données OneDrive ont été vidées pendant le téléchargement) ; l'annulation de l'appelant
     * n'interrompt le téléchargement que s'il n'a plus d'autre demandeur.
     */
    suspend fun ensureOriginal(target: CloudFileTarget, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        existing(target)?.let { return it }
        val key = "${target.mediaId}:${target.itemId}:${target.cTag}"
        val flight = synchronized(lock) {
            val found = flights[key] ?: run {
                val name = cacheFileName(target.itemId, target.cTag, target.displayName, target.mimeType)
                val previous = lastJobs[name]
                val deferred = scope.async(io, start = CoroutineStart.LAZY) {
                    previous?.join()
                    download(key, name, target)
                }
                lastJobs[name] = deferred
                Flight(name, deferred, target.sizeBytes).also { flights[key] = it }
            }
            found.waiters++
            found
        }
        flight.deferred.start()
        try {
            return coroutineScope {
                val relay = launch { flight.progress.collect { onProgress(it, flight.total) } }
                try {
                    flight.deferred.await()
                } finally {
                    relay.cancel()
                }
            }
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters <= 0) {
                    if (flights[key] === flight) flights.remove(key)
                    // Plus personne n'attend : le téléchargement s'arrête (le fichier partiel reste
                    // pour une reprise de la même version, supprimé au prochain nettoyage sinon).
                    if (!flight.deferred.isCompleted) flight.deferred.cancel()
                }
            }
        }
    }

    private suspend fun download(key: String, name: String, target: CloudFileTarget): File {
        val self = kotlin.coroutines.coroutineContext.job
        try {
            val startGeneration = generation.value
            if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
                throw CloudDownloadException(DownloadError.NO_SPACE, IOException("Dossier des originaux inaccessible"))
            }
            val part = File(root, "$name$PART_SUFFIX")
            ensureSpace((target.sizeBytes - (if (part.isFile) part.length() else 0L)).coerceAtLeast(0L))
            val flight = synchronized(lock) { flights[key]?.takeIf { it.deferred === self } }
            var lastEmitMs = 0L
            var lastBytes = 0L
            val result = downloader.download(target.itemId, part, target.cTag) { done, total ->
                val now = clock()
                if (shouldEmitProgress(lastEmitMs, now, lastBytes, done, total)) {
                    lastEmitMs = now
                    lastBytes = done
                    flight?.progress?.value = done
                }
            }
            // Fin du téléchargement : renommage et enregistrement ne sont jamais interrompus à
            // moitié (sinon fichier sans ligne, ou ligne sans fichier).
            return withContext(NonCancellable) {
                // Nom déjà pris (version inconnue, ou fichier précédent non réutilisable) : autre
                // nom, l'ancien fichier reste intact tant que le nouveau n'est pas enregistré.
                val usual = File(root, name)
                val dest = if (usual.exists()) File(root, alternateFileName(name, nonce())) else usual
                if (!part.renameTo(dest)) {
                    part.delete()
                    throw CloudDownloadException(DownloadError.NO_SPACE, IOException("Renommage impossible"))
                }
                val outcome = try {
                    commit(target, dest.name, result.sizeBytes, result.cTag, startGeneration)
                } catch (e: Exception) {
                    Log.w(TAG, "Enregistrement de l'original impossible", e)
                    CommitOutcome.Failed(DownloadError.SERVER)
                }
                when (outcome) {
                    is CommitOutcome.Committed -> {
                        // Ancien fichier supprimé seulement maintenant (et seulement s'il diffère).
                        outcome.replacedPath?.let { resolve(it)?.delete() }
                        requestTrim()
                        dest
                    }
                    is CommitOutcome.Failed -> {
                        dest.delete()
                        throw CloudDownloadException(outcome.error)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (flights[key]?.deferred === self) flights.remove(key)
                if (lastJobs[name] === self) lastJobs.remove(name)
            }
        }
    }

    /**
     * Espace pour [needed] octets de plus (marge comprise) : rien à faire, ou cache d'autres
     * applis libéré par le système (StorageManager.allocateBytes), sinon [DownloadError.NO_SPACE].
     */
    private suspend fun ensureSpace(needed: Long) {
        val space = space ?: return
        val enough = try {
            when (spaceAction(space.usableBytes(), space.allocatableBytes(), needed, ORIGINAL_SPACE_MARGIN_BYTES)) {
                SpaceAction.ENOUGH -> true
                SpaceAction.ALLOCATE -> {
                    space.reserve(needed + ORIGINAL_SPACE_MARGIN_BYTES)
                    true
                }
                SpaceAction.NO_SPACE -> false
            }
        } catch (_: IOException) {
            false
        }
        if (!enough) throw CloudDownloadException(DownloadError.NO_SPACE)
    }

    private sealed interface CommitOutcome {
        class Committed(val replacedPath: String?) : CommitOutcome
        class Failed(val error: DownloadError) : CommitOutcome
    }

    private suspend fun commit(
        target: CloudFileTarget,
        name: String,
        sizeBytes: Long,
        cTag: String?,
        startGeneration: Long,
    ): CommitOutcome = db.withTransaction {
        // Données OneDrive vidées (déconnexion, changement de compte) depuis le début : rien n'est gardé.
        if (generation.value != startGeneration) return@withTransaction CommitOutcome.Failed(DownloadError.AUTH_REQUIRED)
        val source = sources.find(SourceKind.ONEDRIVE, target.itemId)
        if (source == null || source.mediaId != target.mediaId || source.missingSince != null) {
            return@withTransaction CommitOutcome.Failed(DownloadError.NOT_FOUND)
        }
        // Contenu changé sur OneDrive (synchro passée entre-temps) : cette copie est déjà périmée.
        if (source.cTag != null && cTag != null && source.cTag != cTag) {
            return@withTransaction CommitOutcome.Failed(DownloadError.CONTENT_CHANGED)
        }
        val now = clock()
        val previous = cachedFiles.replace(
            CachedFileEntity(
                mediaId = target.mediaId,
                sourceId = source.id,
                kind = CacheKind.ORIGINAL,
                path = name,
                sizeBytes = sizeBytes,
                createdAt = now,
                lastAccessAt = now,
                pinned = false,
                version = cTag,
            ),
        )
        CommitOutcome.Committed(previous?.path?.takeIf { it != name })
    }

    /**
     * Nettoyage en arrière-plan (portée de l'appli), après un téléchargement ou un changement de
     * plafond. Une demande faite pendant un nettoyage en relance un seul à la fin.
     */
    fun requestTrim() {
        if (!trimPending.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                trimLock.withLock {
                    trimPending.set(false)
                    trimNow(maxBytesSetting(), MAX_AGE_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Nettoyage des originaux impossible", e)
            }
        }
    }

    /**
     * Nettoyage LRU ([planOriginalTrim]) : lignes sans fichier, originaux inutilisés depuis plus
     * de [maxAgeMs], puis les moins récemment utilisés au-delà de [maxBytes] (par défaut le
     * réglage), sauf ceux utilisés depuis moins de 30 min ; fichiers sans ligne (plus d'une heure)
     * et fichiers partiels abandonnés (plus de 24 h). Les téléchargements en cours ne sont jamais
     * touchés.
     */
    suspend fun trim(maxBytes: Long? = null, maxAgeMs: Long = MAX_AGE_MS): Unit = withContext(io) {
        trimLock.withLock { trimNow(maxBytes ?: maxBytesSetting(), maxAgeMs) }
    }

    private suspend fun trimNow(maxBytes: Long, maxAgeMs: Long) {
        val now = clock()
        val busy = synchronized(lock) { flights.values.map { it.name }.toSet() }
        val rows = cachedFiles.listByKind(CacheKind.ORIGINAL).map { row ->
            TrimRow(row.id, row.path, row.sizeBytes, row.lastAccessAt, fileExists = resolve(row.path)?.isFile == true, pinned = row.pinned)
        }
        val plan = planOriginalTrim(rows, busy, now, maxBytes, maxAgeMs)
        // Suppression ligne par ligne, seulement si la ligne n'a pas changé depuis sa lecture :
        // un original rouvert (date d'accès), remplacé par une nouvelle version (autre fichier)
        // ou en cours de lecture ([accessLock]) entre-temps est gardé.
        val keptAnyway = HashSet<String>()
        if (plan.drop.isNotEmpty()) {
            accessLock.withLock {
                for (row in plan.drop) {
                    if (cachedFiles.deleteIfUnchanged(row.id, row.path, row.lastAccessAt) > 0) {
                        resolve(row.path)?.delete()
                    } else {
                        keptAnyway += row.path
                    }
                }
            }
        }
        val known = plan.keep.map { it.path }.toSet() + keptAnyway
        val files = root.listFiles().orEmpty().associateBy { it.name }
        val entries = files.values.map { SweepEntry(it.name, it.isFile, now - it.lastModified()) }
        filesToSweep(entries, known, busy).forEach { files[it]?.delete() }
    }

    /**
     * Supprime tous les originaux (purge des données OneDrive, après la suppression des lignes) et
     * arrête les téléchargements en cours.
     */
    suspend fun clearAll(): Unit = withContext(io) {
        val running = synchronized(lock) {
            (flights.values.map { it.deferred } + lastJobs.values).distinct().also { flights.clear() }
        }
        running.forEach { it.cancel() }
        // Attend (brièvement) qu'ils aient fermé leurs fichiers ; leur enregistrement échouera de
        // toute façon (numéro de vidage changé avant la purge).
        withTimeoutOrNull(CLEAR_JOIN_TIMEOUT_MS) { running.forEach { it.join() } }
        root.listFiles()?.forEach { it.delete() }
    }

    /** Fichier d'une ligne `cached_file`, uniquement s'il est bien dans le dossier des originaux. */
    private fun resolve(path: String): File? {
        if (path.isEmpty() || path.contains('/') || path.contains('\\') || path == "." || path == "..") return null
        val file = File(root, path)
        return try {
            if (file.canonicalFile.parentFile == root.canonicalFile) file else null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** Dossier des originaux sous `cacheDir` (exposé au partage par FileProvider). */
        const val ORIGINALS_DIR = "onedrive/originals"
        private const val TAG = "CloudOriginalStore"

        /** Originaux inutilisés depuis plus de 7 jours supprimés au démarrage. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val CLEAR_JOIN_TIMEOUT_MS = 5_000L
    }
}

/**
 * Fichier OneDrive de [media] à télécharger pour l'afficher ou le partager : élément OneDrive
 * visible, sans fichier local utilisable. Null sinon.
 */
fun cloudFileTargetOf(media: MediaDetails): CloudFileTarget? {
    if (media.hasLocal && media.uri != null) return null
    val cloud = media.cloudSource ?: return null
    return CloudFileTarget(
        mediaId = media.id,
        itemId = cloud.externalId,
        cTag = cloud.cTag,
        sizeBytes = cloud.sizeBytes.takeIf { it > 0 } ?: media.sizeBytes,
        displayName = media.displayName,
        mimeType = media.mimeType,
    )
}

/** Erreur de téléchargement portée par [e] ou sa chaîne de causes, sinon null. */
fun downloadErrorOf(e: Throwable): DownloadError? =
    generateSequence(e) { it.cause }.take(8).filterIsInstance<CloudDownloadException>().firstOrNull()?.error
