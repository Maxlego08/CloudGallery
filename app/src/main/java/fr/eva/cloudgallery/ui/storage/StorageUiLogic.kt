package fr.eva.cloudgallery.ui.storage

/*
 * Logique pure de la page Stockage (testée en JVM).
 */

/**
 * Plafond des miniatures modifié depuis l'ouverture du cache Coil : le nouveau plafond ne
 * s'applique qu'au prochain démarrage. [effectiveBytes] null (cache pas encore ouvert) : le
 * réglage sera lu à l'ouverture, rien n'est en attente.
 */
internal fun thumbnailCapPendingRestart(settingBytes: Long, effectiveBytes: Long?): Boolean =
    effectiveBytes != null && effectiveBytes != settingBytes

/** « Vider le cache » proposé seulement s'il y a quelque chose à vider et rien en cours. */
internal fun canClearCache(cacheBytes: Long?, clearing: Boolean): Boolean =
    !clearing && cacheBytes != null && cacheBytes > 0
