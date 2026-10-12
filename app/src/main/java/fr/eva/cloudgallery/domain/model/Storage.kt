package fr.eva.cloudgallery.domain.model

/**
 * Espace occupé par l'appli sur l'appareil, par niveau (page Stockage). Tailles mesurées sur le
 * disque (fichiers partiels et fichiers en attente de nettoyage compris), en octets.
 */
data class StorageUsage(
    /** Cache disque Coil : miniatures et aperçus OneDrive. */
    val thumbnailBytes: Long,
    /** Plafond du cache des miniatures en vigueur (ouvert au démarrage), null s'il est inconnu. */
    val thumbnailMaxBytes: Long?,
    /**
     * Mise en cache de tout OneDrive : vignettes et aperçus gardés hors du plafond ci-dessus
     * ([precacheFiles] fichiers).
     */
    val precacheBytes: Long,
    val precacheFiles: Int,
    /** Originaux OneDrive temporaires (zoom profond, partage). */
    val originalBytes: Long,
    val originalCount: Int,
    /** Copies hors ligne (épinglées : jamais vidées avec le cache). */
    val offlineBytes: Long,
    val offlineCount: Int,
    /** Espace encore libre sur le volume de l'appli. */
    val freeBytes: Long,
) {
    /** Cache vidable (miniatures, aperçus, mise en cache de tout OneDrive et originaux). */
    val cacheBytes: Long get() = thumbnailBytes + precacheBytes + originalBytes
}
