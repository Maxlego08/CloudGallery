package fr.eva.cloudgallery.data.cloud.offline

import android.net.Network
import android.util.Log
import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.content.downloadErrorOf
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.sync.parseCloudCursorVersion
import fr.eva.cloudgallery.data.cloud.transfer.CdnResult
import fr.eva.cloudgallery.data.cloud.transfer.CloudFileDownloader
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.cloud.transfer.shouldEmitProgress
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Téléchargement d'un fichier OneDrive dans un fichier partiel (remplaçable dans les tests). */
fun interface OfflineFileFetcher {
    suspend fun fetch(
        itemId: String,
        part: File,
        expectedCTag: String?,
        network: Network?,
        expectedDriveId: String?,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): CdnResult
}

/** Espace disque du dossier des copies (remplaçable dans les tests). */
interface OfflineSpace {
    /** Espace libre du volume (File.getUsableSpace). */
    suspend fun usableBytes(): Long

    /** Espace obtenable en libérant le cache d'autres applis (StorageManager.getAllocatableBytes). */
    suspend fun allocatableBytes(): Long

    /** Demande au système de libérer [bytes] octets (StorageManager.allocateBytes) ; [IOException] si impossible. */
    suspend fun reserve(bytes: Long)
}

/** Fin d'un passage du moteur. */
enum class DrainOutcome {
    /**
     * Plus rien de téléchargeable maintenant : file vide, entièrement en échec ou en pause, ou
     * tâches restantes qui attendent le Wi-Fi ou la mise à jour de l'index.
     */
    DONE,

    /** Erreur passagère : le travail doit être relancé plus tard (attente gérée par le système). */
    RETRY_LATER,
}

/** Progression d'un passage, pour la notification et JobScheduler. */
data class OfflineProgress(
    /** Nom affiché de l'élément en cours (jamais journalisé). */
    val displayName: String,
    val itemDone: Long,
    val itemTotal: Long,
    /** Eléments terminés pendant ce passage. */
    val completed: Int,
    /** Eléments restants, celui en cours compris. */
    val remaining: Int,
    /** Octets reçus pendant ce passage (tous éléments). */
    val sessionBytes: Long,
)

/** Bilan d'un passage (notification de fin). */
data class DrainSummary(val completed: Int, val failed: Int)

/**
 * Moteur des copies hors ligne : vide la file `download_task`, un élément à la fois.
 *
 * - Un seul passage à la fois ([drain] attend la fin du précédent, nettoyage compris) ; au début
 *   d'un passage, toute tâche « en cours » vient d'un processus disparu et repart en attente.
 * - Chaque tâche est d'abord autorisée sur le réseau du travail (réglage « Wi-Fi uniquement »,
 *   lot « Télécharger maintenant ») : les autres restent en attente du Wi-Fi.
 * - Chaque fichier est écrit dans `offline/<nom>.part` (reprise par Range de la même version),
 *   renommé une fois complet, puis enregistré en une transaction qui revérifie : données
 *   OneDrive non vidées entre-temps, tâche toujours en cours (pas libérée ni annulée), élément
 *   toujours présent dans l'index avec la même version. Sinon le fichier est supprimé.
 * - « Libérer » / « Annuler » d'un élément arrêtent seulement cet élément ([withItemsStopped]) :
 *   le passage continue avec les suivants.
 * - Renommage, enregistrement et suppression ne sont jamais interrompus à moitié.
 * - L'ancienne copie (nouvelle version) reste servie jusqu'à l'enregistrement de la nouvelle,
 *   puis est supprimée.
 */
@Singleton
class OfflineDownloadEngine internal constructor(
    private val db: AppDatabase,
    private val fetcher: OfflineFileFetcher,
    private val store: OfflineStore,
    private val generation: CloudDataGeneration,
    private val canAccess: suspend () -> Boolean,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val space: OfflineSpace = store,
) {

    @Inject
    constructor(
        db: AppDatabase,
        downloader: CloudFileDownloader,
        store: OfflineStore,
        generation: CloudDataGeneration,
        guard: CloudAccessGuard,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        db = db,
        fetcher = OfflineFileFetcher { itemId, part, cTag, network, driveId, onProgress ->
            downloader.download(itemId, part, cTag, network, driveId, onProgress)
        },
        store = store,
        generation = generation,
        canAccess = guard::canAccess,
        io = io,
        clock = System::currentTimeMillis,
        space = store,
    )

    private val tasks = db.downloadTaskDao()
    private val sources = db.mediaSourceDao()
    private val mediaDao = db.mediaDao()
    private val cachedFiles = db.cachedFileDao()

    /** Un seul passage à la fois (P0-4). */
    private val drainMutex = Mutex()

    /**
     * Décision « plus rien à prendre, fin du passage » prise sous ce verrou, comme l'ajout d'une
     * tâche ([withQueue]).
     */
    private val queueLock = Mutex()

    @Volatile
    private var accepting = false

    @Volatile
    private var drainJob: Job? = null

    private val _currentName = MutableStateFlow<String?>(null)

    /** Nom de fichier de la copie en cours de téléchargement (fichier partiel à ne pas toucher). */
    val currentName: StateFlow<String?> = _currentName.asStateFlow()

    /** Elément en cours de traitement : son téléchargement, et la fin de son traitement (enregistrement compris). */
    private class CurrentItem(val mediaId: Long) {
        var fetch: Job? = null
        val done = CompletableDeferred<Unit>()
    }

    private val itemLock = Any()

    /** Médias en cours de libération ou d'annulation (nombre de demandes en cours par média). */
    private val stopRequests = HashMap<Long, Int>()
    private var current: CurrentItem? = null

    /** Un passage est en cours dans ce processus. */
    val isDraining: Boolean get() = drainMutex.isLocked

    /** Bilan du dernier passage terminé. */
    @Volatile
    var lastSummary: DrainSummary = DrainSummary(0, 0)
        private set

    /**
     * Exécute [block] sous le verrou de la file ; [draining] vrai si un passage en cours prendra
     * encore de nouvelles tâches.
     */
    internal suspend fun <T> withQueue(block: suspend (draining: Boolean) -> T): T =
        queueLock.withLock { block(accepting) }

    /**
     * Exécute [block] (suppression des tâches et des copies de [mediaIds]) une fois que le moteur
     * ne travaille plus sur aucun de ces médias : un téléchargement en cours de l'un d'eux est
     * arrêté (fichier fermé) et son enregistrement éventuel terminé ; tant que [block] s'exécute,
     * le moteur ne commence ni n'enregistre aucun d'eux. Le passage continue avec les autres.
     */
    suspend fun <T> withItemsStopped(mediaIds: Collection<Long>, block: suspend () -> T): T {
        val ids = mediaIds.toSet()
        val running = synchronized(itemLock) {
            ids.forEach { stopRequests[it] = (stopRequests[it] ?: 0) + 1 }
            current?.takeIf { it.mediaId in ids }?.also { it.fetch?.cancel() }
        }
        try {
            running?.done?.await()
            return block()
        } finally {
            synchronized(itemLock) {
                ids.forEach { id ->
                    val left = (stopRequests[id] ?: 1) - 1
                    if (left <= 0) stopRequests.remove(id) else stopRequests[id] = left
                }
            }
        }
    }

    private fun isStopRequested(mediaId: Long): Boolean = synchronized(itemLock) { mediaId in stopRequests }

    /**
     * Vide la file. [network] : réseau choisi par JobScheduler (contrainte « Wi-Fi uniquement »
     * respectée par la connexion elle-même). [allowed] : tâches autorisées sur ce réseau, relu
     * avant chaque tâche (null : toutes). Annulable à tout moment (arrêt du travail, purge) : la
     * tâche en cours repart en attente, son fichier partiel est gardé pour la reprise.
     */
    suspend fun drain(
        network: Network?,
        allowed: suspend () -> Set<Long>? = { null },
        onProgress: suspend (OfflineProgress) -> Unit = {},
    ): DrainOutcome =
        drainMutex.withLock {
            drainJob = currentCoroutineContext().job
            try {
                withContext(io) { runDrain(network, allowed, onProgress) }
            } finally {
                accepting = false
                _currentName.value = null
                drainJob = null
            }
        }

    /**
     * Arrête le passage en cours et attend sa fin (fichiers fermés, tâche remise en attente).
     * Utilisé par la purge et « Annuler » de la notification.
     */
    suspend fun stopAndJoin() {
        drainJob?.cancelAndJoin()
    }

    /** Arrête le passage en cours puis exécute [block] sans qu'aucun passage ne puisse démarrer. */
    suspend fun <T> whileStopped(block: suspend () -> T): T {
        stopAndJoin()
        return drainMutex.withLock { block() }
    }

    /**
     * Hors passage seulement : tâches « en cours » d'un processus disparu remises en attente ou,
     * si ce processus a été arrêté par l'utilisateur pendant un téléchargement, toute la file en
     * pause (voir [startupRecovery]). Sans effet pendant un passage. Renvoie le nombre de tâches
     * modifiées.
     */
    suspend fun recoverIfIdle(firstStart: Boolean, userRequestedExit: Boolean): Int {
        if (!drainMutex.tryLock()) return 0
        try {
            return withContext(io) {
                when (startupRecovery(firstStart, userRequestedExit, tasks.existsRunning())) {
                    StartupRecovery.PAUSE -> tasks.pauseActive(clock())
                    StartupRecovery.REQUEUE -> tasks.resetRunningToQueued(clock())
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    private class DrainState {
        var completed = 0
        var failed = 0
        var sessionBytes = 0L
        var spaceChecked = false

        /** Tâches laissées de côté pendant ce passage (libération ou annulation en cours). */
        val skipped = HashSet<Long>()
    }

    private suspend fun runDrain(
        network: Network?,
        allowed: suspend () -> Set<Long>?,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): DrainOutcome {
        val state = DrainState()
        try {
            // Un seul moteur par processus et son verrou est tenu : toute tâche « en cours » est périmée.
            tasks.resetRunningToQueued(clock())
            accepting = true
            if (!canAccess()) {
                failAll(DownloadError.AUTH_REQUIRED)
                return DrainOutcome.DONE
            }
            val driveId = parseCloudCursorVersion(db.syncCursorDao().get(ONEDRIVE_PROVIDER)?.version)?.driveId
            while (true) {
                // Réglage relu à chaque tâche : le passer à « Wi-Fi uniquement » s'applique aussitôt.
                val permitted = allowed()
                val task = queueLock.withLock {
                    nextTask(permitted, state.skipped).also { if (it == null) accepting = false }
                } ?: break
                when (processTask(task, network, driveId, state, onProgress)) {
                    Step.NEXT -> Unit
                    Step.RETRY_LATER -> return DrainOutcome.RETRY_LATER
                    Step.STOP -> return DrainOutcome.DONE
                }
            }
            return DrainOutcome.DONE
        } finally {
            lastSummary = DrainSummary(state.completed, state.failed)
        }
    }

    private suspend fun nextTask(permitted: Set<Long>?, skipped: Set<Long>): DownloadTaskEntity? {
        // Liste jamais vide dans la requête (identifiant impossible) : SQL plus simple à lire.
        val skip = skipped.toList().ifEmpty { listOf(NO_ID) }
        return when {
            permitted == null -> tasks.nextRunnable(skip)
            permitted.isEmpty() -> null
            else -> tasks.nextRunnableAmong(permitted.toList(), skip)
        }
    }

    private enum class Step { NEXT, RETRY_LATER, STOP }

    /** La tâche a disparu pendant son téléchargement (libérée, annulée) : téléchargement arrêté. */
    private class TaskGoneException : Exception()

    private suspend fun processTask(
        task: DownloadTaskEntity,
        network: Network?,
        driveId: String?,
        state: DrainState,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): Step {
        // Elément pris en charge avant toute écriture : une libération ou une annulation qui
        // arrive à partir de maintenant arrête ce téléchargement (et l'attend).
        val item = CurrentItem(task.mediaId)
        val stopping = synchronized(itemLock) {
            if (task.mediaId in stopRequests) {
                true
            } else {
                current = item
                false
            }
        }
        if (stopping) {
            // Libération ou annulation en cours : la tâche va être supprimée par l'appelant.
            state.skipped += task.id
            return Step.NEXT
        }
        try {
            return processCurrent(task, item, network, driveId, state, onProgress)
        } finally {
            synchronized(itemLock) { if (current === item) current = null }
            item.done.complete(Unit)
        }
    }

    private suspend fun processCurrent(
        task: DownloadTaskEntity,
        item: CurrentItem,
        network: Network?,
        driveId: String?,
        state: DrainState,
        onProgress: suspend (OfflineProgress) -> Unit,
    ): Step {
        val itemId = task.cloudItemId
        val media = mediaDao.getById(task.mediaId)
        if (media == null) {
            tasks.deleteById(task.id)
            return Step.NEXT
        }
        val source = itemId?.let { sources.find(SourceKind.ONEDRIVE, it) }
        if (itemId == null || source == null || source.mediaId != task.mediaId || source.missingSince != null) {
            // Elément supprimé ou masqué sur OneDrive depuis la mise en file.
            tasks.markFailed(task.id, DownloadError.NOT_FOUND.name, task.attempts, clock())
            state.failed++
            return Step.NEXT
        }
        if (tasks.markRunning(task.id, clock()) == 0) return Step.NEXT
        val cTag = source.cTag
        if (cTag != task.cTag || source.sizeBytes != task.bytesTotal) tasks.setVersion(task.id, cTag, source.sizeBytes)
        if (!state.spaceChecked) {
            state.spaceChecked = true
            if (!ensureSpace()) {
                tasks.markFailed(task.id, DownloadError.NO_SPACE.name, task.attempts + 1, clock())
                failAll(DownloadError.NO_SPACE)
                state.failed++
                return Step.STOP
            }
        }
        val name = cacheFileName(itemId, cTag, media.displayName, media.mimeType)
        store.ensureRoot()
        val part = store.partOf(name) ?: return failTask(task, DownloadError.SERVER, state)
        val dest = store.fileOf(name) ?: return failTask(task, DownloadError.SERVER, state)
        _currentName.value = name
        val remaining = tasks.countQueued() + 1
        val startGeneration = generation.value
        var lastEmitMs = 0L
        var lastBytes = 0L
        var itemBytes = -1L
        var progressed = false
        val result = try {
            coroutineScope {
                val fetch = async(start = CoroutineStart.LAZY) {
                    fetcher.fetch(itemId, part, cTag, network, driveId) { done, total ->
                        // Reprise : le premier relevé part de la taille du fichier partiel.
                        if (itemBytes < 0) itemBytes = done
                        if (done > itemBytes) progressed = true
                        state.sessionBytes += (done - itemBytes).coerceAtLeast(0)
                        itemBytes = done
                        val now = clock()
                        if (shouldEmitProgress(lastEmitMs, now, lastBytes, done, total)) {
                            lastEmitMs = now
                            lastBytes = done
                            // Tâche supprimée entre-temps (libérée, annulée) : inutile de continuer.
                            if (tasks.updateProgress(task.id, done, total, now) == 0) throw TaskGoneException()
                            onProgress(OfflineProgress(media.displayName, done, total, state.completed, remaining, state.sessionBytes))
                        }
                    }
                }
                val stopNow = synchronized(itemLock) {
                    item.fetch = fetch
                    task.mediaId in stopRequests
                }
                if (stopNow) fetch.cancel()
                fetch.await()
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // Cet élément seulement (libéré ou annulé) : la tâche est supprimée par l'appelant.
                state.skipped += task.id
                return Step.NEXT
            }
            // Arrêt du travail : la tâche repart en attente, le fichier partiel reste pour la reprise.
            withContext(NonCancellable) { tasks.requeue(task.id, null, task.attempts, clock()) }
            throw e
        } catch (_: TaskGoneException) {
            state.skipped += task.id
            return Step.NEXT
        } catch (e: Exception) {
            val error = downloadErrorOf(e) ?: if (e is IOException) DownloadError.NETWORK else DownloadError.SERVER
            if (error != DownloadError.NETWORK && error != DownloadError.THROTTLED) {
                Log.w(TAG, "Copie hors ligne interrompue : $error")
            }
            return onFailure(task, error, state, progressed)
        } finally {
            _currentName.value = null
        }
        return withContext(NonCancellable) { finish(task, media, itemId, name, part, dest, result, startGeneration, state) }
    }

    /** Fin du téléchargement : renommage puis enregistrement (jamais interrompus). */
    private suspend fun finish(
        task: DownloadTaskEntity,
        media: MediaEntity,
        itemId: String,
        name: String,
        part: File,
        dest: File,
        result: CdnResult,
        startGeneration: Long,
        state: DrainState,
    ): Step {
        if (!part.renameTo(dest)) {
            part.delete()
            return onFailure(task, DownloadError.SERVER, state, progressed = false)
        }
        val outcome = try {
            commit(task, media, itemId, name, result, startGeneration)
        } catch (e: Exception) {
            Log.w(TAG, "Enregistrement de la copie hors ligne impossible", e)
            Commit.Failed
        }
        return when (outcome) {
            is Commit.Done -> {
                outcome.replacedPath?.let { store.delete(listOf(it)) }
                state.completed++
                Step.NEXT
            }
            Commit.ContentChanged -> {
                dest.delete()
                onFailure(task, DownloadError.CONTENT_CHANGED, state, progressed = false)
            }
            Commit.Discarded -> {
                // Données vidées, tâche libérée ou annulée, élément disparu : rien n'est gardé.
                dest.delete()
                Step.NEXT
            }
            Commit.Failed -> {
                dest.delete()
                onFailure(task, DownloadError.SERVER, state, progressed = false)
            }
        }
    }

    private sealed interface Commit {
        class Done(val replacedPath: String?) : Commit
        data object ContentChanged : Commit
        data object Discarded : Commit
        data object Failed : Commit
    }

    private suspend fun commit(
        task: DownloadTaskEntity,
        media: MediaEntity,
        itemId: String,
        name: String,
        result: CdnResult,
        startGeneration: Long,
    ): Commit = db.withTransaction {
        if (generation.value != startGeneration) return@withTransaction Commit.Discarded
        // Libération ou annulation de cet élément en cours : la copie n'est pas enregistrée.
        if (isStopRequested(media.id)) return@withTransaction Commit.Discarded
        val current = tasks.getById(task.id)
        if (current == null || current.state != DownloadState.RUNNING) return@withTransaction Commit.Discarded
        if (mediaDao.getById(media.id) == null) return@withTransaction Commit.Discarded
        val source = sources.find(SourceKind.ONEDRIVE, itemId)
        if (source == null || source.mediaId != media.id || source.missingSince != null) {
            tasks.markFailed(task.id, DownloadError.NOT_FOUND.name, task.attempts, clock())
            return@withTransaction Commit.Discarded
        }
        if (!contentMatches(source.cTag, source.sizeBytes, source.eTag, result.cTag, result.sizeBytes, result.eTag)) {
            return@withTransaction Commit.ContentChanged
        }
        val version = result.cTag ?: source.cTag
        val offline = sources.find(SourceKind.OFFLINE_COPY, itemId)
        val fields = MediaSourceEntity(
            mediaId = media.id,
            kind = SourceKind.OFFLINE_COPY,
            // Identifiant OneDrive : unique par type et stable (le chemin du fichier est dans cached_file).
            externalId = itemId,
            uri = null,
            // Jamais d'album-dossier : l'appartenance vient de la source OneDrive.
            bucketKey = null,
            bucketName = null,
            relativePath = null,
            sizeBytes = result.sizeBytes,
            dateModified = source.dateModified,
            eTag = result.eTag ?: source.eTag,
            cTag = version,
            missingSince = null,
        )
        val offlineId = when {
            offline == null -> sources.insert(fields)
            offline.mediaId != media.id -> {
                sources.deleteByIds(listOf(offline.id))
                sources.insert(fields)
            }
            else -> {
                sources.update(fields.copy(id = offline.id))
                offline.id
            }
        }
        val now = clock()
        val previous = cachedFiles.replace(
            CachedFileEntity(
                mediaId = media.id,
                sourceId = offlineId,
                kind = CacheKind.OFFLINE,
                path = name,
                sizeBytes = result.sizeBytes,
                createdAt = now,
                lastAccessAt = now,
                pinned = true,
                version = version,
            ),
        )
        mediaDao.refreshSourceFlags(listOf(media.id))
        tasks.deleteById(task.id)
        Commit.Done(previous?.path?.takeIf { it != name })
    }

    private suspend fun onFailure(task: DownloadTaskEntity, error: DownloadError, state: DrainState, progressed: Boolean): Step {
        val attempts = task.attempts + if (countsAsAttempt(error, progressed)) 1 else 0
        val now = clock()
        return when (failureAction(error, attempts)) {
            FailureAction.RETRY_LATER -> {
                tasks.requeue(task.id, error.name, attempts, now)
                Step.RETRY_LATER
            }
            FailureAction.WAIT_FOR_INDEX -> {
                // Laissée en attente (sans compter d'essai) jusqu'à la prochaine synchronisation ;
                // la requête des tâches téléchargeables l'écarte d'ici là.
                tasks.requeue(task.id, error.name, attempts, now)
                state.skipped += task.id
                Step.NEXT
            }
            FailureAction.FAIL -> {
                tasks.markFailed(task.id, error.name, attempts, now)
                state.failed++
                Step.NEXT
            }
            FailureAction.FAIL_ALL -> {
                tasks.markFailed(task.id, error.name, attempts, now)
                failAll(error)
                state.failed++
                Step.STOP
            }
        }
    }

    private suspend fun failTask(task: DownloadTaskEntity, error: DownloadError, state: DrainState): Step {
        tasks.markFailed(task.id, error.name, task.attempts + 1, clock())
        state.failed++
        return Step.NEXT
    }

    private suspend fun failAll(error: DownloadError) {
        queueLock.withLock {
            tasks.failAllQueued(error.name, clock())
            accepting = false
        }
    }

    /**
     * Espace pour toute la file (une seule demande au système par passage) : l'espace libre
     * suffit, ou le système libère le cache d'autres applis (allocateBytes : une écriture
     * ordinaire ne le ferait pas), ou l'espace manque même ainsi.
     */
    private suspend fun ensureSpace(): Boolean {
        val needed = tasks.remainingBytes()
        return try {
            when (spaceAction(space.usableBytes(), space.allocatableBytes(), needed, OFFLINE_SPACE_MARGIN_BYTES)) {
                SpaceAction.ENOUGH -> true
                SpaceAction.ALLOCATE -> {
                    space.reserve(needed + OFFLINE_SPACE_MARGIN_BYTES)
                    true
                }
                SpaceAction.NO_SPACE -> false
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val TAG = "OfflineEngine"
        const val NO_ID = -1L
    }
}
