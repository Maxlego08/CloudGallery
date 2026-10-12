package fr.eva.cloudgallery.domain.model

/**
 * Copie locale qu'on peut supprimer sans perte : le même contenu (empreinte QuickXorHash et
 * taille identiques) est sur OneDrive. [sourceId] : source LOCAL concernée ; [uri] : URI
 * MediaStore envoyée à la corbeille Android.
 */
data class SpaceSaverCandidate(
    val sourceId: Long,
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val isVideo: Boolean,
    val displayName: String,
    val dateTaken: Long,
)

/**
 * Copies proposées par l'économiseur d'espace. [excludedUserData] : copies vérifiées mais
 * écartées parce que leur média (favori ou membre d'un album) n'est pas encore regroupé avec
 * l'élément OneDrive : les supprimer ferait perdre ce favori ou cet album.
 */
data class SpaceSaverList(
    val candidates: List<SpaceSaverCandidate>,
    val excludedUserData: Int,
)

/** Filtre d'ancienneté de l'économiseur d'espace (date de prise de vue). */
enum class SaverAge(val minAgeDays: Int?) {
    ALL(null),
    DAYS_30(30),
    DAYS_90(90),
    YEAR(365),
}
