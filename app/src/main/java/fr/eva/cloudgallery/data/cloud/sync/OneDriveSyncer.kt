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
 * OneDrive -> local index sync through the Microsoft Graph delta query (whole drive,
 * read-only).
 *
 * - Each page is applied as soon as it is received (writes idempotent by item identifier);
 *   the deltaLink is only saved at the end: an interrupted pass resumes from the previous
 *   cursor.
 * - The cursor is bound to the drive (drive.id) from the start of a full enumeration: another
 *   account never purges the index without the user's confirmation.
 * - 410 (expired token) or restored folder: full enumeration, missing items are hidden. The
 *   deltaLink is cleared as soon as the decision is made (an interrupted pass resumes with a
 *   full enumeration, never from the old cursor).
 * - Graph throttling: the end of the pause is saved ([CloudSyncThrottleStore]) and honored
 *   even after a process restart.
 * - Only for a signed-in account; requests coalesced, one pass at a time.
 * - Logs: no token, no deltaLink, no account name.
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

    /** Only one pass (or one exclusive operation: sign-out, purge) at a time. */
    private val mutex = Mutex()

    /** Parent of launched passes, so they can be interrupted before a purge. */
    private val runs = SupervisorJob(scope.coroutineContext.job)

    /** Request number: a pass started after a request satisfies it (coalescing). */
    private val requestCounter = AtomicLong(0)

    @Volatile
    private var lastStartedRequest = 0L

    @Volatile
    private var stateLoaded = false

    init {
        if (feature.enabled) {
            scope.launch(io) { reloadState() }
            // Successful sign-in or re-sign-in: first pass without waiting for the foreground.
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
        // Child of [runs]: interrupted like the other passes by exclusive(). A cancelled caller
        // (work stopped by WorkManager: network lost) also stops the pass.
        val run = scope.async(runs + io) { runRequest(ticket, reason) }
        return try {
            run.await()
        } catch (e: CancellationException) {
            run.cancel()
            throw e
        }
    }

    /** True if a pass queried OneDrive for this request (or a pass started after it). */
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
                // OneDrive data possibly removed: state reset from scratch. The Graph pause is
                // read back (kept unless the account's data has been purged).
                _state.value = CloudSyncState()
                reloadState()
            }
        }
    }

    /** True if a pass actually queried OneDrive (even if it failed). */
    private suspend fun runGuarded(reason: CloudSyncReason): Boolean {
        if (!stateLoaded) reloadState()
        // Account state not read yet (process startup) or previous read failed.
        auth.refreshIfNeeded()
        val signedIn = auth.state.value as? CloudAccountState.SignedIn ?: return false
        val accountId = signedIn.account.id
        val now = System.currentTimeMillis()
        val current = _state.value
        if (now < current.retryNotBefore) return false
        // Account not verified yet (another account signed in): pass without waiting for the
        // interval, access to content stays blocked until then.
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
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("OneDrive drive without an identifier"))
        val previous = cursorDao.get(ONEDRIVE_PROVIDER)
        val start = planSyncStart(
            storedLink = previous?.cursor,
            storedVersion = previous?.version,
            driveId = driveId,
            selectVersion = DELTA_SELECT_VERSION,
            hasIndexedData = writer.hasIndexedData(),
        )
        if (start is SyncStart.AccountMismatch) {
            Log.w(TAG, "Signed-in OneDrive differs from the index's: sync suspended")
            _state.update { it.copy(accountMismatch = true) }
            // Persisted: access stays blocked after a restart, until the purge.
            withContext(NonCancellable) { throttle.setAccountMismatch() }
            return
        }
        _state.update { it.copy(accountMismatch = false) }
        // Drive matches the index: this account is verified (before the enumeration, which may be long).
        accountId?.let { throttle.setVerified(it) }

        val version = cloudCursorVersion(driveId, DELTA_SELECT_VERSION)
        val initialUrl = graph.initialDeltaUrl()
        // Interrupted full enumeration (process stopped, network error): resume from the last
        // applied page, with the seen items replayed from the journal.
        val resume = if (start is SyncStart.Full) loadResume(version) else null
        val run = resume?.run ?: CloudSyncRun(full = start is SyncStart.Full)
        var url = resume?.nextLink ?: (start as? SyncStart.Incremental)?.deltaLink ?: initialUrl
        // Resume journal usable (disabled for this pass after a write error).
        var journaling = when {
            resume != null -> {
                Log.i(TAG, "Resuming full enumeration after ${resume.pages} pages")
                true
            }
            run.full -> bindDrive(version)
            else -> false
        }
        // Pages applied and journaled for the current full enumeration.
        var pages = resume?.pages ?: 0
        var resumedLinkPending = resume != null
        var restarts = 0
        var processed = 0
        while (true) {
            val page = try {
                graph.deltaPage(url)
            } catch (e: GraphResyncRequiredException) {
                // Delta token expired: new full enumeration (Location, else initial delta).
                if (++restarts > MAX_FULL_RESTARTS) throw CloudException(CloudErrorCode.SERVER, e)
                Log.i(TAG, "Delta token expired: full enumeration")
                journaling = bindDrive(version)
                run.restartFull()
                url = resyncStartUrl(e.location, initialUrl)
                pages = 0
                resumedLinkPending = false
                continue
            } catch (e: GraphHttpException) {
                // Resumed continuation link rejected (expired, unknown): the enumeration restarts from the beginning.
                if (!resumedLinkPending || !isRejectedResumeLink(e.status)) throw e
                if (++restarts > MAX_FULL_RESTARTS) throw e
                Log.i(TAG, "Resume link rejected (HTTP ${e.status}): full enumeration from the beginning")
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
            // Page applied (transaction finished): its seen items are journaled before the next
            // link is saved.
            val ops = run.drainPageOps()
            if (run.full && journaling) {
                journaling = journalPage(ops)
                if (journaling) pages++
            }

            val deltaLink = when (val step = deltaPageStep(run.full, run.restoredFolderWithHiddenItems, page.nextLink, page.deltaLink)) {
                DeltaPageStep.RestartFull -> {
                    // Restored folder: its hidden items are not necessarily returned one by one.
                    // The deltaLink was already cleared with the page (durable decision: with the
                    // folder saved, an interrupted pass could no longer make it again); the full
                    // enumeration starts without waiting for the end of the delta feed.
                    journaling = bindDrive(version)
                    if (++restarts > MAX_FULL_RESTARTS) {
                        throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Too many full enumerations"))
                    }
                    Log.i(TAG, "OneDrive folder restored: full enumeration")
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
                    throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Delta page without a continuation link"))
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
                Log.w(TAG, "Failed to clear the Microsoft Graph pause", e)
            }
            // Enumeration finished: nothing left to resume.
            if (run.full) persistResume { throttle.clearResume() }
            return
        }
    }

    /** Resumed full enumeration: pass, next page link, pages already applied. */
    private class Resume(val run: CloudSyncRun, val nextLink: String, val pages: Int)

    /**
     * Usable resume state for [version] ([canResumeEnumeration]), otherwise null: a stale or
     * inconsistent state is then cleared by the new enumeration (bindDrive).
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
                Log.i(TAG, "Cannot resume enumeration (stale or incomplete state): starting from the beginning")
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read the resume state", e)
        null
    }

    /**
     * Journals the seen items of an applied page. False if resuming becomes impossible for this
     * pass (identifier that cannot be journaled, write error): the resume state is then cleared,
     * and a stop will restart the enumeration from the beginning (as before).
     */
    private suspend fun journalPage(ops: List<SeenEntry>): Boolean {
        if (ops.any { !isJournalSafeId(it.id) }) {
            Log.w(TAG, "OneDrive identifier cannot be journaled: resume disabled for this pass")
            persistResume { throttle.clearResume() }
            return false
        }
        return persistResume { throttle.appendSeenBlock(encodeSeenBlock(ops)) }.also { ok ->
            if (!ok) persistResume { throttle.clearResume() }
        }
    }

    /** Writes the resume state: a failure never interrupts the sync. */
    private suspend fun persistResume(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to save the resume state", e)
        false
    }

    /**
     * Binds the cursor to the drive before a full enumeration and clears the deltaLink: an
     * interrupted pass will resume with a full enumeration of the same drive.
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
        // New enumeration: old resume state and journal cleared. True if this one can be
        // resumed.
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
                Log.w(TAG, "Failed to save the Microsoft Graph pause", e)
            }
        }
        if (error is CloudException) {
            Log.w(TAG, "OneDrive sync interrupted: $code")
        } else {
            Log.e(TAG, "OneDrive sync failed", error)
        }
        // The error is kept with the (unchanged) cursor; without a cursor, only in memory.
        try {
            withContext(NonCancellable) {
                cursorDao.get(ONEDRIVE_PROVIDER)?.let { cursorDao.upsert(it.copy(lastError = code.name)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save the sync error", e)
        }
        _state.update { it.copy(lastError = code) }
    }

    /** Reads back the cursor and the item count (local database). */
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
                    // Saved Graph pause (survives a process restart and exclusive()).
                    retryNotBefore = maxOf(it.retryNotBefore, pausedUntil),
                    // Other account detected (survives a process restart, cleared by the purge).
                    accountMismatch = it.accountMismatch || mismatch,
                    lastSyncAt = cursor?.lastSyncAt ?: 0L,
                    lastFullSyncAt = cursor?.lastFullSyncAt ?: 0L,
                    // With a cursor, the saved error is authoritative; without one, only memory holds it.
                    lastError = if (cursor != null) cursor.lastError?.let(::parseErrorCode) else it.lastError,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read the OneDrive sync state", e)
        }
    }

    private fun folderLabels() = CloudFolderLabels(
        root = context.getString(R.string.common_onedrive),
        unnamed = context.getString(R.string.data_folder_unnamed),
    )

    private companion object {
        const val TAG = "OneDriveSync"

        /** Maximum full enumerations restarted per pass (410, restored folder). */
        const val MAX_FULL_RESTARTS = 2

        fun parseErrorCode(name: String): CloudErrorCode =
            CloudErrorCode.entries.firstOrNull { it.name == name } ?: CloudErrorCode.UNKNOWN
    }
}
