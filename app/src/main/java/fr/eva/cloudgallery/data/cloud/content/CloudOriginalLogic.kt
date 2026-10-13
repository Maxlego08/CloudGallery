package fr.eva.cloudgallery.data.cloud.content

/*
 * Logique pure du cache temporaire des originaux OneDrive (testée en JVM).
 */

/** Espace libre gardé sur l'appareil en plus d'un original téléchargé (50 Mio). */
internal const val ORIGINAL_SPACE_MARGIN_BYTES = 50L * 1024 * 1024

/**
 * Un original utilisé depuis moins longtemps (affiché, partagé, ouvert dans une autre appli) n'est
 * pas évincé pour respecter le plafond : l'appli qui le lit par le FileProvider peut le faire
 * quelques minutes plus tard. Le plafond peut donc être dépassé un moment.
 */
internal const val ORIGINAL_IN_USE_GRACE_MS = 30L * 60 * 1000

/**
 * Ligne `cached_file` d'un original, vue par le nettoyage ([fileExists] : fichier présent ;
 * [pinned] : jamais supprimée par le nettoyage ni comptée dans le plafond).
 */
internal data class TrimRow(
    val id: Long,
    val path: String,
    val sizeBytes: Long,
    val lastAccessAt: Long,
    val fileExists: Boolean,
    val pinned: Boolean = false,
)

/** Lignes à supprimer ([drop], avec leur fichier) et à garder ([keep]) au nettoyage. */
internal data class TrimPlan(val drop: List<TrimRow>, val keep: List<TrimRow>)

/**
 * Nettoyage LRU du cache des originaux : lignes sans fichier, originaux inutilisés depuis plus
 * de [maxAgeMs], puis les moins récemment utilisés tant que le total (lignes non épinglées)
 * dépasse [maxBytes]. Jamais retirés : un fichier en cours de téléchargement ([busy]), une ligne
 * épinglée ; pas d'éviction pour le plafond d'un original utilisé depuis moins de [inUseGraceMs].
 */
internal fun planOriginalTrim(
    rows: List<TrimRow>,
    busy: Set<String>,
    now: Long,
    maxBytes: Long,
    maxAgeMs: Long,
    inUseGraceMs: Long = ORIGINAL_IN_USE_GRACE_MS,
): TrimPlan {
    val drop = ArrayList<TrimRow>()
    val keep = ArrayList<TrimRow>()
    for (row in rows) {
        when {
            row.path in busy || row.pinned -> keep += row
            !row.fileExists -> drop += row
            now - row.lastAccessAt > maxAgeMs -> drop += row
            else -> keep += row
        }
    }
    var total = keep.filter { !it.pinned }.sumOf { it.sizeBytes }
    val evicted = HashSet<Long>()
    for (row in keep.sortedWith(compareBy({ it.lastAccessAt }, { it.id }))) {
        if (total <= maxBytes) break
        if (row.path in busy || row.pinned || now - row.lastAccessAt < inUseGraceMs) continue
        evicted += row.id
        total -= row.sizeBytes
    }
    return TrimPlan(drop = drop + keep.filter { it.id in evicted }, keep = keep.filter { it.id !in evicted })
}

/**
 * Autre nom pour un original dont le nom habituel [name] est déjà pris par un fichier (version
 * inconnue, ou fichier précédent non réutilisable) : `<base>-<nonce>.<ext>`. Le fichier existant
 * n'est jamais écrasé avant l'enregistrement du nouveau (il peut être partagé à une autre appli).
 */
internal fun alternateFileName(name: String, nonce: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name-$nonce" else "${name.substring(0, dot)}-$nonce${name.substring(dot)}"
}
