package fr.eva.cloudgallery.ui.video

import android.net.Uri
import androidx.media3.common.MediaItem
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.OfflineFile

/** Source physique choisie pour lire un média. */
sealed interface PlaybackSource {
    /** Fichier présent sur l'appareil (URI `content://` MediaStore). */
    data class Local(val uri: Uri, val mimeType: String?) : PlaybackSource

    /**
     * Copie hors ligne d'un élément OneDrive (fichier de l'appli, lu tel quel). [file] a été
     * vérifié (présent, taille attendue) hors du thread principal.
     */
    data class Offline(val file: OfflineFile, val mimeType: String?) : PlaybackSource

    /**
     * Elément OneDrive lu en streaming (URL de téléchargement temporaire obtenue à l'ouverture,
     * jamais conservée ici). [cTag] : version du contenu connue de l'index.
     */
    data class Remote(val itemId: String, val cTag: String?, val mimeType: String?) : PlaybackSource

    /** Aucune source lisible maintenant. */
    data class Unavailable(val reason: UnavailableReason) : PlaybackSource
}

/** Pourquoi un média ne peut pas être lu. */
enum class UnavailableReason {
    /** Elément OneDrive, mais accès impossible (compte déconnecté, autre compte, OneDrive non configuré). */
    SIGNED_OUT,

    /** Ni fichier local, ni copie hors ligne, ni élément OneDrive visible. */
    NO_SOURCE,
}

/**
 * Choisit la source de lecture d'un média, par priorité : fichier local visible, copie hors
 * ligne intacte, puis élément OneDrive en streaming si [remoteAllowed] (compte connecté, cf.
 * CloudAccessGuard). Jamais l'URI principale du média en repli.
 */
object PlaybackSourceResolver {

    /** Choix de la source, par priorité (fonction pure). */
    internal enum class Choice { LOCAL, OFFLINE, REMOTE, SIGNED_OUT, NO_SOURCE }

    internal fun choose(
        hasLocalFile: Boolean,
        hasCloudItem: Boolean,
        remoteAllowed: Boolean,
        offlineFileOk: Boolean = false,
    ): Choice = when {
        hasLocalFile -> Choice.LOCAL
        offlineFileOk -> Choice.OFFLINE
        !hasCloudItem -> Choice.NO_SOURCE
        remoteAllowed -> Choice.REMOTE
        else -> Choice.SIGNED_OUT
    }

    /**
     * Source de [details]. [offlineFileOk] : la copie hors ligne existe et a la taille attendue
     * (vérifié par l'appelant sur le dispatcher d'E/S, voir [isOfflineFileUsable]).
     */
    fun resolve(details: MediaDetails, remoteAllowed: Boolean, offlineFileOk: Boolean = false): PlaybackSource {
        val local = details.localSource?.uri
        val cloud = details.cloudSource
        val offline = details.offlineCopy
        return when (choose(local != null, cloud != null, remoteAllowed, offlineFileOk && offline != null)) {
            Choice.LOCAL -> PlaybackSource.Local(checkNotNull(local), details.mimeType)
            Choice.OFFLINE -> PlaybackSource.Offline(checkNotNull(offline), details.mimeType)
            Choice.REMOTE -> PlaybackSource.Remote(checkNotNull(cloud).externalId, cloud.cTag, details.mimeType)
            Choice.SIGNED_OUT -> PlaybackSource.Unavailable(UnavailableReason.SIGNED_OUT)
            Choice.NO_SOURCE -> PlaybackSource.Unavailable(UnavailableReason.NO_SOURCE)
        }
    }

    /**
     * Vrai si la lecture de [details] passerait par OneDrive (l'accès doit alors être vérifié).
     * [offlineFileOk] : copie hors ligne utilisable.
     */
    fun needsRemote(details: MediaDetails, offlineFileOk: Boolean = false): Boolean =
        choose(details.localSource?.uri != null, details.cloudSource != null, remoteAllowed = true, offlineFileOk) == Choice.REMOTE

    /** Copie hors ligne présente avec la taille enregistrée (à appeler sur le dispatcher d'E/S). */
    fun isOfflineFileUsable(file: OfflineFile?): Boolean =
        file != null && file.file.isFile && (file.sizeBytes <= 0 || file.file.length() == file.sizeBytes)

    /** Fichier progressif local : ExoPlayer détecte le conteneur lui-même (pas de type MIME forcé). */
    fun toMediaItem(source: PlaybackSource.Local): MediaItem = MediaItem.fromUri(source.uri)

    /** Copie hors ligne : fichier de l'appli lu directement (`file://`). */
    fun toMediaItem(source: PlaybackSource.Offline): MediaItem = MediaItem.fromUri(Uri.fromFile(source.file.file))
}
