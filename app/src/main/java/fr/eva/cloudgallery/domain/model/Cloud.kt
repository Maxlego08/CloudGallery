package fr.eva.cloudgallery.domain.model

import java.io.IOException

/** Compte Microsoft connecté (mode compte unique). Le nom d'utilisateur n'est jamais journalisé. */
/**
 * Compte Microsoft connecté. [id] : identifiant MSAL opaque (jamais affiché ni journalisé :
 * [toString] ne le contient pas), qui sert à vérifier que l'index appartient à ce compte.
 */
data class CloudAccount(val username: String?, val id: String? = null) {
    override fun toString(): String = "CloudAccount"
}

/**
 * Cause d'un échec cloud, indépendante de la langue : le texte affiché est résolu par l'UI
 * (une erreur persistée reste ainsi juste après un changement de langue).
 */
enum class CloudErrorCode {
    /** Pas de réseau ou délai dépassé : réessayer plus tard, la session reste valide. */
    NETWORK,

    /** Session expirée ou révoquée : une reconnexion interactive est nécessaire. */
    AUTH_REQUIRED,

    /** Trop de requêtes : Microsoft Graph demande de patienter. */
    THROTTLED,

    /** Erreur côté service Microsoft. */
    SERVER,

    /** Configuration MSAL invalide (identifiant, URI de redirection, manifeste). */
    CONFIG,

    /** Autorisations refusées par l'utilisateur. */
    DECLINED,

    /** Aucun navigateur compatible pour la page de connexion Microsoft. */
    NO_BROWSER,

    UNKNOWN,
}

/** Etat du compte OneDrive. */
sealed interface CloudAccountState {
    /** Build sans identifiant MSAL : la fonction est masquée. */
    data object NotConfigured : CloudAccountState

    /** Etat pas encore lu (au démarrage du processus). */
    data object Loading : CloudAccountState

    /**
     * Aucun compte. [hasLeftoverData] : des données OneDrive d'une session précédente restent
     * dans l'index local (jamais supprimées sans confirmation explicite de l'utilisateur).
     */
    data class SignedOut(val hasLeftoverData: Boolean) : CloudAccountState

    data class SignedIn(val account: CloudAccount) : CloudAccountState

    /** Compte connu mais session expirée : les données en cache sont conservées. */
    data class NeedsReauth(val account: CloudAccount) : CloudAccountState

    /** Déconnexion en cours (aucune synchronisation ne doit démarrer pendant ce temps). */
    data object SigningOut : CloudAccountState

    data class Error(val code: CloudErrorCode) : CloudAccountState
}

/** Issue d'une connexion interactive. */
sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data object Cancelled : SignInOutcome
    data class Failed(val code: CloudErrorCode) : SignInOutcome
}

/** Echec cloud typé (le message affiché est résolu par l'UI à partir de [code]). */
open class CloudException(
    val code: CloudErrorCode,
    cause: Throwable? = null,
) : Exception("Echec OneDrive : $code", cause)

/** Aucun jeton utilisable sans interaction : reconnexion nécessaire (ou compte absent). */
class CloudAuthRequiredException(cause: Throwable? = null) : CloudException(CloudErrorCode.AUTH_REQUIRED, cause)

/** Requêtes Microsoft Graph limitées : ne pas réessayer avant [retryAfterMs] (ms, 0 si inconnu). */
class CloudThrottledException(val retryAfterMs: Long, cause: Throwable? = null) :
    CloudException(CloudErrorCode.THROTTLED, cause)

/** Profil Microsoft Graph du compte connecté (chargé à la demande, jamais journalisé). */
data class CloudProfile(
    val displayName: String?,
    val email: String?,
    /** Espace utilisé / total en octets (quota unifié du compte Microsoft), null si inconnu. */
    val quotaUsed: Long?,
    val quotaTotal: Long?,
)

/** Origine d'une demande de synchronisation OneDrive. */
enum class CloudSyncReason {
    /** Retour de l'appli au premier plan : ignorée si la dernière synchro est récente. */
    FOREGROUND,

    /** « Synchroniser maintenant ». */
    MANUAL,

    /** Connexion (ou reconnexion) réussie. */
    SIGN_IN,

    /** Synchro périodique en arrière-plan (WorkManager) : ignorée si la dernière est récente. */
    BACKGROUND,
}

/** Etat de la synchronisation OneDrive -> index local. */
data class CloudSyncState(
    val running: Boolean = false,
    /** Eléments reçus de Microsoft Graph pendant la synchro en cours. */
    val processedItems: Int = 0,
    /** Eléments OneDrive visibles dans l'index. */
    val itemCount: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastFullSyncAt: Long = 0L,
    /** Cause du dernier échec (texte résolu par l'UI), null si la dernière synchro a réussi. */
    val lastError: CloudErrorCode? = null,
    /** Limitation Graph : aucune requête avant cet instant (ms). */
    val retryNotBefore: Long = 0L,
    /**
     * Le OneDrive connecté n'est pas celui des données présentes dans l'index : la synchro est
     * suspendue jusqu'à ce que l'utilisateur confirme le remplacement (jamais automatique).
     */
    val accountMismatch: Boolean = false,
)

/**
 * Référence de la miniature Microsoft Graph d'un élément OneDrive : identifiant d'élément et
 * version du contenu (cTag, qui ne change pas pour un simple renommage). Sert de clé de cache.
 */
data class CloudThumbRef(val itemId: String, val version: String)

/**
 * Modèle d'image (Coil) d'une miniature OneDrive. [previewSide] : grand aperçu aux proportions
 * d'origine (visionneuse) tenant dans un carré de ce côté (px), au lieu d'une vignette carrée
 * dimensionnée selon la cellule (null).
 */
data class CloudThumbnail(val ref: CloudThumbRef, val previewSide: Int? = null)

/**
 * Accès temporaire au contenu d'un élément OneDrive (`@microsoft.graph.downloadUrl`). [url] est
 * une URL pré-authentifiée qui vaut un jeton : jamais persistée, jamais journalisée, jamais
 * envoyée avec un en-tête Authorization. Classe ordinaire (pas une data class) : [toString] ne
 * la contient pas.
 */
class CloudDownloadInfo(
    val itemId: String,
    val sizeBytes: Long,
    /** Version du contenu (cTag), null si Graph ne l'a pas renvoyée. */
    val cTag: String?,
    val eTag: String?,
    /** Lecteur OneDrive de l'élément (comparé au lecteur de l'index avant tout téléchargement persistant). */
    val driveId: String?,
    val url: String,
) {
    override fun toString(): String = "CloudDownloadInfo(itemId=$itemId, sizeBytes=$sizeBytes)"
}

/** Cause d'un échec de lecture en streaming d'un élément OneDrive, indépendante de la langue. */
enum class CloudStreamFailure {
    /** Compte déconnecté, session expirée ou OneDrive différent de celui de l'index. */
    AUTH_REQUIRED,

    /** Microsoft Graph demande de patienter. */
    THROTTLED,

    /** Elément introuvable sur OneDrive (supprimé ou déplacé hors du lecteur). */
    NOT_FOUND,

    /** Pas de réseau ou délai dépassé. */
    NETWORK,

    /** Autre échec côté Microsoft. */
    SERVER,
}

/**
 * Echec d'obtention de l'URL de lecture d'un élément OneDrive, remonté à Media3 sous forme
 * d'[IOException]. Le message ne contient jamais d'URL ni de jeton (seulement [failure]).
 */
class CloudStreamException(val failure: CloudStreamFailure, cause: Throwable? = null) :
    IOException("Lecture OneDrive impossible : $failure", cause)

/**
 * Cause d'échec d'un téléchargement de fichier OneDrive (original, partage, copie hors ligne),
 * indépendante de la langue. Persistable par son nom (jamais de texte libre ni d'URL).
 */
enum class DownloadError {
    /** Pas de réseau, délai dépassé ou connexion coupée. */
    NETWORK,

    /** Compte déconnecté, session expirée, autre compte, ou données OneDrive vidées entre-temps. */
    AUTH_REQUIRED,

    /** Microsoft Graph ou l'hôte de téléchargement demande de patienter. */
    THROTTLED,

    /** Elément introuvable sur OneDrive. */
    NOT_FOUND,

    /** Le contenu a changé sur OneDrive pendant le téléchargement. */
    CONTENT_CHANGED,

    /** Espace de stockage insuffisant sur l'appareil. */
    NO_SPACE,

    /** Taille reçue différente de la taille annoncée. */
    SIZE_MISMATCH,

    /** Autre échec côté service (réponse inattendue). */
    SERVER,

    /** Annulé par l'utilisateur. */
    CANCELLED,
}

/** Echec typé d'un téléchargement OneDrive. Le message ne contient jamais d'URL ni de jeton. */
class CloudDownloadException(val error: DownloadError, cause: Throwable? = null) :
    IOException("Téléchargement OneDrive impossible : $error", cause)
