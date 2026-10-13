package fr.eva.cloudgallery.domain.repository

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.paging.PagingData
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudProfile
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.DeletedOnOneDriveCopy
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineQueueState
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.model.PrecacheEstimate
import fr.eva.cloudgallery.domain.model.PrecacheState
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.domain.model.SpaceSaverList
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Read access to the unified library (local + cloud), regardless of the source. */
interface MediaRepository {
    /** Media of the context, from newest to oldest (dateTaken DESC, id DESC). */
    fun pagedMedia(context: MediaContext): Flow<PagingData<MediaSummary>> = pagedMedia(context, 0)

    /**
     * Like [pagedMedia], with the first page starting at position [initialOffset] of the sorted
     * list (fast-scroll jump); the previous pages load when scrolling back up.
     */
    fun pagedMedia(context: MediaContext, initialOffset: Int): Flow<PagingData<MediaSummary>>

    /** Dates taken of the context, in the same order as [pagedMedia] (fast scroll). */
    fun observeDates(context: MediaContext): Flow<List<Long>>

    /** IDs of the context, in the same order as [pagedMedia]. Used by the viewer. */
    fun observeMediaIds(context: MediaContext): Flow<List<Long>>

    fun observeMedia(id: Long): Flow<MediaDetails?>

    suspend fun getMedia(id: Long): MediaDetails?

    suspend fun getSummaries(ids: List<Long>): List<MediaSummary>

    /** Number of media among [ids] that have a local file (OneDrive-only items are excluded). */
    suspend fun countWithLocalFile(ids: Collection<Long>): Int

    /** Number of shareable media among [ids] (local file, offline copy or OneDrive item). */
    suspend fun countShareable(ids: Collection<Long>): Int

    /** Visible OneDrive items without an offline copy among [ids] ("Keep offline"). */
    suspend fun countKeepableOffline(ids: Collection<Long>): Int

    /** Media among [ids] that have an offline copy ("Free up space"). */
    suspend fun countWithOfflineCopy(ids: Collection<Long>): Int

    /** IDs of the context whose date taken is in [startMs, endMs[, in the order of [pagedMedia]. */
    suspend fun idsInPeriod(context: MediaContext, startMs: Long, endMs: Long): List<Long>

    /**
     * IDs of the context located between two media of the list (bounds included, in any
     * order), including those not yet loaded by paging.
     */
    suspend fun idsBetween(context: MediaContext, a: MediaSummary, b: MediaSummary): List<Long>

    /** Number of media in the context. */
    fun observeCount(context: MediaContext): Flow<Int>

    /** Finds (or immediately indexes) the media item matching a MediaStore URI. */
    suspend fun findByContentUri(uri: Uri): Long?
}

interface FavoritesRepository {
    suspend fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean)
    suspend fun toggle(mediaId: Long): Boolean
}

interface AlbumRepository {
    fun observeAlbums(sort: AlbumSort): Flow<List<AlbumSummary>>
    fun observeAlbum(albumId: Long): Flow<AlbumSummary?>
    suspend fun createVirtualAlbum(name: String): Long
    suspend fun renameAlbum(albumId: Long, name: String)

    /** Deletes only a virtual album (the referenced media are not touched). */
    suspend fun deleteVirtualAlbum(albumId: Long)
    suspend fun addToAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun removeFromAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun setPinned(albumId: Long, pinned: Boolean)

    /** All albums, hidden ones included, sorted by name ("Hide albums" screen). */
    fun observeAllAlbums(): Flow<List<AlbumSummary>>
    suspend fun setVisibility(albumId: Long, visibility: AlbumVisibility)

    /**
     * Web link of the OneDrive folder of a OneDrive album (opened in the OneDrive app or the
     * browser); null for any other album. Graph request: [fr.eva.cloudgallery.domain.model.CloudException] when offline.
     */
    suspend fun oneDriveFolderUrl(albumId: Long): String?
}

/** Recent searches (on the device only), most recent first. */
interface SearchHistoryRepository {
    val recent: Flow<List<String>>
    suspend fun add(query: String)
    suspend fun remove(query: String)
    suspend fun clear()
}

/** Incremental indexing from MediaStore into the Room database. */
interface LocalMediaIndexer {
    val state: StateFlow<IndexState>

    /** Syncs MediaStore -> Room. [full] forces a complete re-read. */
    suspend fun sync(full: Boolean = false)

    /** Starts observing MediaStore changes (ContentObserver). Idempotent. */
    fun startObserving()
}

/**
 * Write operations on local files. Those that require a system confirmation return an
 * [IntentSender] to be launched by the UI (StartIntentSenderForResult).
 */
interface LocalMediaWriter {
    /** Android trash (recoverable) or permanent deletion depending on [toTrash]. */
    fun createDeleteRequest(uris: List<Uri>, toTrash: Boolean): IntentSender
    fun createWriteRequest(uris: List<Uri>): IntentSender

    /** True if [mimeType] allows a lossless rotation (editable EXIF orientation). */
    fun canRotate(mimeType: String?): Boolean

    /** Rotates an image by [degrees] (multiple of 90). Requires granted write access. */
    suspend fun rotateImage(uri: Uri, degrees: Int): Result<Unit>

    /** Saves a cropped copy (normalized 0..1 rectangle) and returns the created URI. */
    suspend fun saveCroppedCopy(
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
    ): Result<Uri>

    /** Copies local files to a relative folder (e.g. "Pictures/Holidays/"). */
    suspend fun copyToFolder(uris: List<Uri>, relativePath: String): Result<Int>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setPhotosZoomLevel(level: Int)
    suspend fun setAlbumZoomLevel(level: Int)
    suspend fun setAlbumSort(sort: AlbumSort)
    suspend fun setShowVideoDuration(show: Boolean)
    suspend fun setAutoPlayVideos(enabled: Boolean)
    suspend fun setConfirmBeforeDelete(enabled: Boolean)
    suspend fun setUseTrash(enabled: Boolean)
    suspend fun setShowCloudInPhotos(enabled: Boolean)
    suspend fun setLoadOriginalsOnMobileData(enabled: Boolean)
    suspend fun setOfflineWifiOnly(enabled: Boolean)
    suspend fun setOfflineNotificationsAsked(asked: Boolean)

    /** [bytes]: one of the values of `ORIGINALS_CACHE_CHOICES`. */
    suspend fun setOriginalsCacheMaxBytes(bytes: Long)

    /** [bytes]: one of the values of `THUMBNAIL_CACHE_CHOICES` (applied at the next start). */
    suspend fun setThumbnailCacheMaxBytes(bytes: Long)
    suspend fun setBackgroundSyncInterval(interval: BackgroundSyncInterval)
    suspend fun setBackgroundSyncWifiOnly(enabled: Boolean)
    suspend fun setOneDriveOnlyChosen(chosen: Boolean)
    suspend fun setPrecacheKeepUpdated(enabled: Boolean)
}

/** Space used by the app and clearing of the unpinned cache (Storage page). */
interface StorageRepository {
    /** Measures on disk (I/O dispatcher). */
    suspend fun usage(): StorageUsage

    /**
     * Clears the unpinned cache: OneDrive thumbnails and previews, temporary originals (except
     * downloads in progress). Offline copies are never touched.
     */
    suspend fun clearCache()

    /** New originals cap (already saved): cleanup right away (I/O dispatcher). */
    suspend fun applyOriginalsCap()
}

/**
 * "Cache all of OneDrive": grid thumbnails and viewer previews of all OneDrive items,
 * downloaded in the background (WorkManager) and kept outside the thumbnail cache cap.
 */
interface OneDrivePrecacheRepository {
    /** State of the current pass or of the last pass. */
    val state: Flow<PrecacheState>

    /** Items concerned, remaining to cache, estimated size and free space (I/O dispatcher). */
    suspend fun estimate(): PrecacheEstimate

    /** Starts (or lets continue) the pass; resumes without re-downloading what is already cached. */
    fun start()

    /** Stops the current pass; what is already cached is kept. */
    fun cancel()
}

/**
 * OneDrive account (single-account mode) as seen by the UI: sign-in, sign-out and local cloud
 * data. Interactive operations do not suspend the caller: their outcome is published in
 * [signInOutcomes] and the state in [state].
 */
interface CloudAccountRepository {
    /** True if this build ships a valid MSAL configuration (otherwise everything is hidden). */
    val isAvailable: Boolean

    val state: StateFlow<CloudAccountState>

    /** True during an interactive sign-in (Microsoft page open). */
    val signInRunning: StateFlow<Boolean>

    /** Outcomes of interactive sign-ins, one per attempt. */
    val signInOutcomes: Flow<SignInOutcome>

    /** Re-reads the account persisted by MSAL (I/O thread). */
    suspend fun refresh()

    /** Opens the Microsoft sign-in page from [activity]. */
    fun signIn(activity: Activity)

    /** Re-sign-in of the current account after the session expired. */
    fun reauthenticate(activity: Activity)

    /**
     * Signs the account out, then removes all OneDrive data from the local index (media,
     * favorites and the associated album additions, OneDrive albums). Files on OneDrive and on
     * the device are never modified. If sign-out fails, the data is kept.
     */
    suspend fun signOutAndPurge(): Result<Unit>

    /** Removes the OneDrive data left in the index after a finished session. */
    suspend fun purgeLeftoverData(): Result<Unit>

    /** State of the OneDrive sync. */
    val syncState: StateFlow<CloudSyncState>

    /** Immediate (incremental) OneDrive sync. */
    fun syncNow()

    /** Name, address and quota of the signed-in account (Microsoft Graph). */
    suspend fun loadProfile(): Result<CloudProfile>

    /**
     * The signed-in OneDrive differs from the one of the index data ([CloudSyncState.accountMismatch]):
     * after the user confirms, removes the old account's data then syncs the signed-in
     * account. Files on OneDrive and on the device are never modified.
     */
    suspend fun replaceMismatchedData(): Result<Unit>
}

/**
 * OneDrive -> local index sync (read-only on the OneDrive side). Only runs for a signed-in
 * account; concurrent requests are coalesced.
 */
interface CloudMediaSyncer {
    val state: StateFlow<CloudSyncState>

    /** Requests a sync (without waiting). No effect if OneDrive is unavailable. */
    fun requestSync(reason: CloudSyncReason)

    /**
     * Sync whose completion is awaited (background work): coalesced with the pending requests
     * like [requestSync]. True if OneDrive was queried (even on failure).
     */
    suspend fun syncAndWait(reason: CloudSyncReason): Boolean

    /**
     * The MSAL account [accountId] may access the index data: no other drive detected, and the
     * account was verified by the last pass (or no account verified yet). Values stored outside
     * memory: true right from process start.
     */
    suspend fun accountAllowed(accountId: String?): Boolean

    /**
     * Interrupts the current sync and runs [block] mutually exclusive with any sync (sign-out,
     * purge). The state is re-read afterwards.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T
}

/** Microsoft Graph access token for the signed-in account. */
interface CloudTokenProvider {
    /**
     * Valid token, silently renewed if needed (I/O thread).
     * @throws CloudAuthRequiredException if an interactive sign-in is required.
     * @throws CloudException for other failures (network, service...).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String

    /**
     * Microsoft Graph rejected (401) [token], obtained just before by a forced renewal: access
     * was revoked on the service side, the account switches to sign-in required. No effect if
     * [token] is no longer the current token (sign-out, another account in the meantime).
     */
    suspend fun onTokenRejected(token: String)
}

/**
 * Offline copies of OneDrive items ("Keep offline" / "Free up"): private app files, downloaded
 * in the background by a task started by the user. Neither MediaStore nor the user's files are
 * ever touched.
 */
interface OfflineRepository {
    /** Download queue and copies present. */
    val queue: Flow<OfflineQueueState>

    /** Offline state of a media item (status, progress, error). */
    fun observeItem(mediaId: Long): Flow<OfflineItemState>

    /**
     * Queues the OneDrive items of [mediaIds] that have no offline copy. A large batch (more
     * than 200 items or 1 GB) first asks for confirmation, unless [confirmedLargeBatch].
     */
    suspend fun keepOffline(mediaIds: Collection<Long>, confirmedLargeBatch: Boolean = false): KeepOfflineResult

    /**
     * Offline copies among [mediaIds] (visible or hidden) that are the last copy of the item:
     * neither on OneDrive (deleted) nor on the device. Throws if the count is impossible:
     * "Free up" must then delete nothing without confirmation.
     */
    suspend fun lastCopiesAmong(mediaIds: Collection<Long>): Int

    /**
     * Deletes the offline copies of [mediaIds] (and their downloads). An item that only existed
     * as an offline copy disappears. Returns the number of copies deleted.
     */
    suspend fun release(mediaIds: Collection<Long>): Int

    /** Cancels the downloads of [mediaIds] (a copy already present is kept). */
    suspend fun cancel(mediaIds: Collection<Long>)

    /** Cancels all downloads ("Cancel" button of the notification, Account screen). */
    suspend fun cancelAll()

    /** Resumes paused or failed downloads. */
    suspend fun resume()

    /**
     * "Download now": the tasks queued at this moment ignore the "Wi-Fi only" setting (the
     * following ones respect it again).
     */
    suspend fun downloadNow()

    /** "Wi-Fi only" setting changed: current or scheduled work adjusted right away. */
    suspend fun onNetworkPolicyChanged()

    /** Back to the foreground: resume after a process kill, cleanup, restart of the queue. */
    suspend fun resumeIfNeeded()

    /** Copies of items deleted from OneDrive awaiting a decision (grace period elapsed). */
    val deletedOnOneDrive: Flow<List<DeletedOnOneDriveCopy>>

    /**
     * "Delete the copies" confirmed for [mediaIds] (the list shown to the user): removes these
     * copies and their items, only for those still awaiting a decision. Returns the number
     * actually processed.
     */
    suspend fun deleteCopiesOfDeletedItems(mediaIds: Collection<Long>): Int

    /**
     * "Keep them" for [mediaIds] (the list shown): these copies remain, visible, as the only
     * copies. Only for those still awaiting a decision; returns their number.
     */
    suspend fun keepCopiesOfDeletedItems(mediaIds: Collection<Long>): Int

    /** Offline copies deleted by a sign-out or a purge, including last copies. */
    suspend fun purgeImpact(): OfflinePurgeImpact
}

/** Offline copies removed by a purge of OneDrive data ([lastCopies]: only remaining copies). */
data class OfflinePurgeImpact(val copies: Int, val lastCopies: Int)

interface PlaybackPositionRepository {
    suspend fun get(mediaId: Long): Long
    suspend fun save(mediaId: Long, positionMs: Long, durationMs: Long)
    suspend fun clear(mediaId: Long)
}

/**
 * Space saver: local copies already on OneDrive (identical hash and size), never deleted
 * without review, confirmation and system dialog; always to the trash.
 */
interface SpaceSaverRepository {
    /** Copies offered (I/O dispatcher). */
    suspend fun candidates(): SpaceSaverList

    /** Reviewed copies still deletable, re-read from the database right before the request. */
    suspend fun revalidate(reviewed: List<SpaceSaverCandidate>): List<SpaceSaverCandidate>
}
