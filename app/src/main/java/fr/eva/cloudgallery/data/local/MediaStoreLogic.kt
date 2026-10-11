package fr.eva.cloudgallery.data.local

import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType

/*
 * Logique pure (sans dépendance Android) de l'indexation MediaStore, testée en JVM.
 */

/** Nom du fournisseur dans la table sync_cursor. */
internal const val MEDIASTORE_PROVIDER = "mediastore"

private const val PREFIX_IMAGE = "image"
private const val PREFIX_VIDEO = "video"

/** Identifiant externe d'une source LOCAL : « image:123 » / « video:45 » (les _ID images et vidéos ne sont pas disjoints). */
internal fun buildExternalId(type: MediaType, mediaStoreId: Long): String = when (type) {
    MediaType.IMAGE -> "$PREFIX_IMAGE:$mediaStoreId"
    MediaType.VIDEO -> "$PREFIX_VIDEO:$mediaStoreId"
}

/** Inverse de [buildExternalId]. Renvoie null si le format n'est pas reconnu. */
internal fun parseExternalId(externalId: String): Pair<MediaType, Long>? {
    val sep = externalId.indexOf(':')
    if (sep <= 0) return null
    val type = when (externalId.substring(0, sep)) {
        PREFIX_IMAGE -> MediaType.IMAGE
        PREFIX_VIDEO -> MediaType.VIDEO
        else -> return null
    }
    val id = externalId.substring(sep + 1).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return type to id
}

/** Clé de dossier d'une source locale. */
internal fun localBucketKey(bucketId: Long): String = "local:$bucketId"

/**
 * Date de prise de vue en ms : DATE_TAKEN (ms) si renseignée, sinon DATE_ADDED puis
 * DATE_MODIFIED (en secondes dans MediaStore), sinon 0.
 */
internal fun resolveDateTaken(dateTakenMs: Long?, dateAddedSec: Long?, dateModifiedSec: Long?): Long = when {
    dateTakenMs != null && dateTakenMs > 0 -> dateTakenMs
    dateAddedSec != null && dateAddedSec > 0 -> dateAddedSec * 1000
    dateModifiedSec != null && dateModifiedSec > 0 -> dateModifiedSec * 1000
    else -> 0L
}

/**
 * Une relecture complète est nécessaire si elle est demandée, s'il n'existe pas encore de
 * curseur exploitable, ou si la version MediaStore a changé (les générations ne sont alors
 * plus comparables : base MediaStore recréée, restauration…).
 */
internal fun needsFullSync(
    forceFull: Boolean,
    storedCursor: String?,
    storedVersion: String?,
    currentVersion: String?,
): Boolean = forceFull ||
    storedCursor?.toLongOrNull() == null ||
    storedVersion == null ||
    currentVersion == null ||
    storedVersion != currentVersion

/** La version MediaStore a changé (et non simplement « inconnue ») : les _ID ne sont plus fiables. */
internal fun mediaStoreVersionChanged(storedVersion: String?, currentVersion: String?): Boolean =
    storedVersion != null && currentVersion != null && storedVersion != currentVersion

/**
 * Empreinte stable d'un fichier, indépendante du _ID MediaStore : sert à ré-associer une source
 * à son média (favoris, albums) après un changement de version MediaStore.
 */
internal fun localFingerprint(relativePath: String?, displayName: String, sizeBytes: Long): String =
    "${relativePath.orEmpty().trim('/')}|$displayName|$sizeBytes"

/** Délai au-delà duquel une source masquée dont la disparition est confirmée est purgée. */
internal const val MISSING_GRACE_MS = 30L * 24 * 60 * 60 * 1000

/** Seuils du frein anti-suppression massive (proportion ET nombre absolu). */
internal const val MASS_REMOVAL_MIN_COUNT = 200
internal const val MASS_REMOVAL_FRACTION = 0.25

/** Etat constaté d'une source qui n'apparaît plus dans la requête MediaStore standard. */
internal enum class MissingVerdict {
    /** Absente de MediaStore (y compris corbeille / en attente) alors que son volume est monté. */
    GONE,

    /** Encore présente mais invisible (corbeille, en attente), sur un volume démonté, ou invérifiable. */
    HIDDEN,
}

internal data class MissingCandidate(
    val sourceId: Long,
    val mediaId: Long,
    val verdict: MissingVerdict,
    /** Déjà masquée depuis cet instant, ou null. */
    val missingSince: Long?,
)

internal data class RemovalPlan(
    /** Sources à supprimer de la base (avec leurs médias devenus orphelins). */
    val delete: List<MissingCandidate>,
    /** Sources à masquer (conservées avec favoris et albums). */
    val hide: List<MissingCandidate>,
    /** Vrai si le frein anti-suppression massive a été déclenché. */
    val massRemovalBlocked: Boolean,
)

/**
 * Décide du sort des sources absentes de la requête MediaStore. Rien n'est supprimé
 * définitivement sans accès complet (images ET vidéos) : en accès partiel, MediaStore ne
 * renvoie que la sélection de l'utilisateur. Une disparition confirmée est supprimée
 * immédiatement, sauf si elle est massive (frein : masquage puis purge après [MISSING_GRACE_MS]).
 * Une source déjà masquée (corbeille, volume démonté, accès partiel, frein) n'est purgée qu'une
 * fois confirmée disparue et après le délai de grâce.
 */
internal fun planRemovals(
    candidates: List<MissingCandidate>,
    knownCount: Int,
    fullAccess: Boolean,
    now: Long,
): RemovalPlan {
    if (!fullAccess) return RemovalPlan(emptyList(), candidates, massRemovalBlocked = false)
    val gone = candidates.filter { it.verdict == MissingVerdict.GONE }
    val freshGone = gone.count { it.missingSince == null }
    val massive = freshGone > MASS_REMOVAL_MIN_COUNT && freshGone > knownCount * MASS_REMOVAL_FRACTION
    val delete = ArrayList<MissingCandidate>()
    val hide = ArrayList<MissingCandidate>()
    for (c in candidates) {
        when {
            c.verdict == MissingVerdict.HIDDEN -> hide += c
            // Déjà masquée (frein, accès partiel…) : purgée seulement après le délai de grâce.
            c.missingSince != null -> if (now - c.missingSince >= MISSING_GRACE_MS) delete += c else hide += c
            massive -> hide += c
            else -> delete += c
        }
    }
    return RemovalPlan(delete, hide, massRemovalBlocked = massive)
}

/**
 * Analyse les segments de chemin d'une URI MediaStore (`content://media/<volume>/images/media/<id>`,
 * `.../video/media/<id>`, `.../file/<id>`) et renvoie les identifiants externes candidats.
 * Pour les URI « file » le type est inconnu : les deux candidats sont renvoyés.
 */
internal fun externalIdCandidatesFromMediaStorePath(pathSegments: List<String>): List<String> {
    if (pathSegments.size < 3) return emptyList()
    val id = pathSegments.last().toLongOrNull() ?: return emptyList()
    return when (pathSegments[1]) {
        "images" -> listOf(buildExternalId(MediaType.IMAGE, id))
        "video" -> listOf(buildExternalId(MediaType.VIDEO, id))
        "file" -> listOf(buildExternalId(MediaType.IMAGE, id), buildExternalId(MediaType.VIDEO, id))
        else -> emptyList()
    }
}

/**
 * Identifiant de document du MediaDocumentsProvider (« image:123 », « video:45 ») :
 * il a justement le format de nos identifiants externes.
 */
internal fun externalIdFromMediaDocumentId(documentId: String): String? =
    parseExternalId(documentId)?.let { (type, id) -> buildExternalId(type, id) }

/** Normalise un angle en 0, 90, 180 ou 270 ; null si ce n'est pas un multiple de 90. */
internal fun normalizeRightAngle(degrees: Int): Int? {
    if (degrees % 90 != 0) return null
    return ((degrees % 360) + 360) % 360
}

/** Rectangle normalisé (0..1). */
internal data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): NormRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        return NormRect(l, t, right.coerceIn(l, 1f), bottom.coerceIn(t, 1f))
    }
}

/**
 * Convertit un rectangle de recadrage exprimé dans l'image **pivotée** de [rotationDegrees]
 * (sens horaire) en rectangle dans l'image source non pivotée. Permet de recadrer avant de
 * pivoter, et donc de n'allouer qu'un seul bitmap de la taille du recadrage.
 */
internal fun cropRectInSource(rotated: NormRect, rotationDegrees: Int): NormRect {
    val r = rotated.clamped()
    return when (normalizeRightAngle(rotationDegrees) ?: 0) {
        // Rotation horaire de 90° : x' = 1 - y, y' = x  =>  x = y', y = 1 - x'
        90 -> NormRect(left = r.top, top = 1f - r.right, right = r.bottom, bottom = 1f - r.left)
        180 -> NormRect(left = 1f - r.right, top = 1f - r.bottom, right = 1f - r.left, bottom = 1f - r.top)
        // Rotation horaire de 270° : x' = y, y' = 1 - x  =>  x = 1 - y', y = x'
        270 -> NormRect(left = 1f - r.bottom, top = r.left, right = 1f - r.top, bottom = r.right)
        else -> r
    }
}

/** Facteur de sous-échantillonnage (>= 1) pour que le plus grand côté ne dépasse pas [maxSide]. */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    val largest = maxOf(width, height)
    if (largest <= maxSide || maxSide <= 0) return 1
    return (largest + maxSide - 1) / maxSide
}

/** Normalise un chemin relatif MediaStore : sans « / » initial, avec « / » final. */
internal fun normalizeRelativePath(path: String): String {
    val trimmed = path.trim().trim('/')
    return if (trimmed.isEmpty()) "" else "$trimmed/"
}

/** « IMG_001.jpg » -> « IMG_001_crop_<ts>.jpg ». */
internal fun croppedCopyName(originalName: String?, timestamp: Long): String {
    val base = originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "IMG"
    return "${base}_crop_$timestamp.jpg"
}

/** Une position de lecture n'est mémorisée que si elle n'est ni au tout début ni à la toute fin. */
internal fun shouldKeepPlaybackPosition(positionMs: Long, durationMs: Long, marginMs: Long = 3_000L): Boolean {
    if (positionMs < marginMs) return false
    if (durationMs > 0 && positionMs > durationMs - marginMs) return false
    return true
}

/**
 * Ordonne deux médias selon leur position dans les grilles (dateTaken DESC, id DESC) :
 * renvoie (le plus haut dans la liste, le plus bas).
 */
internal fun orderedByListPosition(a: MediaSummary, b: MediaSummary): Pair<MediaSummary, MediaSummary> {
    val aFirst = a.dateTaken > b.dateTaken || (a.dateTaken == b.dateTaken && a.id >= b.id)
    return if (aFirst) a to b else b to a
}
