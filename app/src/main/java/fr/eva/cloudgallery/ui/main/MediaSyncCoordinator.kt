package fr.eva.cloudgallery.ui.main

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.model.MediaAccess
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.ui.permissions.MediaPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single trigger point for local indexing: when moving to the foreground, when
 * permission is granted, or on demand (Menu / Settings). Does nothing without media
 * access. Syncs do not overlap; a full request during an incremental
 * sync is chained after it.
 *
 * OneDrive sync is requested in parallel (foreground, "Sync
 * now"), independently of local media access; it only runs for a
 * signed-in account and limits itself to the foreground.
 *
 * On the process's first move to the foreground, the temporary cache of OneDrive originals
 * is cleaned up (older than 7 days, 1 GB limit, abandoned partial files).
 *
 * Offline copies: every time the app moves to the foreground and at the end of every OneDrive
 * sync (new versions to download), the queue is restarted if needed. The download
 * work can only be scheduled while the app is visible.
 */
@Singleton
class MediaSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexer: dagger.Lazy<LocalMediaIndexer>,
    private val cloudSync: dagger.Lazy<CloudMediaSyncer>,
    private val originals: dagger.Lazy<CloudOriginalStore>,
    private val offline: dagger.Lazy<OfflineRepository>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var syncJob: Job? = null
    private val startupCleanupDone = AtomicBoolean(false)

    fun hasMediaAccess(): Boolean = MediaPermissions.currentAccess(context) != MediaAccess.NONE

    /** App back in the foreground: incremental sync (local and OneDrive). */
    fun onForeground() {
        syncIfAllowed(full = false)
        cloudSync.get().requestSync(CloudSyncReason.FOREGROUND)
        if (startupCleanupDone.compareAndSet(false, true)) {
            scope.launch(io) {
                try {
                    originals.get().trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to clean up OneDrive originals", e)
                }
            }
            // End of a OneDrive sync: modified versions to download again.
            scope.launch(io) {
                cloudSync.get().state
                    .map { it.running }
                    .distinctUntilChanged()
                    .drop(1)
                    .filter { running -> !running }
                    .collect { resumeOffline() }
            }
        }
        scope.launch(io) { resumeOffline() }
    }

    private suspend fun resumeOffline() {
        try {
            offline.get().resumeIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resume offline copies", e)
        }
    }

    /** Permission granted or extended (partial -> full, new items chosen). */
    fun onAccessChanged() = syncIfAllowed(full = false)

    /**
     * Immediate sync. The full re-read ([full]) only concerns local media: the
     * OneDrive sync is always incremental (automatic full enumeration if necessary).
     */
    fun syncNow(full: Boolean) {
        syncIfAllowed(full)
        if (!full) cloudSync.get().requestSync(CloudSyncReason.MANUAL)
    }

    @Synchronized
    private fun syncIfAllowed(full: Boolean) {
        if (!hasMediaAccess()) return
        val idx = indexer.get()
        idx.startObserving()
        val previous = syncJob
        if (previous?.isActive == true && !full) return
        syncJob = scope.launch(io) {
            previous?.join()
            try {
                idx.sync(full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The indexer publishes the error to IndexState itself; we only log it.
                Log.w(TAG, "Local sync failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "MediaSync"
    }
}
