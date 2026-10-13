package fr.eva.cloudgallery.domain.model

/**
 * Estimation avant « Mettre en cache tout OneDrive » : éléments concernés (miniature et aperçu
 * venant de OneDrive), éléments pas encore mis en cache, taille estimée de ce qui reste à
 * télécharger et espace libre sur l'appareil. [enoughSpace] : l'estimation tient en gardant
 * l'espace minimal réservé (sinon le passage s'arrêtera avant la fin).
 */
data class PrecacheEstimate(
    val total: Int,
    val missing: Int,
    val estimatedBytes: Long,
    val freeBytes: Long,
    val enoughSpace: Boolean,
)

/** Issue du dernier passage de mise en cache. */
enum class PrecacheOutcome {
    /** Tout est en cache (hors éléments sans miniature). */
    COMPLETED,

    /** Arrêté pour garder de l'espace libre sur l'appareil. */
    LOW_SPACE,

    /** Compte OneDrive déconnecté ou à reconnecter. */
    SIGNED_OUT,
}

/** État de la mise en cache de tout OneDrive, vu par la page Stockage. */
sealed interface PrecacheState {
    /** Rien en cours (jamais lancé, ou annulé). */
    data object Idle : PrecacheState

    /** En attente des conditions (Wi-Fi, batterie) ou d'une reprise après une coupure réseau. */
    data object Waiting : PrecacheState

    /** En cours : [done] éléments traités sur [total], [bytes] téléchargés pendant ce passage. */
    data class Running(val done: Int, val total: Int, val bytes: Long, val failed: Int) : PrecacheState

    /** Dernier passage terminé. [failed] : éléments sans miniature ou en échec. */
    data class Finished(val outcome: PrecacheOutcome, val done: Int, val total: Int, val failed: Int) : PrecacheState
}
