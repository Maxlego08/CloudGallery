package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.data.cloud.graph.BACKOFF_CAP_MS
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.graph.GraphResyncRequiredException
import fr.eva.cloudgallery.data.cloud.graph.resyncStartUrl
import fr.eva.cloudgallery.data.db.dao.SyncCursorDao
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronisation OneDrive -> index local par la requête delta de Microsoft Graph (tout le
 * lecteur, lecture seule).
 *
 * - Chaque page est appliquée dès sa réception (écritures idempotentes par identifiant
 *   d'élément) ; le deltaLink n'est enregistré qu'à la fin : un passage interrompu reprend depuis
 *   le curseur précédent.
 * - Le curseur est lié au lecteur (drive.id) dès le début d'une énumération complète : un autre
 *   compte ne purge jamais l'index sans la confirmation de l'utilisateur.
 * - 410 (jeton expiré) ou dossier restauré : énumération complète, les éléments absents sont
 *   masqués. Le deltaLink est effacé dès la décision (un passage interrompu reprend par une
 *   énumération complète, jamais depuis l'ancien curseur).
 * - Limitation Graph : la fin de la pause est enregistrée ([CloudSyncThrottleStore]) et
 *   respectée même après un redémarrage du processus.
 * - Uniquement pour un compte connecté ; demandes regroupées, un seul passage à la fois.
 * - Journaux : ni jeton, ni deltaLink, ni nom de compte.
 */
@Singleton
class OneDriveSyncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feature: CloudFeature,
    private val auth: MsalAuthService,
    private val graph: GraphApi,
    private val writer: CloudSyncWriter,
    private val cursorDao: SyncCursorDao,
    private val throttle: CloudSyncThrottleStore,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CloudMediaSyncer {

    private val _state = MutableStateFlow(CloudSyncState())
    override val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    /** Un seul passage (ou une seule opération exclusive : déconnexion, purge) à la fois. */
    private val mutex = Mutex()

    /** Parent des passages lancés, pour pouvoir les interrompre avant une purge. */
    private val runs = SupervisorJob(scope.coroutineContext.job)

    /** Numéro de demande : un passage démarré après une demande la satisfait (coalescence). */
    private val requestCounter = AtomicLong(0)

    @Volatile
    private var lastStartedRequest = 0L

    @Volatile
    private var stateLoaded = false

    init {
        if (feature.enabled) {
            scope.launch(io) { reloadState() }
            // Connexion ou reconnexion réussie : premier passage sans attendre le premier plan.
            scope.launch {
                auth.signInOutcomes.collect { if (it is SignInOutcome.Success) requestSync(CloudSyncReason.SIGN_IN) }
            }
        }
    }

    override fun requestSync(reason: CloudSyncReason) {
        if (!feature.enabled) return
        val ticket = requestCounter.incrementAndGet()
        scope.launch(runs + io) { runRequest(ticket, reason) }
    }

    override suspend fun syncAndWait(reason: CloudSyncReason): Boolean {
        if (!feature.enabled) return false
        val ticket = requestCounter.incrementAndGet()
        // Enfant de [runs] : interrompu comme les autres passages par exclusive(). L'appelant
        // annulé (travail arrêté par WorkManager : réseau perdu) arrête aussi le passage.
        val run = scope.async(runs + io) { runRequest(ticket, reason) }
        return try {
            run.await()
        } catch (e: CancellationException) {
            run.cancel()
            throw e
        }
    }

    /** Vrai si un passage a interrogé OneDrive pour cette demande (ou un passage démarré après elle). */
    private suspend fun runRequest(ticket: Long, reason: CloudSyncReason): Boolean = mutex.withLock {
        if (lastStartedRequest >= ticket) return@withLock true
        val startTicket = requestCounter.get()
        val ran = runGuarded(reason)
        if (ran) lastStartedRequest = startTicket
        ran
    }

    override suspend fun accountAllowed(accountId: String?): Boolean = withContext(io) {
        if (!stateLoaded) reloadState()
        !_state.value.accountMismatch &&
            cloudAccountAllowed(accountId, throttle.verifiedAccountId(), throttle.accountMismatch())
    }

    override suspend fun <T> exclusive(block: suspend () -> T): T {
        runs.children.toList().forEach { it.cancelAndJoin() }
        return try {
            mutex.withLock { block() }
        } finally {
            withContext(NonCancellable + io) {
                // Données OneDrive éventuellement retirées : état repris de zéro. La pause Graph
                // est relue (conservée sauf si les données du compte ont été purgées).
                _state.value = CloudSyncState()
                reloadState()
            }
        }
    }

    /** Vrai si un passage a réellement interrogé OneDrive (même en échec). */
    private suspend fun runGuarded(reason: CloudSyncReason): Boolean {
        if (!stateLoaded) reloadState()
        // Etat du compte pas encore lu (démarrage du processus) ou lecture précédente en échec.
        auth.refreshIfNeeded()
        val signedIn = auth.state.value as? CloudAccountState.SignedIn ?: return false
        val accountId = signedIn.account.id
        val now = System.currentTimeMillis()
        val current = _state.value
        if (now < current.retryNotBefore) return false
        // Compte pas encore vérifié (autre compte connecté) : passage sans attendre l'intervalle,
        // l'accès au contenu reste bloqué jusque-là.
        val verified = cloudAccountAllowed(accountId, throttle.verifiedAccountId(), mismatch = false)
        val skippable = reason == CloudSyncReason.FOREGROUND || reason == CloudSyncReason.BACKGROUND
        if (skippable && verified && !shouldRunForegroundSync(current.lastSyncAt, current.retryNotBefore, now)) {
            return false
        }
        _state.update { it.copy(running = true, processedItems = 0) }
        try {
            runSync(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onSyncFailed(e)
        } finally {
            withContext(NonCancellable) {
                _state.update { it.copy(running = false) }
                reloadState()
            }
        }
        return true
    }

    private suspend fun runSync(accountId: String?) {
        val driveId = graph.drive().id?.takeIf { it.isNotBlank() }
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Lecteur OneDrive sans identifiant"))
        val previous = cursorDao.get(ONEDRIVE_PROVIDER)
        val start = planSyncStart(
            storedLink = previous?.cursor,
            storedVersion = previous?.version,
            driveId = driveId,
            selectVersion = DELTA_SELECT_VERSION,
            hasIndexedData = writer.hasIndexedData(),
        )
        if (start is SyncStart.AccountMismatch) {
            Log.w(TAG, "OneDrive connecté différent de celui de l'index : synchronisation suspendue")
            _state.update { it.copy(accountMismatch = true) }
            // Conservé : l'accès reste bloqué après un redémarrage, jusqu'à la purge.
            withContext(NonCancellable) { throttle.setAccountMismatch() }
            return
        }
        _state.update { it.copy(accountMismatch = false) }
        // Lecteur conforme à l'index : ce compte est vérifié (avant l'énumération, qui peut être longue).
        accountId?.let { throttle.setVerified(it) }

        val version = cloudCursorVersion(driveId, DELTA_SELECT_VERSION)
        val initialUrl = graph.initialDeltaUrl()
        // Enumération complète interrompue (arrêt du processus, erreur réseau) : reprise depuis la
        // dernière page appliquée, avec les éléments vus rejoués depuis le journal.
        val resume = if (start is SyncStart.Full) loadResume(version) else null
        val run = resume?.run ?: CloudSyncRun(full = start is SyncStart.Full)
        var url = resume?.nextLink ?: (start as? SyncStart.Incremental)?.deltaLink ?: initialUrl
        // Journal de reprise utilisable (désactivé pour ce passage après une erreur d'écriture).
        var journaling = when {
            resume != null -> {
                Log.i(TAG, "Reprise de l'énumération complète après ${resume.pages} pages")
                true
            }
            run.full -> bindDrive(version)
            else -> false
        }
        // Pages appliquées et journalisées de l'énumération complète en cours.
        var pages = resume?.pages ?: 0
        var resumedLinkPending = resume != null
        var restarts = 0
        var processed = 0
        while (true) {
            val page = try {
                graph.deltaPage(url)
            } catch (e: GraphResyncRequiredException) {
                // Jeton delta expiré : nouvelle énumération complète (Location, sinon delta initial).
                if (++restarts > MAX_FULL_RESTARTS) throw CloudException(CloudErrorCode.SERVER, e)
                Log.i(TAG, "Jeton delta expiré : énumération complète")
                journaling = bindDrive(version)
                run.restartFull()
                url = resyncStartUrl(e.location, initialUrl)
                pages = 0
                resumedLinkPending = false
                continue
            } catch (e: GraphHttpException) {
                // Lien de suite repris refusé (expiré, inconnu) : l'énumération repart du début.
                if (!resumedLinkPending || !isRejectedResumeLink(e.status)) throw e
                if (++restarts > MAX_FULL_RESTARTS) throw e
                Log.i(TAG, "Lien de reprise refusé (HTTP ${e.status}) : énumération complète depuis le début")
                journaling = bindDrive(version)
                run.restartFull()
                url = initialUrl
                pages = 0
                resumedLinkPending = false
                continue
            }
            resumedLinkPending = false
            writer.applyPage(page.value, run, System.currentTimeMillis())
            processed += page.value.size
            _state.update { it.copy(processedItems = processed) }
            // Page appliquée (transaction terminée) : ses éléments vus sont journalisés avant
            // l'enregistrement du lien suivant.
            val ops = run.drainPageOps()
            if (run.full && journaling) {
                journaling = journalPage(ops)
                if (journaling) pages++
            }

            val deltaLink = when (val step = deltaPageStep(run.full, run.restoredFolderWithHiddenItems, page.nextLink, page.deltaLink)) {
                DeltaPageStep.RestartFull -> {
                    // Dossier restauré : ses éléments masqués ne sont pas forcément renvoyés un par
                    // un. Le deltaLink a déjà été effacé avec la page (décision durable : le dossier
                    // étant enregistré, un passage interrompu ne pourrait plus la reprendre) ;
                    // l'énumération complète démarre sans attendre la fin du flux delta.
                    journaling = bindDrive(version)
                    if (++restarts > MAX_FULL_RESTARTS) {
                        throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Trop d'énumérations complètes"))
                    }
                    Log.i(TAG, "Dossier OneDrive restauré : énumération complète")
                    run.restartFull()
                    url = initialUrl
                    pages = 0
                    continue
                }
                is DeltaPageStep.NextPage -> {
                    url = step.url
                    if (run.full && journaling) {
                        journaling = persistResume { throttle.saveNextLink(step.url, pages) }
                        if (!journaling) persistResume { throttle.clearResume() }
                    }
                    continue
                }
                DeltaPageStep.MissingLink ->
                    throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Page delta sans lien de suite"))
                is DeltaPageStep.Finish -> step.deltaLink
            }
            val now = System.currentTimeMillis()
            writer.finishSync(run, now, folderLabels())
            cursorDao.upsert(
                SyncCursorEntity(
                    provider = ONEDRIVE_PROVIDER,
                    cursor = deltaLink,
                    version = version,
                    lastSyncAt = now,
                    lastFullSyncAt = if (run.full) now else previous?.lastFullSyncAt ?: 0L,
                    lastError = null,
                ),
            )
            _state.update { it.copy(retryNotBefore = 0L) }
            try {
                throttle.clearRetry()
            } catch (e: Exception) {
                Log.w(TAG, "Effacement de la pause Microsoft Graph impossible", e)
            }
            // Enumération terminée : plus rien à reprendre.
            if (run.full) persistResume { throttle.clearResume() }
            return
        }
    }

    /** Enumération complète reprise : passage, lien de la page suivante, pages déjà appliquées. */
    private class Resume(val run: CloudSyncRun, val nextLink: String, val pages: Int)

    /**
     * Etat de reprise utilisable pour [version] ([canResumeEnumeration]), sinon null : l'état
     * périmé ou incohérent est alors effacé par la nouvelle énumération (bindDrive).
     */
    private suspend fun loadResume(version: String): Resume? = try {
        val meta = throttle.resumeMeta()
        val link = meta.nextLink
        if (link == null || meta.version != version) {
            null
        } else {
            val journal = throttle.readSeenJournal()?.let(::replaySeenJournal)
            if (journal != null && canResumeEnumeration(meta, version, System.currentTimeMillis(), journal)) {
                Resume(CloudSyncRun.resumed(journal.seen, journal.seenFolders), link, meta.pages)
            } else {
                Log.i(TAG, "Reprise de l'énumération impossible (état périmé ou incomplet) : départ du début")
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Lecture de l'état de reprise impossible", e)
        null
    }

    /**
     * Journalise les éléments vus d'une page appliquée. Faux si la reprise devient impossible
     * pour ce passage (identifiant non journalisable, erreur d'écriture) : l'état de reprise est
     * alors effacé, un arrêt fera repartir l'énumération du début (comme avant).
     */
    private suspend fun journalPage(ops: List<SeenEntry>): Boolean {
        if (ops.any { !isJournalSafeId(it.id) }) {
            Log.w(TAG, "Identifiant OneDrive non journalisable : reprise désactivée pour ce passage")
            persistResume { throttle.clearResume() }
            return false
        }
        return persistResume { throttle.appendSeenBlock(encodeSeenBlock(ops)) }.also { ok ->
            if (!ok) persistResume { throttle.clearResume() }
        }
    }

    /** Ecriture de l'état de reprise : un échec n'interrompt jamais la synchronisation. */
    private suspend fun persistResume(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Enregistrement de l'état de reprise impossible", e)
        false
    }

    /**
     * Lie le curseur au lecteur avant une énumération complète et efface le deltaLink : un passage
     * interrompu reprendra par une énumération complète du même lecteur.
     */
    private suspend fun bindDrive(version: String): Boolean {
        val previous = cursorDao.get(ONEDRIVE_PROVIDER)
        cursorDao.upsert(
            SyncCursorEntity(
                provider = ONEDRIVE_PROVIDER,
                cursor = null,
                version = version,
                lastSyncAt = previous?.lastSyncAt ?: 0L,
                lastFullSyncAt = previous?.lastFullSyncAt ?: 0L,
                lastError = previous?.lastError,
            ),
        )
        // Nouvelle énumération : ancien état de reprise et journal effacés. Vrai si la reprise
        // de celle-ci est possible.
        return persistResume { throttle.beginEnumeration(version, System.currentTimeMillis()) }
    }

    private suspend fun onSyncFailed(error: Exception) {
        val code = when (error) {
            is CloudException -> error.code
            else -> CloudErrorCode.UNKNOWN
        }
        if (error is CloudThrottledException) {
            val until = System.currentTimeMillis() + error.retryAfterMs.coerceAtLeast(BACKOFF_CAP_MS)
            _state.update { it.copy(retryNotBefore = until) }
            try {
                withContext(NonCancellable) { throttle.setRetryNotBefore(until) }
            } catch (e: Exception) {
                Log.w(TAG, "Enregistrement de la pause Microsoft Graph impossible", e)
            }
        }
        if (error is CloudException) {
            Log.w(TAG, "Synchronisation OneDrive interrompue : $code")
        } else {
            Log.e(TAG, "Echec de la synchronisation OneDrive", error)
        }
        // L'erreur est conservée avec le curseur (inchangé) ; sans curseur, seulement en mémoire.
        try {
            withContext(NonCancellable) {
                cursorDao.get(ONEDRIVE_PROVIDER)?.let { cursorDao.upsert(it.copy(lastError = code.name)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enregistrement de l'erreur de synchronisation impossible", e)
        }
        _state.update { it.copy(lastError = code) }
    }

    /** Relit le curseur et le nombre d'éléments (base locale). */
    private suspend fun reloadState() {
        try {
            val cursor = cursorDao.get(ONEDRIVE_PROVIDER)
            val count = writer.visibleCount()
            val pausedUntil = restoredRetryNotBefore(throttle.retryNotBefore(), System.currentTimeMillis())
            val mismatch = throttle.accountMismatch()
            stateLoaded = true
            _state.update {
                it.copy(
                    itemCount = count,
                    // Pause Graph enregistrée (survit au redémarrage du processus et à exclusive()).
                    retryNotBefore = maxOf(it.retryNotBefore, pausedUntil),
                    // Autre compte détecté (survit au redémarrage du processus, effacé par la purge).
                    accountMismatch = it.accountMismatch || mismatch,
                    lastSyncAt = cursor?.lastSyncAt ?: 0L,
                    lastFullSyncAt = cursor?.lastFullSyncAt ?: 0L,
                    // Avec un curseur, l'erreur enregistrée fait foi ; sans curseur, seule la mémoire la porte.
                    lastError = if (cursor != null) cursor.lastError?.let(::parseErrorCode) else it.lastError,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lecture de l'état de synchronisation OneDrive impossible", e)
        }
    }

    private fun folderLabels() = CloudFolderLabels(
        root = context.getString(R.string.common_onedrive),
        unnamed = context.getString(R.string.data_folder_unnamed),
    )

    private companion object {
        const val TAG = "OneDriveSync"

        /** Enumérations complètes relancées au plus par passage (410, dossier restauré). */
        const val MAX_FULL_RESTARTS = 2

        fun parseErrorCode(name: String): CloudErrorCode =
            CloudErrorCode.entries.firstOrNull { it.name == name } ?: CloudErrorCode.UNKNOWN
    }
}
