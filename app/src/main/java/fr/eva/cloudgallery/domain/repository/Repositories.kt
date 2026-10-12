package fr.eva.cloudgallery.domain.repository

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.paging.PagingData
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
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
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Accès en lecture à la bibliothèque unifiée (locale + cloud), indépendamment de la source. */
interface MediaRepository {
    /** Médias du contexte, du plus récent au plus ancien (dateTaken DESC, id DESC). */
    fun pagedMedia(context: MediaContext): Flow<PagingData<MediaSummary>>

    /** Identifiants du contexte, dans le même ordre que [pagedMedia]. Utilisé par la visionneuse. */
    fun observeMediaIds(context: MediaContext): Flow<List<Long>>

    fun observeMedia(id: Long): Flow<MediaDetails?>

    suspend fun getMedia(id: Long): MediaDetails?

    suspend fun getSummaries(ids: List<Long>): List<MediaSummary>

    /** Nombre de médias parmi [ids] disposant d'un fichier local (les éléments uniquement OneDrive sont exclus). */
    suspend fun countWithLocalFile(ids: Collection<Long>): Int

    /** Nombre de médias parmi [ids] partageables (fichier local, copie hors ligne ou élément OneDrive). */
    suspend fun countShareable(ids: Collection<Long>): Int

    /** Eléments OneDrive visibles sans copie hors ligne parmi [ids] (« Conserver hors ligne »). */
    suspend fun countKeepableOffline(ids: Collection<Long>): Int

    /** Médias parmi [ids] qui ont une copie hors ligne (« Libérer l'espace »). */
    suspend fun countWithOfflineCopy(ids: Collection<Long>): Int

    /** Identifiants du contexte dont la date de prise de vue est dans [startMs, endMs[, dans l'ordre de [pagedMedia]. */
    suspend fun idsInPeriod(context: MediaContext, startMs: Long, endMs: Long): List<Long>

    /**
     * Identifiants du contexte situés entre deux médias de la liste (bornes incluses, dans
     * n'importe quel ordre), y compris ceux qui ne sont pas encore chargés par la pagination.
     */
    suspend fun idsBetween(context: MediaContext, a: MediaSummary, b: MediaSummary): List<Long>

    /** Nombre de médias du contexte. */
    fun observeCount(context: MediaContext): Flow<Int>

    /** Retrouve (ou indexe immédiatement) le média correspondant à une URI MediaStore. */
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

    /** Supprime uniquement un album virtuel (les médias référencés ne sont pas touchés). */
    suspend fun deleteVirtualAlbum(albumId: Long)
    suspend fun addToAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun removeFromAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun setPinned(albumId: Long, pinned: Boolean)
}

/** Indexation incrémentale de MediaStore vers la base Room. */
interface LocalMediaIndexer {
    val state: StateFlow<IndexState>

    /** Synchronise MediaStore -> Room. [full] force une relecture complète. */
    suspend fun sync(full: Boolean = false)

    /** Démarre l'observation des changements MediaStore (ContentObserver). Idempotent. */
    fun startObserving()
}

/**
 * Opérations d'écriture sur les fichiers locaux. Celles qui exigent une confirmation
 * système renvoient un [IntentSender] à lancer par l'UI (StartIntentSenderForResult).
 */
interface LocalMediaWriter {
    /** Corbeille Android (récupérable) ou suppression définitive selon [toTrash]. */
    fun createDeleteRequest(uris: List<Uri>, toTrash: Boolean): IntentSender
    fun createWriteRequest(uris: List<Uri>): IntentSender

    /** Vrai si [mimeType] permet une rotation sans perte (orientation EXIF modifiable). */
    fun canRotate(mimeType: String?): Boolean

    /** Pivote une image de [degrees] (multiple de 90). Nécessite l'accès en écriture accordé. */
    suspend fun rotateImage(uri: Uri, degrees: Int): Result<Unit>

    /** Enregistre une copie recadrée (rectangle normalisé 0..1) et renvoie l'URI créée. */
    suspend fun saveCroppedCopy(
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
    ): Result<Uri>

    /** Copie des fichiers locaux vers un dossier relatif (ex. "Pictures/Vacances/"). */
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

    /** [bytes] : une des valeurs de `ORIGINALS_CACHE_CHOICES`. */
    suspend fun setOriginalsCacheMaxBytes(bytes: Long)

    /** [bytes] : une des valeurs de `THUMBNAIL_CACHE_CHOICES` (appliquée au prochain démarrage). */
    suspend fun setThumbnailCacheMaxBytes(bytes: Long)
    suspend fun setBackgroundSyncInterval(interval: BackgroundSyncInterval)
    suspend fun setBackgroundSyncWifiOnly(enabled: Boolean)
    suspend fun setOneDriveOnlyChosen(chosen: Boolean)
}

/** Espace occupé par l'appli et vidage du cache non épinglé (page Stockage). */
interface StorageRepository {
    /** Mesure sur le disque (dispatcher d'E/S). */
    suspend fun usage(): StorageUsage

    /**
     * Vide le cache non épinglé : miniatures et aperçus OneDrive, originaux temporaires (sauf
     * téléchargements en cours). Les copies hors ligne ne sont jamais touchées.
     */
    suspend fun clearCache()

    /** Nouveau plafond des originaux (déjà enregistré) : nettoyage aussitôt (dispatcher d'E/S). */
    suspend fun applyOriginalsCap()
}

/**
 * Compte OneDrive (mode compte unique) vu par l'UI : connexion, déconnexion et données cloud
 * locales. Les opérations interactives ne suspendent pas l'appelant : leur issue est publiée
 * dans [signInOutcomes] et l'état dans [state].
 */
interface CloudAccountRepository {
    /** Vrai si ce build embarque une configuration MSAL valide (sinon tout est masqué). */
    val isAvailable: Boolean

    val state: StateFlow<CloudAccountState>

    /** Vrai pendant une connexion interactive (page Microsoft ouverte). */
    val signInRunning: StateFlow<Boolean>

    /** Issues des connexions interactives, une par tentative. */
    val signInOutcomes: Flow<SignInOutcome>

    /** Relit le compte persisté par MSAL (thread d'E/S). */
    suspend fun refresh()

    /** Ouvre la page de connexion Microsoft depuis [activity]. */
    fun signIn(activity: Activity)

    /** Reconnexion du compte courant après expiration de la session. */
    fun reauthenticate(activity: Activity)

    /**
     * Déconnecte le compte puis retire de l'index local toutes les données OneDrive (médias,
     * favoris et ajouts aux albums associés, albums OneDrive). Les fichiers sur OneDrive et sur
     * l'appareil ne sont jamais modifiés. Echec de la déconnexion : les données sont conservées.
     */
    suspend fun signOutAndPurge(): Result<Unit>

    /** Retire les données OneDrive restées dans l'index après une session terminée. */
    suspend fun purgeLeftoverData(): Result<Unit>

    /** Etat de la synchronisation OneDrive. */
    val syncState: StateFlow<CloudSyncState>

    /** Synchronisation OneDrive immédiate (incrémentale). */
    fun syncNow()

    /** Nom, adresse et quota du compte connecté (Microsoft Graph). */
    suspend fun loadProfile(): Result<CloudProfile>

    /**
     * Le OneDrive connecté diffère de celui des données de l'index ([CloudSyncState.accountMismatch]) :
     * après confirmation de l'utilisateur, retire les données de l'ancien compte puis synchronise
     * le compte connecté. Les fichiers sur OneDrive et sur l'appareil ne sont jamais modifiés.
     */
    suspend fun replaceMismatchedData(): Result<Unit>
}

/**
 * Synchronisation OneDrive -> index local (lecture seule côté OneDrive). Ne s'exécute que pour
 * un compte connecté ; les demandes simultanées sont regroupées.
 */
interface CloudMediaSyncer {
    val state: StateFlow<CloudSyncState>

    /** Demande une synchronisation (sans attendre). Sans effet si OneDrive est indisponible. */
    fun requestSync(reason: CloudSyncReason)

    /**
     * Synchronisation dont on attend la fin (travail de fond) : regroupée avec les demandes en
     * cours comme [requestSync]. Vrai si OneDrive a été interrogé (même en échec).
     */
    suspend fun syncAndWait(reason: CloudSyncReason): Boolean

    /**
     * Le compte MSAL [accountId] peut accéder aux données de l'index : aucun autre lecteur
     * détecté, et compte vérifié par le dernier passage (ou aucun compte encore vérifié).
     * Valeurs conservées hors de la mémoire : vrai dès le démarrage du processus.
     */
    suspend fun accountAllowed(accountId: String?): Boolean

    /**
     * Interrompt la synchronisation en cours et exécute [block] en exclusion mutuelle avec toute
     * synchronisation (déconnexion, purge). L'état est relu ensuite.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T
}

/** Jeton d'accès Microsoft Graph pour le compte connecté. */
interface CloudTokenProvider {
    /**
     * Jeton valide, renouvelé silencieusement si besoin (thread d'E/S).
     * @throws CloudAuthRequiredException si une reconnexion interactive est nécessaire.
     * @throws CloudException pour les autres échecs (réseau, service…).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String

    /**
     * Microsoft Graph a refusé (401) [token], obtenu juste avant par un renouvellement forcé :
     * l'accès a été révoqué côté service, le compte passe en reconnexion requise. Sans effet si
     * [token] n'est plus le jeton courant (déconnexion, autre compte entre-temps).
     */
    suspend fun onTokenRejected(token: String)
}

/**
 * Copies hors ligne d'éléments OneDrive (« Conserver hors ligne » / « Libérer ») : fichiers
 * privés de l'appli, téléchargés en arrière-plan par une tâche lancée par l'utilisateur. Ni
 * MediaStore ni les fichiers de l'utilisateur ne sont jamais touchés.
 */
interface OfflineRepository {
    /** File des téléchargements et copies présentes. */
    val queue: Flow<OfflineQueueState>

    /** Etat hors ligne d'un média (statut, progression, erreur). */
    fun observeItem(mediaId: Long): Flow<OfflineItemState>

    /**
     * Met en file les éléments OneDrive de [mediaIds] sans copie hors ligne. Un gros lot (plus de
     * 200 éléments ou 1 Go) demande d'abord confirmation, sauf [confirmedLargeBatch].
     */
    suspend fun keepOffline(mediaIds: Collection<Long>, confirmedLargeBatch: Boolean = false): KeepOfflineResult

    /**
     * Copies hors ligne parmi [mediaIds] (visibles ou masquées) qui sont la dernière copie de
     * l'élément : ni sur OneDrive (supprimé), ni sur l'appareil. Lève une exception si le compte
     * est impossible : « Libérer » ne doit alors rien supprimer sans confirmation.
     */
    suspend fun lastCopiesAmong(mediaIds: Collection<Long>): Int

    /**
     * Supprime les copies hors ligne de [mediaIds] (et leurs téléchargements). Un élément qui
     * n'existait plus qu'en copie hors ligne disparaît. Renvoie le nombre de copies supprimées.
     */
    suspend fun release(mediaIds: Collection<Long>): Int

    /** Annule les téléchargements de [mediaIds] (une copie déjà présente est gardée). */
    suspend fun cancel(mediaIds: Collection<Long>)

    /** Annule tous les téléchargements (bouton « Annuler » de la notification, écran Compte). */
    suspend fun cancelAll()

    /** Reprend les téléchargements en pause ou en échec. */
    suspend fun resume()

    /**
     * « Télécharger maintenant » : les tâches en file à cet instant ignorent le réglage « Wi-Fi
     * uniquement » (les suivantes le respectent de nouveau).
     */
    suspend fun downloadNow()

    /** Réglage « Wi-Fi uniquement » modifié : travail en cours ou planifié adapté aussitôt. */
    suspend fun onNetworkPolicyChanged()

    /** Retour au premier plan : reprise après un arrêt du processus, nettoyage, relance de la file. */
    suspend fun resumeIfNeeded()

    /** Copies d'éléments supprimés de OneDrive en attente de décision (délai de grâce écoulé). */
    val deletedOnOneDrive: Flow<List<DeletedOnOneDriveCopy>>

    /**
     * « Supprimer les copies » confirmé pour [mediaIds] (la liste montrée à l'utilisateur) :
     * retire ces copies et leurs éléments, seulement pour ceux encore en attente de décision.
     * Renvoie le nombre réellement traité.
     */
    suspend fun deleteCopiesOfDeletedItems(mediaIds: Collection<Long>): Int

    /**
     * « Les conserver » pour [mediaIds] (la liste montrée) : ces copies restent, visibles, comme
     * seuls exemplaires. Seulement pour ceux encore en attente de décision ; renvoie leur nombre.
     */
    suspend fun keepCopiesOfDeletedItems(mediaIds: Collection<Long>): Int

    /** Copies hors ligne supprimées par une déconnexion ou une purge, dont dernières copies. */
    suspend fun purgeImpact(): OfflinePurgeImpact
}

/** Copies hors ligne retirées par une purge des données OneDrive ([lastCopies] : seuls exemplaires). */
data class OfflinePurgeImpact(val copies: Int, val lastCopies: Int)

interface PlaybackPositionRepository {
    suspend fun get(mediaId: Long): Long
    suspend fun save(mediaId: Long, positionMs: Long, durationMs: Long)
    suspend fun clear(mediaId: Long)
}
