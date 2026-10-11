package fr.eva.cloudgallery.domain.model

/** Compte Microsoft connecté (mode compte unique). Le nom d'utilisateur n'est jamais journalisé. */
data class CloudAccount(val username: String?)

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
 * Modèle d'image (Coil) d'une miniature OneDrive. [preview] : grand aperçu aux proportions
 * d'origine (visionneuse) au lieu d'une vignette carrée dimensionnée selon la cellule.
 */
data class CloudThumbnail(val ref: CloudThumbRef, val preview: Boolean = false)
