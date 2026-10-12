package fr.eva.cloudgallery.data.dedup

/*
 * Logique pure du dédoublonnage local / OneDrive (testée en JVM).
 *
 * Règle de fond : un fichier du téléphone et un élément OneDrive ne sont un même média que si
 * leur QuickXorHash ET leur taille sont identiques. Le nom, le dossier et la date n'entrent
 * jamais en compte. La fusion ne modifie que l'index : aucun fichier n'est touché.
 */

/** Préfixe de `media.contentHash` pour une empreinte QuickXorHash (même valeur que OneDrive). */
internal const val QUICK_XOR_PREFIX = "qxh:"

/** `media.contentHash` d'une empreinte QuickXorHash, null si inconnue. */
internal fun contentHashOf(quickXorHash: String?): String? = quickXorHash?.let { QUICK_XOR_PREFIX + it }

/**
 * Signature d'un fichier local au moment du calcul de son empreinte (`media_source.hashSignature`) :
 * taille, date de modification et génération MediaStore. Une autre valeur pour l'état courant de
 * la source rend l'empreinte périmée. Même forme que l'expression SQL [LOCAL_HASH_SIGNATURE_SQL].
 */
internal fun localHashSignature(sizeBytes: Long, dateModified: Long, generation: Long): String =
    "$sizeBytes:$dateModified:$generation"

/**
 * Signature courante d'une ligne `media_source` en SQL (préfixe de table compris dans les
 * requêtes qui l'insèrent) ; doit rester identique à [localHashSignature].
 */
internal const val LOCAL_HASH_SIGNATURE_SQL = "(sizeBytes || ':' || dateModified || ':' || generation)"

/**
 * Couple candidat à la fusion : fichier local (empreinte à jour, média sans source OneDrive) et
 * élément OneDrive (média sans fichier local) de même empreinte et même taille.
 */
data class MergeCandidateRow(
    val localSourceId: Long,
    val localMediaId: Long,
    val cloudSourceId: Long,
    val cloudMediaId: Long,
    val quickXorHash: String,
    val sizeBytes: Long,
)

/** Fusion à faire : le média OneDrive [cloudMediaId] rejoint le média local [localMediaId]. */
data class MergePlan(val localMediaId: Long, val cloudMediaId: Long, val cloudSourceId: Long)

/**
 * Fusions à faire parmi [candidates]. Chaque média n'est fusionné qu'une fois. Plusieurs fichiers
 * locaux identiques, ou plusieurs éléments OneDrive identiques (copies du même fichier) : règle
 * déterministe, par empreinte et taille, le i-ème fichier local (ordre des identifiants de
 * source) reçoit le i-ème élément OneDrive ; les éléments en surnombre restent séparés.
 */
internal fun planMerges(candidates: List<MergeCandidateRow>): List<MergePlan> {
    val plans = ArrayList<MergePlan>()
    candidates.groupBy { it.quickXorHash to it.sizeBytes }.values.forEach { group ->
        val locals = group.map { it.localSourceId to it.localMediaId }.distinct().sortedBy { it.first }
        val clouds = group.map { it.cloudSourceId to it.cloudMediaId }.distinct().sortedBy { it.first }
        locals.zip(clouds).forEach { (local, cloud) ->
            plans += MergePlan(localMediaId = local.second, cloudMediaId = cloud.second, cloudSourceId = cloud.first)
        }
    }
    // Un média ne peut entrer que dans une fusion (deux groupes ne partagent jamais de source,
    // mais un média local ou OneDrive pourrait apparaître dans deux groupes par une donnée incohérente).
    val usedLocal = HashSet<Long>()
    val usedCloud = HashSet<Long>()
    return plans.sortedBy { it.cloudSourceId }.filter { usedLocal.add(it.localMediaId) && usedCloud.add(it.cloudMediaId) }
}

/** Média fusionné vu par la vérification : sa source locale et sa source OneDrive. */
data class MergedRow(
    val mediaId: Long,
    val localSizeBytes: Long,
    val localQuickXorHash: String?,
    /** Empreinte locale calculée pour l'état actuel du fichier (signature à jour). */
    val localHashCurrent: Boolean,
    val cloudSourceId: Long,
    val cloudItemId: String,
    val cloudSizeBytes: Long,
    val cloudQuickXorHash: String?,
)

/**
 * La fusion doit être défaite : tailles différentes (fichier modifié d'un côté, certain), ou
 * deux empreintes connues et différentes (empreinte locale recalculée après une modification,
 * ou contenu OneDrive modifié). Une empreinte locale périmée mais pas encore recalculée (fichier
 * touché sans changement de taille, ex. métadonnées) ne suffit pas : la fusion est gardée
 * jusqu'au prochain calcul (en charge), qui tranche.
 */
internal fun shouldSplit(row: MergedRow): Boolean {
    if (row.localSizeBytes != row.cloudSizeBytes) return true
    val local = row.localQuickXorHash?.takeIf { row.localHashCurrent } ?: return false
    val cloud = row.cloudQuickXorHash ?: return false
    return local != cloud
}

/** Fichier local dont l'empreinte est à calculer. */
data class HashTarget(
    val sourceId: Long,
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val generation: Long,
) {
    val signature: String get() = localHashSignature(sizeBytes, dateModified, generation)
}

/**
 * Prochain lot d'empreintes : d'abord les fichiers dont la taille existe parmi les éléments
 * OneDrive visibles ([cloudSizes] : doublons probables, utiles au dédoublonnage et à
 * l'économiseur d'espace), puis les autres ; dans chaque groupe, ordre des identifiants. Au plus
 * [maxCount] fichiers et [maxBytes] octets (au moins un fichier, même plus gros que le budget).
 */
internal fun nextHashBatch(targets: List<HashTarget>, cloudSizes: Set<Long>, maxCount: Int, maxBytes: Long): List<HashTarget> {
    val ordered = targets.sortedWith(compareBy<HashTarget>({ it.sizeBytes !in cloudSizes }, { it.sourceId }))
    val batch = ArrayList<HashTarget>()
    var bytes = 0L
    for (t in ordered) {
        if (batch.size >= maxCount) break
        if (batch.isNotEmpty() && bytes + t.sizeBytes > maxBytes) break
        batch += t
        bytes += t.sizeBytes.coerceAtLeast(0L)
    }
    return batch
}
