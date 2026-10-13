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
 * Tenue à jour de l'index de recherche `media_fts` d'après la base, sans dépendre des
 * synchronisations : toute écriture dans `media`, `media_source`, `album` ou `album_media`
 * (indexation MediaStore, synchro OneDrive, fusion, purge, renommage d'album…) relance, après
 * [REFRESH_DEBOUNCE_MS] sans nouvelle écriture, une mise à jour incrémentale
 * ([SearchDao.refreshIndex]). Le premier passage, au démarrage, remplit l'index après la
 * migration v6 ou le rattrape s'il a été interrompu.
 *
 * Coût : chaque passage recalcule les libellés de tous les médias pour les comparer à l'index
 * (sous-requêtes indexées), puis ne réécrit que les entrées changées. Pour 100 000 médias, de
 * l'ordre d'une à quelques secondes sur le dispatcher d'E/S, une fois en fin de synchro ; le
 * premier remplissage écrit en plus les 100 000 entrées. Une synchro qui écrit sans cesse retarde
 * la mise à jour jusqu'à sa fin (une recherche pendant ce temps trouve l'état précédent).
 */
@Singleton
class SearchIndexer @Inject constructor(
    private val db: AppDatabase,
    private val dao: SearchDao,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    /** Suivi des tables pour la durée du processus (Application.onCreate). Idempotent. */
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
            if (removed > 0 || ms > SLOW_REFRESH_MS) Log.i(TAG, "Index de recherche à jour : $removed entrées retirées, $ms ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Repris à la prochaine écriture (ou au prochain démarrage) : la recherche garde l'état précédent.
            Log.w(TAG, "Mise à jour de l'index de recherche impossible", e)
        }
    }

    private companion object {
        const val TAG = "SearchIndexer"
        const val REFRESH_DEBOUNCE_MS = 2_000L
        const val SLOW_REFRESH_MS = 500L
        val WATCHED_TABLES = arrayOf("media", "media_source", "album", "album_media")
    }
}
