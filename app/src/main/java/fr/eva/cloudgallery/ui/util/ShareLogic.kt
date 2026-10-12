package fr.eva.cloudgallery.ui.util

/*
 * Logique pure du partage (et de « Ouvrir avec ») d'éléments OneDrive (testée en JVM).
 */

/** Nombre maximal d'éléments OneDrive à télécharger pour un même partage (décision utilisateur). */
internal const val MAX_SHARE_CLOUD_ITEMS = 20

/** Taille totale maximale des éléments OneDrive à télécharger pour un même partage (500 Mo). */
internal const val MAX_SHARE_CLOUD_BYTES = 500L * 1000 * 1000

/**
 * Elément candidat au partage : fichier local ([hasLocalFile]), copie hors ligne
 * ([offlinePath], tranche 3), ou élément OneDrive à télécharger ([cloudItemId], [sizeBytes]).
 */
internal data class ShareCandidate(
    val mediaId: Long,
    val hasLocalFile: Boolean,
    val offlinePath: String?,
    val cloudItemId: String?,
    val sizeBytes: Long,
)

/** Pourquoi un partage d'éléments OneDrive est refusé. */
internal enum class ShareRefusal {
    /** Plus de [MAX_SHARE_CLOUD_ITEMS] éléments à télécharger. */
    TOO_MANY,

    /** Plus de [MAX_SHARE_CLOUD_BYTES] à télécharger. */
    TOO_LARGE,
}

/**
 * Répartition d'un partage : fichiers locaux, copies hors ligne, éléments OneDrive à télécharger
 * et éléments sans source partageable ([unavailable]). [refusal] non nul : les téléchargements
 * dépassent les plafonds, le partage n'est pas lancé.
 */
internal data class SharePlan(
    val local: List<Long>,
    val offline: List<Long>,
    val toDownload: List<Long>,
    val unavailable: List<Long>,
    val downloadBytes: Long,
    val refusal: ShareRefusal?,
)

/**
 * Plan de partage de [candidates] par priorité de source : fichier local, copie hors ligne, puis
 * téléchargement depuis OneDrive si [cloudAllowed] (compte connecté), dans les plafonds
 * [maxCloudItems] / [maxCloudBytes]. Une taille inconnue (≤ 0) ne compte pas dans le total.
 */
internal fun planShare(
    candidates: List<ShareCandidate>,
    cloudAllowed: Boolean,
    maxCloudItems: Int = MAX_SHARE_CLOUD_ITEMS,
    maxCloudBytes: Long = MAX_SHARE_CLOUD_BYTES,
): SharePlan {
    val local = ArrayList<Long>()
    val offline = ArrayList<Long>()
    val toDownload = ArrayList<Long>()
    val unavailable = ArrayList<Long>()
    var bytes = 0L
    for (c in candidates) {
        when {
            c.hasLocalFile -> local += c.mediaId
            c.offlinePath != null -> offline += c.mediaId
            c.cloudItemId != null && cloudAllowed -> {
                toDownload += c.mediaId
                bytes += c.sizeBytes.coerceAtLeast(0)
            }
            else -> unavailable += c.mediaId
        }
    }
    val refusal = when {
        toDownload.size > maxCloudItems -> ShareRefusal.TOO_MANY
        bytes > maxCloudBytes -> ShareRefusal.TOO_LARGE
        else -> null
    }
    return SharePlan(local, offline, toDownload, unavailable, bytes, refusal)
}

/** Pourcentage entier (0..100) d'une progression, -1 si la taille est inconnue. */
internal fun progressPercent(done: Long, total: Long): Int =
    if (total <= 0) -1 else (done.coerceIn(0, total) * 100 / total).toInt()
