package fr.eva.cloudgallery.data.search

import android.os.SystemClock
import android.util.Log
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.SearchDao
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the `media_fts` search index up to date from the database, without depending on the
 * syncs: any write to `media`, `media_source`, `album` or `album_media` (MediaStore indexing,
 * OneDrive sync, merge, purge, album rename…) triggers, after [REFRESH_DEBOUNCE_MS] without a
 * new write, an incremental update ([SearchDao.refreshIndex]). The first pass, at startup,
 * fills the index after the v6 migration or catches it up if it was interrupted.
 *
 * Cost: each pass recomputes the labels of all media to compare them with the index (indexed
 * subqueries), then only rewrites the entries that changed. For 100,000 media, on the order of
 * one to a few seconds on the I/O dispatcher, once at the end of a sync; the first fill also
 * writes the 100,000 entries. A sync that keeps writing delays the update until it ends (a
 * search in the meantime finds the previous state).
 */
@Singleton
class SearchIndexer @Inject constructor(
    private val db: AppDatabase,
    private val dao: SearchDao,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    /** Watches the tables for the lifetime of the process (Application.onCreate). Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            db.invalidationTracker.createFlow(*WATCHED_TABLES)
                .debounce(REFRESH_DEBOUNCE_MS)
                .conflate()
                .collect { refresh() }
        }
    }

    private suspend fun refresh() {
        try {
            val start = SystemClock.elapsedRealtime()
            val removed = dao.refreshIndex()
            val ms = SystemClock.elapsedRealtime() - start
            if (removed > 0 || ms > SLOW_REFRESH_MS) Log.i(TAG, "Search index up to date: $removed entries removed, $ms ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retried on the next write (or the next startup): search keeps the previous state.
            Log.w(TAG, "Search index update failed", e)
        }
    }

    private companion object {
        const val TAG = "SearchIndexer"
        const val REFRESH_DEBOUNCE_MS = 2_000L
        const val SLOW_REFRESH_MS = 500L
        val WATCHED_TABLES = arrayOf("media", "media_source", "album", "album_media")
    }
}
