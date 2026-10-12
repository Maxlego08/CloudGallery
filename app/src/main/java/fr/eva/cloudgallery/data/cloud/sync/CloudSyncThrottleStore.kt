package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Etat de synchronisation conservé hors de la mémoire (un redémarrage du processus ne l'oublie
 * pas) :
 * - fin de la pause demandée par Microsoft Graph (Retry-After) : pas de requête pendant la pause ;
 * - compte MSAL vérifié ([verifiedAccountId]) : celui dont le lecteur correspond à l'index lors
 *   du dernier passage ; un autre compte connecté n'accède à rien avant d'être vérifié ;
 * - autre compte détecté ([accountMismatch]) : accès bloqué dès le démarrage, jusqu'à la purge ;
 * - reprise d'une énumération complète interrompue ([resumeMeta], journal des éléments vus dans
 *   `noBackupFilesDir`, voir EnumerationResumeLogic). Le nextLink porte un jeton Graph : il
 *   n'est jamais journalisé.
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

    /** Etat de reprise de l'énumération complète en cours ou interrompue. */
    internal suspend fun resumeMeta(): EnumerationResumeMeta = withContext(io) {
        EnumerationResumeMeta(
            version = prefs.getString(KEY_RESUME_VERSION, null),
            startedAt = prefs.getLong(KEY_RESUME_STARTED_AT, 0L),
            nextLink = prefs.getString(KEY_RESUME_NEXT_LINK, null),
            pages = prefs.getInt(KEY_RESUME_PAGES, 0),
        )
    }

    /**
     * Nouvelle énumération complète liée à [version] : l'ancien état de reprise et son journal
     * sont effacés (écriture synchrone : jamais un ancien journal avec le nouvel état).
     */
    suspend fun beginEnumeration(version: String, startedAt: Long) = withContext(io) {
        journal.delete()
        prefs.edit(commit = true) {
            removeResume()
            putString(KEY_RESUME_VERSION, version)
            putLong(KEY_RESUME_STARTED_AT, startedAt)
        }
    }

    /** Ajoute le bloc d'une page au journal, synchronisé sur disque avant tout enregistrement du lien. */
    suspend fun appendSeenBlock(block: String) = withContext(io) {
        FileOutputStream(journal, true).use { out ->
            out.write(block.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Lien de la page suivante, après [pages] pages appliquées et journalisées. */
    suspend fun saveNextLink(nextLink: String, pages: Int) = withContext(io) {
        prefs.edit {
            putString(KEY_RESUME_NEXT_LINK, nextLink)
            putInt(KEY_RESUME_PAGES, pages)
        }
    }

    /** Contenu du journal, null s'il manque, est trop gros ou illisible. */
    suspend fun readSeenJournal(): String? = withContext(io) {
        try {
            if (!journal.isFile || journal.length() > SEEN_JOURNAL_MAX_BYTES) null else journal.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    /** Plus de reprise possible (énumération terminée, ou reprise abandonnée). */
    suspend fun clearResume() = withContext(io) {
        prefs.edit(commit = true) { removeResume() }
        journal.delete()
    }

    /** Tout est effacé (données OneDrive purgées), journal de reprise compris. */
    suspend fun clear() = withContext(io) {
        prefs.edit(commit = true) { clear() }
        journal.delete()
    }

    private val journal: File get() = File(context.noBackupFilesDir, JOURNAL_FILE)

    private fun SharedPreferences.Editor.removeResume() {
        remove(KEY_RESUME_VERSION)
        remove(KEY_RESUME_STARTED_AT)
        remove(KEY_RESUME_NEXT_LINK)
        remove(KEY_RESUME_PAGES)
    }

    private companion object {
        const val FILE_NAME = "cloud_sync"
        const val KEY_RETRY_NOT_BEFORE = "retry_not_before"
        const val KEY_VERIFIED_ACCOUNT = "verified_account_id"
        const val KEY_ACCOUNT_MISMATCH = "account_mismatch"
        const val KEY_RESUME_VERSION = "resume_version"
        const val KEY_RESUME_STARTED_AT = "resume_started_at"
        const val KEY_RESUME_NEXT_LINK = "resume_next_link"
        const val KEY_RESUME_PAGES = "resume_pages"

        /** Journal des éléments vus de l'énumération complète en cours (hors sauvegarde). */
        const val JOURNAL_FILE = "onedrive_enumeration_seen.log"
    }
}
