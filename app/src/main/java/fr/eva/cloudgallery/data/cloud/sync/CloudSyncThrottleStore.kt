package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fin de la pause demandée par Microsoft Graph (Retry-After) pour la synchronisation, conservée
 * hors de la mémoire : un redémarrage du processus ne relance pas les requêtes pendant la pause.
 *
 * Préférences dédiées (`cloud_sync.xml`, exclues de la sauvegarde), lues et écrites uniquement
 * sur le dispatcher d'E/S. La valeur est effacée avec les données OneDrive (déconnexion, purge) :
 * elle concerne le compte dont les données sont dans l'index.
 */
@Singleton
class CloudSyncThrottleStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Ouvert au premier usage, sur le dispatcher d'E/S (lecture du fichier). */
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    /** Instant (ms) avant lequel aucune synchronisation ne doit interroger Graph, 0 sinon. */
    suspend fun retryNotBefore(): Long = withContext(io) { prefs.getLong(KEY_RETRY_NOT_BEFORE, 0L) }

    suspend fun setRetryNotBefore(until: Long) = withContext(io) {
        prefs.edit { putLong(KEY_RETRY_NOT_BEFORE, until) }
    }

    suspend fun clear() = withContext(io) {
        if (prefs.contains(KEY_RETRY_NOT_BEFORE)) prefs.edit { remove(KEY_RETRY_NOT_BEFORE) }
    }

    private companion object {
        const val FILE_NAME = "cloud_sync"
        const val KEY_RETRY_NOT_BEFORE = "retry_not_before"
    }
}
