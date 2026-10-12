package fr.eva.cloudgallery.data.cloud.content

/*
 * Logique pure du cache temporaire des originaux OneDrive (testée en JVM).
 */

/** Espace libre gardé sur l'appareil en plus d'un original téléchargé (50 Mio). */
internal const val ORIGINAL_SPACE_MARGIN_BYTES = 50L * 1024 * 1024

/** Ligne `cached_file` d'un original, vue par le nettoyage ([fileExists] : fichier présent). */
internal data class TrimRow(
    val id: Long,
    val path: String,
    val sizeBytes: Long,
    val lastAccessAt: Long,
    val fileExists: Boolean,
)

/** Lignes à supprimer ([drop], avec leur fichier) et à garder ([keep]) au nettoyage. */
internal data class TrimPlan(val drop: List<TrimRow>, val keep: List<TrimRow>)

/**
 * Nettoyage du cache des originaux (phase 4, avant la LRU de la phase 5) : lignes sans fichier,
 * originaux inutilisés depuis plus de [maxAgeMs], puis les moins récemment utilisés tant que le
 * total dépasse [maxBytes]. Un fichier en cours de téléchargement ([busy]) n'est jamais retiré.
 */
internal fun planOriginalTrim(rows: List<TrimRow>, busy: Set<String>, now: Long, maxBytes: Long, maxAgeMs: Long): TrimPlan {
    val drop = ArrayList<TrimRow>()
    val keep = ArrayList<TrimRow>()
    for (row in rows) {
        when {
            row.path in busy -> keep += row
            !row.fileExists -> drop += row
            now - row.lastAccessAt > maxAgeMs -> drop += row
            else -> keep += row
        }
    }
    var total = keep.sumOf { it.sizeBytes }
    val evicted = HashSet<Long>()
    for (row in keep.sortedWith(compareBy({ it.lastAccessAt }, { it.id }))) {
        if (total <= maxBytes) break
        if (row.path in busy) continue
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
