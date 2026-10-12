package fr.eva.cloudgallery.domain.model

import java.io.File
import java.util.UUID

/** Etat « hors ligne » d'un élément OneDrive (visionneuse, panneau Détails). */
enum class OfflineStatus {
    /** Pas de copie hors ligne et rien en cours. */
    NONE,

    /** En attente de téléchargement. */
    QUEUED,

    /** Téléchargement en cours. */
    DOWNLOADING,

    /** Arrêté par l'utilisateur (Gestionnaire de tâches) : reprise à sa demande. */
    PAUSED,

    /** Echec ([OfflineItemState.error]) : nouvel essai à la demande. */
    FAILED,

    /** Copie disponible sur l'appareil. */
    AVAILABLE,

    /** Copie disponible ; nouvelle version en attente ou en cours de téléchargement. */
    UPDATING,

    /**
     * Copie disponible (ancienne version) ; téléchargement de la nouvelle version en pause ou en
     * échec ([OfflineItemState.error]) : reprise ou annulation à la demande.
     */
    UPDATE_STOPPED,
}

/** Etat hors ligne d'un média, progression comprise. */
data class OfflineItemState(
    val status: OfflineStatus,
    val error: DownloadError? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /** Téléchargement en attente d'un réseau Wi-Fi (réglage « Wi-Fi uniquement »). */
    val waitingForUnmetered: Boolean = false,
) {
    /** Tâche en attente ou en cours (annulable). */
    val active: Boolean get() = status == OfflineStatus.QUEUED || status == OfflineStatus.DOWNLOADING ||
        (status == OfflineStatus.UPDATING && error == null)

    /** Tâche arrêtée, à reprendre (première copie ou mise à jour d'une copie présente). */
    val stopped: Boolean get() = status == OfflineStatus.PAUSED || status == OfflineStatus.FAILED ||
        status == OfflineStatus.UPDATE_STOPPED

    companion object {
        val NONE = OfflineItemState(OfflineStatus.NONE)
    }
}

/** File des copies hors ligne (écran Compte, notification). */
data class OfflineQueueState(
    val queued: Int = 0,
    val running: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
    /** Octets restant à télécharger (tâches en attente ou en cours, tailles connues). */
    val remainingBytes: Long = 0L,
    /** File en attente d'un réseau Wi-Fi (réglage « Wi-Fi uniquement », sans « Télécharger maintenant »). */
    val waitingForUnmetered: Boolean = false,
    /** Copies hors ligne présentes sur l'appareil. */
    val availableCount: Int = 0,
    /** Taille totale des copies hors ligne présentes. */
    val availableBytes: Long = 0L,
) {
    val pending: Int get() = queued + running
    val isEmpty: Boolean get() = pending == 0 && paused == 0 && failed == 0
}

/** Issue de « Conserver hors ligne ». */
sealed interface KeepOfflineResult {
    /** Aucun compte OneDrive utilisable. */
    data object NotSignedIn : KeepOfflineResult

    /** Rien à télécharger (déjà hors ligne, ou aucun élément OneDrive dans la sélection). */
    data object NothingToDo : KeepOfflineResult

    /** Espace insuffisant : il manque [missingBytes] sur le volume [storageUuid]. */
    data class NoSpace(val missingBytes: Long, val storageUuid: UUID?) : KeepOfflineResult

    /** Gros lot : confirmation demandée avec la taille totale et l'espace libre. */
    data class NeedsConfirmation(val count: Int, val bytes: Long, val freeBytes: Long) : KeepOfflineResult

    /** [count] éléments mis en file ([bytes] octets) ; [waitingForUnmetered] : attente du Wi-Fi. */
    data class Enqueued(val count: Int, val bytes: Long, val waitingForUnmetered: Boolean) : KeepOfflineResult

    /**
     * Mis en file, mais le système refuse de lancer le travail maintenant (appli en arrière-plan,
     * autorisation retirée) : il reprendra au prochain retour de l'appli au premier plan.
     */
    data class SchedulingRefused(val count: Int) : KeepOfflineResult
}

/**
 * Copie hors ligne d'un élément supprimé de OneDrive dont le délai de grâce est écoulé, en
 * attente de la décision de l'utilisateur (décision D1).
 */
data class DeletedOnOneDriveCopy(
    val mediaId: Long,
    val displayName: String,
    val type: MediaType,
    val mimeType: String?,
    val sizeBytes: Long,
)

/**
 * Modèle d'image (Coil) : miniature décodée depuis la copie hors ligne d'un média (fichier de
 * l'appli), quand ni fichier local ni miniature OneDrive ne sont disponibles. [version] invalide
 * le cache mémoire quand la copie change.
 */
data class OfflineThumbnail(val mediaId: Long, val version: String, val mimeType: String?)

/** Copie hors ligne d'un média : fichier de l'appli (jamais un fichier de l'utilisateur). */
data class OfflineFile(
    val file: File,
    val sizeBytes: Long,
    /** Version du contenu copié (cTag OneDrive), null si inconnue. */
    val version: String?,
)
