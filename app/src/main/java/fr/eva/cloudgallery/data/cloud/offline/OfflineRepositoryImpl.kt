package fr.eva.cloudgallery.data.cloud.offline

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Network
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.transfer.cacheFileName
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.data.net.NetworkState
import fr.eva.cloudgallery.data.net.NetworkStatus
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.DeletedOnOneDriveCopy
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineQueueState
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.OfflinePurgeImpact
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies hors ligne : file de téléchargement ([OfflineDownloadEngine] + [OfflineJobScheduler]),
 * libération, et décision sur les copies d'éléments supprimés de OneDrive (D1). Tout le travail
 * sur la base et les fichiers passe par le dispatcher d'E/S.
 *
 * Réseau (décision D2) : avec « Wi-Fi uniquement », une tâche n'est téléchargée que sur un
 * réseau non facturé, sauf si elle faisait partie de la file au moment d'un « Télécharger
 * maintenant » (dérogation propre à ces tâches : les tâches ajoutées ensuite, dont les
 * nouvelles versions trouvées par la synchronisation, suivent de nouveau le réglage).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OfflineRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val engine: OfflineDownloadEngine,
    private val scheduler: OfflineJobScheduler,
    private val jobs: OfflineJobTracker,
    private val store: OfflineStore,
    private val guard: CloudAccessGuard,
    private val network: NetworkStatus,
    private val settings: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : OfflineRepository {

    private val tasks = db.downloadTaskDao()
    private val sources = db.mediaSourceDao()
    private val mediaDao = db.mediaDao()
    private val cachedFiles = db.cachedFileDao()

    /** Tâches d'un lot « Télécharger maintenant » (oubliées au redémarrage du processus). */
    private val overrides = MutableStateFlow<Set<OverrideKey>>(emptySet())

    private val startupDone = AtomicBoolean(false)

    private val wifiOnly: Flow<Boolean> = settings.settings.map { it.offlineWifiOnly }.distinctUntilChanged()

    /** Règle réseau en vigueur et réseau actuel, pour l'affichage de l'attente du Wi-Fi. */
    private class Policy(val wifiOnly: Boolean, val overrides: Set<OverrideKey>, val net: NetworkState) {
        fun waits(task: DownloadTaskEntity): Boolean =
            task.state == DownloadState.QUEUED &&
                waitsForUnmetered(wifiOnly, OverrideKey(task.id, task.createdAt) in overrides, net.connected, net.unmetered)
    }

    private val policy: Flow<Policy> = combine(wifiOnly, overrides, network.state) { onlyWifi, keys, net ->
        Policy(onlyWifi, keys, net)
    }

    override val queue: Flow<OfflineQueueState> = combine(
        tasks.observeOpen(),
        cachedFiles.observeCount(CacheKind.OFFLINE),
        cachedFiles.observeTotalSize(CacheKind.OFFLINE),
        policy,
    ) { open, count, bytes, rules ->
        val queued = open.count { it.state == DownloadState.QUEUED }
        val running = open.count { it.state == DownloadState.RUNNING }
        OfflineQueueState(
            queued = queued,
            running = running,
            paused = open.count { it.state == DownloadState.PAUSED },
            failed = open.count { it.state == DownloadState.FAILED },
            remainingBytes = open.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
                .sumOf { (it.bytesTotal - it.bytesDownloaded).coerceAtLeast(0) },
            waitingForUnmetered = running == 0 && open.any(rules::waits),
            availableCount = count,
            availableBytes = bytes,
        )
    }.distinctUntilChanged().flowOn(io)

    override fun observeItem(mediaId: Long): Flow<OfflineItemState> = combine(
        tasks.observeOpenForMedia(mediaId),
        mediaDao.observeById(mediaId).map { it?.hasOffline == true }.distinctUntilChanged(),
        policy,
    ) { task, hasOffline, rules ->
        offlineStatus(
            taskState = task?.state?.let(::taskStateOf),
            taskError = downloadErrorOrNull(task?.error),
            bytesDone = task?.bytesDownloaded ?: 0L,
            bytesTotal = task?.bytesTotal ?: 0L,
            hasOffline = hasOffline,
            waitingForUnmetered = task != null && rules.waits(task),
        )
    }.distinctUntilChanged().flowOn(io)

    // --- Conserver hors ligne ------------------------------------------------------------------

    override suspend fun keepOffline(mediaIds: Collection<Long>, confirmedLargeBatch: Boolean): KeepOfflineResult = withContext(io) {
        if (mediaIds.isEmpty()) return@withContext KeepOfflineResult.NothingToDo
        if (!guard.canAccess()) return@withContext KeepOfflineResult.NotSignedIn
        val ids = mediaIds.distinct()
        // Eléments sans copie, et éléments dont la mise à jour (nouvelle version) est arrêtée.
        val candidates = ids.chunked(CHUNK).flatMap { sources.keepOfflineCandidates(it) + sources.stoppedUpdateCandidates(it) }
            .distinctBy { it.mediaId }
        val open = ids.chunked(CHUNK).flatMap { tasks.openForMedia(it) }.associateBy { it.mediaId }
        // Déjà en file et en attente ou en cours, même version : rien à refaire.
        val todo = candidates.filter { c ->
            val task = open[c.mediaId]
            task == null || task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED ||
                task.cTag != c.cTag || task.cloudItemId != c.itemId
        }
        if (todo.isEmpty()) return@withContext KeepOfflineResult.NothingToDo
        val bytes = estimateBytes(todo.map { it.sizeBytes })
        val alreadyQueued = tasks.remainingBytes()
        val missing = missingSpace(store.allocatableBytes(), bytes + alreadyQueued)
        if (missing > 0) return@withContext KeepOfflineResult.NoSpace(missing, store.storageUuid())
        if (!confirmedLargeBatch && isLargeBatch(todo.size, bytes)) {
            return@withContext KeepOfflineResult.NeedsConfirmation(todo.size, bytes, store.allocatableBytes())
        }
        val now = System.currentTimeMillis()
        val entities = todo.map { c ->
            DownloadTaskEntity(
                mediaId = c.mediaId,
                sourceId = null,
                state = DownloadState.QUEUED,
                bytesDownloaded = 0,
                bytesTotal = c.sizeBytes.coerceAtLeast(0),
                keepOffline = true,
                createdAt = now,
                updatedAt = now,
                cloudItemId = c.itemId,
                cTag = c.cTag,
            )
        }
        engine.withQueue { tasks.enqueueIfAbsent(entities) }
        if (ensureScheduled() != ScheduleResult.SCHEDULED) return@withContext KeepOfflineResult.SchedulingRefused(todo.size)
        // Etat du réseau lu directement (le flux partagé commence par un état inconnu). Un
        // nouveau lot ne bénéficie jamais d'un « Télécharger maintenant » antérieur.
        val net = network.current()
        val waiting = waitsForUnmetered(wifiOnly.first(), overridden = false, connected = net.connected, unmetered = net.unmetered)
        KeepOfflineResult.Enqueued(todo.size, bytes, waiting)
    }

    // --- Planification ---------------------------------------------------------------------------

    /** Tâches de « Télécharger maintenant » encore ouvertes (identifiants réattribués écartés). */
    private suspend fun overriddenTaskIds(): Set<Long> {
        val keys = overrides.value
        if (keys.isEmpty()) return emptySet()
        val live = keys.map { it.taskId }.chunked(CHUNK).flatMap { tasks.getByIds(it) }
            .filter { (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) && OverrideKey(it.id, it.createdAt) in keys }
        val ids = live.mapTo(HashSet()) { it.id }
        // Tâches terminées, supprimées ou remplacées : oubliées.
        if (ids.size != keys.size) overrides.update { current -> current.filterTo(HashSet()) { it.taskId in ids } }
        return ids
    }

    /**
     * Réseau exigé par les tâches en attente : tout réseau tant qu'une tâche de « Télécharger
     * maintenant » est en attente ou en cours (son téléchargement n'est jamais interrompu pour
     * attendre le Wi-Fi), sinon d'après le réglage.
     */
    private suspend fun requiredNetwork(): JobNetwork {
        if (!wifiOnly.first()) return JobNetwork.ANY
        return jobNetworkFor(wifiOnly = true, overrideNow = overriddenTaskIds().isNotEmpty())
    }

    /**
     * Tâches qu'un passage du travail peut télécharger sur [jobNetwork] (null : toutes), relu
     * avant chaque tâche : le réglage « Wi-Fi uniquement » s'applique aussitôt.
     */
    internal suspend fun allowedTaskIds(jobNetwork: Network?): Set<Long>? = withContext(io) {
        val onlyWifi = wifiOnly.first()
        if (!onlyWifi) return@withContext null
        val unmetered = if (jobNetwork != null) network.isUnmetered(jobNetwork) else network.current().unmetered
        allowedOnNetwork(onlyWifi, unmetered, if (unmetered) emptySet() else overriddenTaskIds())
    }

    /** Reste-t-il une tâche que le travail actuel ([jobNetwork]) peut télécharger ? */
    private suspend fun hasAllowedWork(jobNetwork: Network?): Boolean {
        val allowed = allowedTaskIds(jobNetwork)
        return when {
            allowed == null -> tasks.existsRunnable()
            allowed.isEmpty() -> false
            else -> allowed.toList().chunked(CHUNK).any { tasks.existsRunnableAmong(it) }
        }
    }

    /**
     * Planifie le travail si les tâches en attente ne sont pas déjà prises en charge par le
     * travail en cours (qui revérifie la file avant de se terminer) ou par un travail planifié
     * avec le bon réseau. Replanifier remplace le travail existant (qui s'arrête, fichier partiel
     * gardé).
     */
    private suspend fun ensureScheduled(): ScheduleResult = jobs.lock.withLock {
        val required = requiredNetwork()
        val active = jobs.active
        val reliable = jobs.pendingReliable
        val pending = if (active == null && reliable) scheduler.pendingNetwork() else null
        if (scheduleDecision(required, active, reliable, pending) == ScheduleDecision.KEEP) return@withLock ScheduleResult.SCHEDULED
        val result = scheduler.schedule(tasks.remainingBytes(), required)
        if (result == ScheduleResult.SCHEDULED) {
            jobs.onScheduled()
        } else {
            Log.i(TAG, "Travail des copies hors ligne non planifié pour l'instant")
        }
        result
    }

    /** Démarrage du travail : renvoie son numéro (voir [OfflineJobTracker]). */
    internal suspend fun jobStarted(): Long = withContext(io) {
        // Le travail en cours est encore renvoyé par JobScheduler : sa contrainte de réseau.
        val constraint = scheduler.pendingNetwork() ?: JobNetwork.ANY
        jobs.lock.withLock { jobs.onJobStarted(constraint) }
    }

    /**
     * Passage terminé : vrai s'il reste du travail téléchargeable sur ce réseau (le travail
     * repasse), sinon le travail est marqué terminé, de façon atomique pour [ensureScheduled] :
     * une tâche ajoutée juste après est confiée à un nouveau travail.
     */
    internal suspend fun continueOrEndJob(seq: Long, jobNetwork: Network?): Boolean = withContext(io) {
        jobs.lock.withLock {
            if (hasAllowedWork(jobNetwork)) {
                true
            } else {
                jobs.onJobEnded(seq, rescheduled = false)
                false
            }
        }
    }

    /** Fin du travail [seq] ([rescheduled] : il reste planifié). */
    internal suspend fun jobEnded(seq: Long, rescheduled: Boolean): Unit = withContext(io) {
        jobs.lock.withLock { jobs.onJobEnded(seq, rescheduled) }
    }

    /** Après la fin normale d'un travail : tâches restées en attente (Wi-Fi) confiées à un nouveau travail. */
    internal suspend fun relaunchIfQueued(): Unit = withContext(io) {
        try {
            if (tasks.existsRunnable() && guard.canAccess()) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Relance des copies hors ligne impossible", e)
        }
    }

    // --- Libérer / annuler ----------------------------------------------------------------------

    override suspend fun lastCopiesAmong(mediaIds: Collection<Long>): Int = withContext(io) {
        // Même règle que release() : copies visibles et masquées.
        mediaIds.distinct().chunked(CHUNK).sumOf { sources.countOfflineLastCopiesAmong(it) }
    }

    override suspend fun release(mediaIds: Collection<Long>): Int = withContext(io) {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        // Téléchargement en cours de l'un d'eux arrêté (fichier fermé) ; les autres continuent.
        engine.withItemsStopped(ids) {
            val names = ArrayList<String>()
            val parts = ArrayList<String>()
            var released = 0
            db.withTransaction {
                for (chunk in ids.chunked(CHUNK)) {
                    parts += partNames(tasks.openForMedia(chunk))
                    val copies = sources.findForMedia(SourceKind.OFFLINE_COPY, chunk)
                    released += copies.map { it.mediaId }.distinct().size
                    names += cachedFiles.getMany(chunk, CacheKind.OFFLINE).map { it.path }
                    tasks.deleteForMedia(chunk)
                    cachedFiles.deleteForMedia(chunk, CacheKind.OFFLINE)
                    sources.deleteForMedia(SourceKind.OFFLINE_COPY, chunk)
                    mediaDao.refreshSourceFlags(chunk)
                }
                // Copie conservée seule (élément supprimé de OneDrive) : l'élément disparaît avec elle.
                mediaDao.deleteOrphans()
                db.albumDao().clearDanglingCovers()
            }
            store.delete(names)
            store.deleteParts(parts)
            released
        }
    }

    override suspend fun cancel(mediaIds: Collection<Long>): Unit = withContext(io) {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return@withContext
        engine.withItemsStopped(ids) {
            val parts = ArrayList<String>()
            engine.withQueue {
                for (chunk in ids.chunked(CHUNK)) {
                    parts += partNames(tasks.openForMedia(chunk))
                    tasks.deleteForMedia(chunk)
                }
            }
            store.deleteParts(parts)
        }
    }

    override suspend fun cancelAll(): Unit = withContext(io) {
        scheduler.cancel()
        overrides.value = emptySet()
        val parts = engine.whileStopped {
            val open = tasks.listOpen()
            tasks.deleteOpen()
            partNames(open)
        }
        store.deleteParts(parts)
    }

    /** Noms des fichiers partiels des tâches (même nom que la copie finale, plus « .part »). */
    private suspend fun partNames(open: List<DownloadTaskEntity>): List<String> {
        if (open.isEmpty()) return emptyList()
        val medias = open.map { it.mediaId }.distinct().chunked(CHUNK).flatMap { mediaDao.getByIds(it) }.associateBy { it.id }
        return open.mapNotNull { task ->
            val media = medias[task.mediaId] ?: return@mapNotNull null
            val itemId = task.cloudItemId ?: return@mapNotNull null
            cacheFileName(itemId, task.cTag, media.displayName, media.mimeType)
        }
    }

    // --- Reprise --------------------------------------------------------------------------------

    override suspend fun resume(): Unit = withContext(io) {
        tasks.resumeStopped(System.currentTimeMillis())
        if (guard.canAccess() && tasks.existsRunnable()) ensureScheduled()
    }

    override suspend fun downloadNow(): Unit = withContext(io) {
        // Dérogation limitée aux tâches présentes maintenant (en attente ou en cours).
        val batch = tasks.listPending().map { OverrideKey(it.id, it.createdAt) }
        overrides.update { it + batch }
        if (guard.canAccess() && tasks.existsRunnable()) ensureScheduled()
    }

    override suspend fun onNetworkPolicyChanged(): Unit = withContext(io) {
        // Nouveau réglage : plus de dérogation ; le travail en cours ou planifié est remplacé s'il
        // n'exige plus le bon réseau (un passage « tout réseau » qui doit attendre le Wi-Fi s'arrête).
        overrides.value = emptySet()
        try {
            if (guard.canAccess() && (tasks.existsRunnable() || jobs.active != null)) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Replanification des copies hors ligne impossible", e)
        }
    }

    override suspend fun resumeIfNeeded(): Unit = withContext(io) {
        if (!guard.configured) return@withContext
        try {
            val firstTime = startupDone.compareAndSet(false, true)
            // Processus arrêté par l'utilisateur pendant un téléchargement (arrêt forcé,
            // Gestionnaire de tâches) : la file reste en pause jusqu'à sa reprise. Un simple
            // balayage depuis les applis récentes, sans téléchargement en cours, ne change rien.
            engine.recoverIfIdle(firstStart = firstTime, userRequestedExit = firstTime && lastExitRequestedByUser())
            if (firstTime) cleanUp()
            if (tasks.existsRunnable() && guard.canAccess()) ensureScheduled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reprise des copies hors ligne impossible", e)
        }
    }

    /** Arrêt par le Gestionnaire de tâches pendant un passage : file en pause (décision D3). */
    internal suspend fun pauseAfterUserStop(): Unit = withContext(io) {
        tasks.pauseActive(System.currentTimeMillis())
        overrides.value = emptySet()
    }

    /**
     * Arrête le moteur et retire le travail planifié, puis exécute [block] (déconnexion, purge)
     * sans qu'aucun téléchargement ne puisse démarrer pendant ce temps.
     */
    internal suspend fun <T> whileStoppedForPurge(block: suspend () -> T): T {
        scheduler.cancel()
        overrides.value = emptySet()
        return engine.whileStopped(block)
    }

    private fun lastExitRequestedByUser(): Boolean = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            ?.firstOrNull()?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
    } catch (_: RuntimeException) {
        false
    }

    /** Nettoyage au premier passage au premier plan : lignes terminées et fichiers sans ligne. */
    private suspend fun cleanUp() {
        val now = System.currentTimeMillis()
        tasks.deleteFinishedBefore(now - FINISHED_RETENTION_MS)
        val known = cachedFiles.listByKind(CacheKind.OFFLINE).map { it.path }.toSet()
        val busy = partNames(tasks.listOpen()).toSet() + listOfNotNull(engine.currentName.value)
        val removed = store.sweepOrphans(known, busy)
        if (removed > 0) Log.i(TAG, "Fichiers hors ligne orphelins supprimés : $removed")
    }

    // --- Eléments supprimés de OneDrive (D1) ------------------------------------------------------

    override val deletedOnOneDrive: Flow<List<DeletedOnOneDriveCopy>> =
        // Echéance calculée à chaque abonnement (un élément peut atteindre les 30 jours entre-temps).
        flow { emitAll(sources.observeAwaitingDecisionMediaIds(System.currentTimeMillis() - MISSING_GRACE_MS)) }
            .distinctUntilChanged()
            .mapLatest { ids ->
                ids.chunked(CHUNK).flatMap { mediaDao.getByIds(it) }
                    .sortedByDescending { it.dateTaken }
                    .map { DeletedOnOneDriveCopy(it.id, it.displayName, it.mediaType, it.mimeType, it.sizeBytes) }
            }
            .flowOn(io)

    override suspend fun deleteCopiesOfDeletedItems(mediaIds: Collection<Long>): Int = withContext(io) {
        val requested = mediaIds.distinct()
        if (requested.isEmpty()) return@withContext 0
        val names = ArrayList<String>()
        var deleted = 0
        db.withTransaction {
            // Uniquement les éléments confirmés par l'utilisateur, et toujours en attente de
            // décision dans cette transaction (un élément revenu sur OneDrive n'est pas touché).
            val expiredBefore = System.currentTimeMillis() - MISSING_GRACE_MS
            for (chunk in requested.chunked(CHUNK)) {
                val ids = sources.awaitingDecisionAmong(chunk, expiredBefore)
                if (ids.isEmpty()) continue
                deleted += ids.size
                names += cachedFiles.getMany(ids, CacheKind.OFFLINE).map { it.path }
                tasks.deleteForMedia(ids)
                cachedFiles.deleteForMedia(ids, CacheKind.OFFLINE)
                sources.deleteForMedia(SourceKind.OFFLINE_COPY, ids)
                // Elément OneDrive masqué depuis plus de 30 jours : retiré comme la purge l'aurait
                // fait (jamais une source OneDrive visible).
                sources.deleteHiddenForMedia(SourceKind.ONEDRIVE, ids)
                mediaDao.refreshSourceFlags(ids)
            }
            mediaDao.deleteOrphans()
            db.albumDao().clearDanglingCovers()
        }
        store.delete(names)
        deleted
    }

    override suspend fun keepCopiesOfDeletedItems(mediaIds: Collection<Long>): Int = withContext(io) {
        val requested = mediaIds.distinct()
        if (requested.isEmpty()) return@withContext 0
        var kept = 0
        db.withTransaction {
            val expiredBefore = System.currentTimeMillis() - MISSING_GRACE_MS
            for (chunk in requested.chunked(CHUNK)) {
                val ids = sources.awaitingDecisionAmong(chunk, expiredBefore)
                if (ids.isEmpty()) continue
                kept += ids.size
                // Plus de source OneDrive masquée : la copie redevient visible, seule (« supprimé de OneDrive »).
                sources.deleteHiddenForMedia(SourceKind.ONEDRIVE, ids)
                sources.syncOfflineCopyVisibility(ids)
                mediaDao.refreshSourceFlags(ids)
            }
        }
        kept
    }

    override suspend fun purgeImpact(): OfflinePurgeImpact = withContext(io) {
        OfflinePurgeImpact(
            copies = sources.countAll(SourceKind.OFFLINE_COPY),
            lastCopies = sources.countOfflineLastCopies(),
        )
    }

    private companion object {
        const val TAG = "OfflineRepository"
        const val CHUNK = 500
        const val FINISHED_RETENTION_MS = 24L * 60 * 60 * 1000

        fun taskStateOf(state: DownloadState): TaskState? = when (state) {
            DownloadState.QUEUED -> TaskState.QUEUED
            DownloadState.RUNNING -> TaskState.RUNNING
            DownloadState.PAUSED -> TaskState.PAUSED
            DownloadState.FAILED -> TaskState.FAILED
            DownloadState.DONE, DownloadState.CANCELLED -> null
        }
    }
}
