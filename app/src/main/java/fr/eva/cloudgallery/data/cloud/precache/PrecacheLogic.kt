package fr.eva.cloudgallery.data.cloud.precache

import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_PX
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_TIERS
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_THUMB_BUCKETS
import fr.eva.cloudgallery.data.cloud.thumb.cloudThumbBucket
import fr.eva.cloudgallery.data.cloud.thumb.previewSideFor
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.PrecacheOutcome
import fr.eva.cloudgallery.domain.model.PrecacheState
import java.security.MessageDigest

/*
 * Logique pure de la mise en cache de tout OneDrive (testée en JVM) : noms des fichiers,
 * fichiers à consulter pour une demande de miniature, paliers téléchargés, estimation, espace.
 */

/** Nombre de colonnes de la grille Photos au zoom par défaut, en portrait (GridZoomLevels.DEFAULT_LEVEL). */
internal const val PRECACHE_GRID_COLUMNS = 3

/** Côté des aperçus des vidéos dans la visionneuse (DEFAULT_CLOUD_PREVIEW_SIDE, sans palier adaptatif). */
internal const val PRECACHE_VIDEO_PREVIEW_PX = CLOUD_PREVIEW_PX

/** Taille moyenne estimée d'une vignette de grille et d'un aperçu (octets), pour l'estimation affichée. */
internal const val ESTIMATED_GRID_BYTES = 35_000L
internal const val ESTIMATED_PREVIEW_BYTES = 350_000L

/** Espace libre minimal gardé sur l'appareil : 2 Go, ou 5 % du volume s'il est plus grand. */
internal const val MIN_FREE_BYTES = 2_000_000_000L
private const val MIN_FREE_PERCENT = 5L

/** Fichier de la mise en cache : vignette de grille (palier) ou aperçu de la visionneuse (côté). */
enum class PrecacheKind(internal val suffix: Char) { GRID('g'), PREVIEW('p') }

/**
 * Empreinte (SHA-1, hexadécimal) d'un élément dans une version donnée : nom de fichier sans
 * caractère spécial (les cTag OneDrive contiennent guillemets, accolades, virgules) et sans
 * l'identifiant en clair.
 */
internal fun precacheHash(ref: CloudThumbRef): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("${ref.itemId}\n${ref.version}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Chemin relatif d'un fichier : sous-dossier des deux premiers caractères (≈ 400 fichiers par dossier pour 100 000 éléments). */
internal fun precacheRelativePath(hash: String, kind: PrecacheKind, side: Int): String =
    "${hash.substring(0, 2)}/$hash.${kind.suffix}$side"

/** Empreinte d'un nom de fichier de la mise en cache, null pour un autre fichier (temporaire…). */
internal fun precacheHashOf(fileName: String): String? {
    val dot = fileName.indexOf('.')
    if (dot != HASH_LENGTH || dot + 2 > fileName.length) return null
    val hash = fileName.substring(0, dot)
    val suffix = fileName[dot + 1]
    if (PrecacheKind.entries.none { it.suffix == suffix }) return null
    if (fileName.substring(dot + 2).toIntOrNull() == null) return null
    return hash.takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' } }
}

private const val HASH_LENGTH = 40

/**
 * Tailles à consulter, dans l'ordre, pour une demande de [requested] px : d'abord les tailles au
 * moins aussi grandes (la plus proche d'abord : pas de perte de netteté), puis, si [allowLower]
 * (hors connexion, téléchargement impossible), les plus petites (la plus proche d'abord).
 */
internal fun precacheLookupSides(requested: Int, kind: PrecacheKind, allowLower: Boolean): List<Int> {
    val sides = when (kind) {
        PrecacheKind.GRID -> CLOUD_THUMB_BUCKETS.toList()
        PrecacheKind.PREVIEW -> CLOUD_PREVIEW_TIERS.toList()
    }
    val larger = sides.filter { it >= requested }.sorted()
    val smaller = if (allowLower) sides.filter { it < requested }.sortedDescending() else emptyList()
    return larger + smaller
}

/** Palier de vignette demandé par la grille au zoom par défaut, pour un écran de [shortSidePx] (portrait). */
internal fun precacheGridBucket(shortSidePx: Int, columns: Int = PRECACHE_GRID_COLUMNS): Int =
    cloudThumbBucket(shortSidePx / columns.coerceAtLeast(1))

/**
 * Côté de l'aperçu demandé par la visionneuse sur un réseau non facturé (même calcul que
 * `previewSideFor`), borné au plus grand palier accepté par Graph ([maxAcceptedSide]). Vidéo :
 * aperçu fixe ([PRECACHE_VIDEO_PREVIEW_PX]).
 */
internal fun precachePreviewSide(viewportLongPx: Int, imageLongPx: Int?, isVideo: Boolean, maxAcceptedSide: Int): Int {
    val side = if (isVideo) PRECACHE_VIDEO_PREVIEW_PX else previewSideFor(viewportLongPx, costlyNetwork = false, imageLongPx = imageLongPx)
    return minOf(side, maxAcceptedSide)
}

/** Taille estimée de la mise en cache de [missingItems] éléments (vignette et aperçu). */
internal fun precacheEstimatedBytes(missingItems: Int): Long =
    missingItems.toLong() * (ESTIMATED_GRID_BYTES + ESTIMATED_PREVIEW_BYTES)

/** Espace libre minimal à garder sur un volume de [totalBytes]. */
internal fun precacheMinFreeBytes(totalBytes: Long): Long = maxOf(MIN_FREE_BYTES, totalBytes / 100 * MIN_FREE_PERCENT)

/** Assez d'espace pour continuer : [usableBytes] au-dessus du minimum à garder. */
internal fun precacheHasRoom(usableBytes: Long, totalBytes: Long): Boolean = usableBytes > precacheMinFreeBytes(totalBytes)

/** Pourcentage (0..100) d'avancement, 0 sans total connu. */
internal fun precachePercent(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done.coerceIn(0, total).toLong() * 100 / total).toInt()

/**
 * Trop d'échecs consécutifs (réseau coupé, service indisponible) : le passage s'arrête et sera
 * repris plus tard par WorkManager, sans retélécharger ce qui est déjà enregistré.
 */
internal const val MAX_CONSECUTIVE_FAILURES = 20

internal fun precacheShouldPause(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

/** État d'un travail WorkManager de mise en cache, réduit à ce qu'affiche l'UI. */
internal enum class PrecacheWorkStatus { WAITING, RUNNING, SUCCEEDED, STOPPED }

/** Relevé d'un travail (WorkInfo) : état, progression ou résultat ([outcome] null : aucun). */
internal data class PrecacheWorkSnapshot(
    val status: PrecacheWorkStatus,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0L,
    val failed: Int = 0,
    val outcome: PrecacheOutcome? = null,
)

/**
 * État affiché d'après les travaux de la file (un passage, éventuellement suivi d'un passage
 * ajouté par « Garder à jour ») : un passage en cours d'abord, puis un passage en attente, puis
 * le dernier bilan. Annulé, en échec, ou vidé avant la fin (sans bilan) : rien en cours.
 */
internal fun precacheStateOf(works: List<PrecacheWorkSnapshot>): PrecacheState {
    works.firstOrNull { it.status == PrecacheWorkStatus.RUNNING }?.let {
        return PrecacheState.Running(it.done, it.total, it.bytes, it.failed)
    }
    if (works.any { it.status == PrecacheWorkStatus.WAITING }) return PrecacheState.Waiting
    works.lastOrNull { it.status == PrecacheWorkStatus.SUCCEEDED && it.outcome != null }?.let {
        return PrecacheState.Finished(it.outcome!!, it.done, it.total, it.failed)
    }
    return PrecacheState.Idle
}
