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
 * Etat de synchronisation conservé hors de la mémoire (un redémarrage du processus ne l'oublie
 * pas) :
 * - fin de la pause demandée par Microsoft Graph (Retry-After) : pas de requête pendant la pause ;
 * - compte MSAL vérifié ([verifiedAccountId]) : celui dont le lecteur correspond à l'index lors
 *   du dernier passage ; un autre compte connecté n'accède à rien avant d'être vérifié ;
 * - autre compte détecté ([accountMismatch]) : accès bloqué dès le démarrage, jusqu'à la purge.
 *
 * Préférences dédiées (`cloud_sync.xml`, exclues de la sauvegarde), lues et écrites uniquement
 * sur le dispatcher d'E/S (lues ensuite en mémoire). Tout est effacé avec les données OneDrive
 * (déconnexion, purge) : ces valeurs concernent le compte dont les données sont dans l'index.
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

    /** Fin de pause effacée (passage réussi) ; le compte vérifié est gardé. */
    suspend fun clearRetry() = withContext(io) {
        if (prefs.contains(KEY_RETRY_NOT_BEFORE)) prefs.edit { remove(KEY_RETRY_NOT_BEFORE) }
    }

    /** Identifiant MSAL du compte vérifié, null si aucun (index vide, ou données d'avant la phase 5). */
    suspend fun verifiedAccountId(): String? = withContext(io) { prefs.getString(KEY_VERIFIED_ACCOUNT, null) }

    suspend fun accountMismatch(): Boolean = withContext(io) { prefs.getBoolean(KEY_ACCOUNT_MISMATCH, false) }

    /** Lecteur du compte [accountId] conforme à l'index : compte vérifié, plus de blocage. */
    suspend fun setVerified(accountId: String) = withContext(io) {
        prefs.edit {
            putString(KEY_VERIFIED_ACCOUNT, accountId)
            remove(KEY_ACCOUNT_MISMATCH)
        }
    }

    /** Lecteur différent de celui de l'index : accès bloqué jusqu'à la purge. */
    suspend fun setAccountMismatch() = withContext(io) {
        prefs.edit { putBoolean(KEY_ACCOUNT_MISMATCH, true) }
    }

    /** Tout est effacé (données OneDrive purgées). */
    suspend fun clear() = withContext(io) {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE_NAME = "cloud_sync"
        const val KEY_RETRY_NOT_BEFORE = "retry_not_before"
        const val KEY_VERIFIED_ACCOUNT = "verified_account_id"
        const val KEY_ACCOUNT_MISMATCH = "account_mismatch"
    }
}
