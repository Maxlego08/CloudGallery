package fr.eva.cloudgallery.data.cloud.transfer

/*
 * Logique pure des transferts OneDrive (testée en JVM).
 */

/**
 * Durée de réutilisation d'une URL `@microsoft.graph.downloadUrl`. La documentation Graph
 * annonce une validité courte (« might expire within minutes ») : l'URL est redemandée au-delà
 * de deux minutes, et aussitôt après un refus de l'hôte de téléchargement ([isExpiredUrlStatus]).
 */
internal const val DOWNLOAD_URL_TTL_MS = 2 * 60_000L

/** Une URL obtenue à [fetchedAtMs] peut encore servir à [nowMs] (horloge monotone). */
internal fun isUrlFresh(fetchedAtMs: Long, nowMs: Long, ttlMs: Long = DOWNLOAD_URL_TTL_MS): Boolean {
    val age = nowMs - fetchedAtMs
    // Horloge revenue en arrière (ne devrait pas arriver avec une horloge monotone) : non fiable.
    return age in 0 until ttlMs
}

/**
 * Statuts de l'hôte de téléchargement qui signalent probablement une URL expirée ou révoquée (le
 * statut exact n'est pas documenté) : une nouvelle URL est demandée à Graph avant de réessayer.
 */
internal fun isExpiredUrlStatus(code: Int): Boolean = code == 401 || code == 403 || code == 404 || code == 410

/** En-tête `Content-Range` d'une réponse 206/416 : `bytes start-end/total` (ou total seul pour un 416). */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

/**
 * Lecture de `Content-Range` (RFC 9110). Forme non satisfaisable (astérisque puis `/2048`) :
 * start = end = -1. Null si absent ou illisible.
 */
internal fun parseContentRange(header: String?): ContentRange? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.substring(6).trim()
    val slash = spec.indexOf('/')
    if (slash < 0) return null
    val totalPart = spec.substring(slash + 1).trim()
    val total = if (totalPart == "*") null else totalPart.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val rangePart = spec.substring(0, slash).trim()
    if (rangePart == "*") return if (total != null) ContentRange(-1, -1, total) else null
    val dash = rangePart.indexOf('-')
    if (dash <= 0) return null
    val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
    val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    if (total != null && end >= total) return null
    return ContentRange(start, end, total)
}

/** Que faire de la réponse de l'hôte de téléchargement à une requête commençant à [offset]. */
internal sealed interface RangeOutcome {
    /** 206 cohérent : les octets reçus s'ajoutent au fichier partiel. */
    data object Append : RangeOutcome

    /** 200 : contenu complet depuis le début (Range ignoré ou absent) ; le fichier partiel est vidé. */
    data object RestartFromZero : RangeOutcome

    /** 416 alors que le fichier partiel a déjà la taille attendue : rien à télécharger. */
    data object AlreadyComplete : RangeOutcome

    /** Réponse incohérente : le fichier partiel ne peut pas être complété sûrement. */
    data class Invalid(val reason: String) : RangeOutcome
}

/**
 * Classement d'une réponse 2xx/416 de l'hôte de téléchargement pour une requête depuis [offset]
 * ([expectedSize] = taille annoncée par Graph). Graph documente qu'un Range peut être ignoré
 * (200 avec tout le contenu) : le fichier repart alors de zéro.
 */
internal fun classifyRangeResponse(code: Int, contentRange: ContentRange?, offset: Long, expectedSize: Long): RangeOutcome = when (code) {
    200 -> RangeOutcome.RestartFromZero
    206 -> when {
        contentRange == null -> RangeOutcome.Invalid("206 sans Content-Range")
        contentRange.start != offset -> RangeOutcome.Invalid("206 hors position")
        contentRange.total != null && expectedSize > 0 && contentRange.total != expectedSize ->
            RangeOutcome.Invalid("206 de taille inattendue")
        else -> RangeOutcome.Append
    }
    416 -> if (offset > 0 && offset == expectedSize && (contentRange?.total == null || contentRange.total == expectedSize)) {
        RangeOutcome.AlreadyComplete
    } else {
        RangeOutcome.Invalid("416")
    }
    else -> RangeOutcome.Invalid("HTTP $code")
}

/** Reprise d'un fichier partiel. */
internal sealed interface ResumePlan {
    data object FromZero : ResumePlan
    data class From(val offset: Long) : ResumePlan
    data object Complete : ResumePlan
}

/**
 * Plan de reprise d'un fichier partiel de [partLength] octets commencé pour la version
 * [partCTag], alors que l'élément est maintenant en version [currentCTag] avec [expectedSize]
 * octets. Une version inconnue (null) d'un côté ou de l'autre n'est jamais considérée comme
 * identique : reprise de zéro, comme après un changement de version ou un fichier trop long.
 */
internal fun resumePlan(partLength: Long, expectedSize: Long, partCTag: String?, currentCTag: String?): ResumePlan = when {
    partLength <= 0 -> ResumePlan.FromZero
    partCTag == null || currentCTag == null || partCTag != currentCTag -> ResumePlan.FromZero
    expectedSize <= 0 || partLength > expectedSize -> ResumePlan.FromZero
    partLength == expectedSize -> ResumePlan.Complete
    else -> ResumePlan.From(partLength)
}

/**
 * Nom de fichier d'une copie OneDrive : `<sha1(itemId)[0..16]>_<sha1(cTag)[0..8]>.<ext>`. Aucun
 * nom d'utilisateur ni caractère de chemin : l'extension (minuscules, 1 à 5 caractères [a-z0-9])
 * vient du nom affiché, sinon du type MIME, sinon `bin`. Une nouvelle version donne un nouveau nom.
 */
internal fun cacheFileName(itemId: String, cTag: String?, displayName: String, mimeType: String?): String {
    val idHash = sha1Hex(itemId).take(16)
    val versionHash = sha1Hex(cTag ?: "").take(8)
    return "${idHash}_$versionHash.${fileExtension(displayName, mimeType)}"
}

private val EXTENSION = Regex("^[a-z0-9]{1,5}$")

/** Extension sûre d'un fichier : celle du nom, sinon d'après le type MIME, sinon `bin`. */
internal fun fileExtension(displayName: String, mimeType: String?): String {
    val fromName = displayName.substringAfterLast('.', "").lowercase()
    if (displayName.contains('.') && EXTENSION.matches(fromName)) return fromName
    val fromMime = when (mimeType?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        "image/x-adobe-dng", "image/dng" -> "dng"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> mimeType?.substringAfter('/', "")?.lowercase()?.takeIf { EXTENSION.matches(it) }
    }
    return fromMime ?: "bin"
}

/**
 * Nom affiché sûr pour l'appli qui reçoit un partage : sans séparateur de chemin ni caractère
 * réservé ou de contrôle, sans `.`/`..` seuls, au plus 120 caractères, jamais vide.
 */
internal fun safeDisplayName(name: String): String {
    val cleaned = name
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .trim()
        .trimStart('.')
        .trim()
    val capped = if (cleaned.length <= MAX_DISPLAY_NAME) {
        cleaned
    } else {
        // Coupe en gardant l'extension (et sans couper une paire de substitution).
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
        val keep = MAX_DISPLAY_NAME - (ext?.length?.plus(1) ?: 0)
        var base = cleaned.take(keep)
        if (base.isNotEmpty() && Character.isHighSurrogate(base.last())) base = base.dropLast(1)
        if (ext != null) "$base.$ext" else base
    }
    return capped.ifEmpty { "fichier" }
}

private const val MAX_DISPLAY_NAME = 120

/**
 * Progression à publier (Room, notification, UI) : au plus une fois par seconde ou tous les
 * 4 Mio, et toujours à la fin du téléchargement ([bytes] atteint [totalBytes]).
 */
internal fun shouldEmitProgress(lastEmitMs: Long, nowMs: Long, lastBytes: Long, bytes: Long, totalBytes: Long = -1): Boolean =
    (totalBytes > 0 && bytes >= totalBytes) ||
        nowMs - lastEmitMs >= PROGRESS_MIN_INTERVAL_MS ||
        bytes - lastBytes >= PROGRESS_MIN_BYTES

private const val PROGRESS_MIN_INTERVAL_MS = 1_000L
private const val PROGRESS_MIN_BYTES = 4L * 1024 * 1024

/** Suffixe des fichiers partiels (téléchargement en cours ou à reprendre). */
internal const val PART_SUFFIX = ".part"

/** Fichiers partiels abandonnés supprimés au nettoyage au-delà de cet âge (24 h). */
internal const val PART_MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Fichiers sans ligne en base supprimés au nettoyage au-delà de cet âge (1 h) : une copie juste
 * renommée mais pas encore enregistrée est toujours plus récente.
 */
internal const val ORPHAN_MIN_AGE_MS = 60L * 60 * 1000

/** Fichier d'un dossier de copies vu par le nettoyage : nom, fichier ordinaire, âge (ms). */
internal data class SweepEntry(val name: String, val isFile: Boolean, val ageMs: Long)

/**
 * Fichiers à supprimer au nettoyage d'un dossier de copies de l'appli : jamais un fichier
 * enregistré ([known] : noms des lignes `cached_file`), ni un fichier (ou son fichier partiel) en
 * cours d'utilisation ([busy] : noms de base des téléchargements en cours ou à reprendre), ni
 * un dossier ; fichiers partiels abandonnés depuis plus de [partMaxAgeMs], autres fichiers sans
 * ligne depuis plus de [orphanMinAgeMs].
 */
internal fun filesToSweep(
    entries: List<SweepEntry>,
    known: Set<String>,
    busy: Set<String>,
    partMaxAgeMs: Long = PART_MAX_AGE_MS,
    orphanMinAgeMs: Long = ORPHAN_MIN_AGE_MS,
): List<String> = entries.filter { entry ->
    val base = entry.name.removeSuffix(PART_SUFFIX)
    if (!entry.isFile || entry.name in known || base in busy) return@filter false
    if (entry.name.endsWith(PART_SUFFIX)) entry.ageMs > partMaxAgeMs else entry.ageMs > orphanMinAgeMs
}.map { it.name }

private fun sha1Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
