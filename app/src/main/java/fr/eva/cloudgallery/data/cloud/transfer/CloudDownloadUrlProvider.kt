package fr.eva.cloudgallery.data.cloud.transfer

import android.os.SystemClock
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudDownloadInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary download URLs of OneDrive items (`@microsoft.graph.downloadUrl`).
 *
 * - Kept **in memory only** (never persisted or logged), for at most [MAX_ENTRIES] items, and
 *   reused for at most [DOWNLOAD_URL_TTL_MS].
 * - Every request goes through [CloudAccessGuard]: never a URL for a signed-out account or one
 *   different from the index's.
 * - [clear] (sign-out, purge, account change) forgets everything; a request in flight at that
 *   moment fails instead of caching a URL of the old account again.
 */
@Singleton
class CloudDownloadUrlProvider internal constructor(
    private val fetch: suspend (itemId: String) -> CloudDownloadInfo,
    private val ensureAccess: suspend () -> Unit,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) {

    @Inject
    constructor(
        graph: dagger.Lazy<GraphApi>,
        guard: CloudAccessGuard,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        fetch = { itemId -> graph.get().downloadInfo(itemId) },
        ensureAccess = guard::ensureSignedIn,
        clock = SystemClock::elapsedRealtime,
        io = io,
    )

    private class Entry(val info: CloudDownloadInfo, val fetchedAtMs: Long)

    private val lock = Any()

    /** Access order: the least recently used entry is evicted first. */
    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }

    /** Incremented by [clear]: a URL obtained before is never cached. */
    private var epoch = 0L

    /**
     * Download URL of [itemId]: reused if recent, otherwise (or with [forceRefresh]) requested
     * again from Microsoft Graph. Throws the exceptions of [CloudAccessGuard] and of Graph
     * ([fr.eva.cloudgallery.domain.model.CloudException] and subclasses).
     */
    suspend fun get(itemId: String, forceRefresh: Boolean = false): CloudDownloadInfo = withContext(io) {
        ensureAccess()
        val startEpoch = synchronized(lock) {
            if (!forceRefresh) {
                val cached = entries[itemId]
                if (cached != null && isUrlFresh(cached.fetchedAtMs, clock())) return@withContext cached.info
            }
            epoch
        }
        val info = fetch(itemId)
        synchronized(lock) {
            // OneDrive data cleared during the request: the URL may belong to the old account.
            if (epoch != startEpoch) throw CloudAuthRequiredException()
            entries[itemId] = Entry(info, clock())
        }
        info
    }

    /** Forgets the URL of [itemId] (rejected by the download host). Callable from any thread. */
    fun invalidate(itemId: String) {
        synchronized(lock) { entries.remove(itemId) }
    }

    /** Forgets all URLs (sign-out, OneDrive data purge, account change). */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            epoch++
        }
    }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}
