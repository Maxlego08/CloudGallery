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
 * URL de téléchargement temporaires des éléments OneDrive (`@microsoft.graph.downloadUrl`).
 *
 * - Gardées **en mémoire uniquement** (jamais persistées ni journalisées), au plus [MAX_ENTRIES]
 *   éléments, et réutilisées pendant [DOWNLOAD_URL_TTL_MS] au plus.
 * - Chaque demande passe par [CloudAccessGuard] : jamais d'URL pour un compte déconnecté ou
 *   différent de celui de l'index.
 * - [clear] (déconnexion, purge, changement de compte) oublie tout ; une demande en cours à ce
 *   moment-là échoue au lieu de remettre en cache une URL de l'ancien compte.
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

    /** Ordre d'accès : l'entrée la moins récemment utilisée est retirée en premier. */
    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }

    /** Incrémenté par [clear] : une URL obtenue avant n'est jamais mise en cache. */
    private var epoch = 0L

    /**
     * URL de téléchargement de [itemId] : réutilisée si elle est récente, sinon (ou avec
     * [forceRefresh]) redemandée à Microsoft Graph. Lève les exceptions de [CloudAccessGuard] et
     * de Graph ([fr.eva.cloudgallery.domain.model.CloudException] et dérivées).
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
            // Données OneDrive vidées pendant la requête : l'URL appartient peut-être à l'ancien compte.
            if (epoch != startEpoch) throw CloudAuthRequiredException()
            entries[itemId] = Entry(info, clock())
        }
        info
    }

    /** Oublie l'URL de [itemId] (refusée par l'hôte de téléchargement). Appelable de tout thread. */
    fun invalidate(itemId: String) {
        synchronized(lock) { entries.remove(itemId) }
    }

    /** Oublie toutes les URL (déconnexion, purge des données OneDrive, changement de compte). */
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
