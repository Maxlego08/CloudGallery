package fr.eva.cloudgallery.data.net

import android.net.ConnectivityManager

/**
 * Etat du réseau par défaut, pour décider des transferts coûteux (aperçus, originaux, copies
 * hors ligne). Le streaming vidéo est autorisé sur tout réseau.
 *
 * [unmetered] vient de `NET_CAPABILITY_NOT_METERED` (jamais déduit du type de transport : un
 * Wi-Fi peut être facturé, un réseau mobile gratuit). [dataSaver] : économiseur de données actif
 * pour l'appli (elle n'est pas sur la liste des exceptions).
 */
data class NetworkState(
    val connected: Boolean,
    val unmetered: Boolean,
    val dataSaver: Boolean,
) {
    /**
     * Réseau à traiter comme facturé : mesuré, économiseur de données actif, ou état inconnu /
     * sans réseau (prudence par défaut).
     */
    val costly: Boolean get() = !connected || !unmetered || dataSaver

    companion object {
        /** Etat pas encore lu : traité comme un réseau facturé. */
        val UNKNOWN = NetworkState(connected = false, unmetered = false, dataSaver = false)
    }
}

/**
 * Economiseur de données actif pour l'appli. `RESTRICT_BACKGROUND_STATUS_WHITELISTED` (appli
 * exemptée par l'utilisateur) et `DISABLED` ne comptent pas.
 */
internal fun isDataSaverOn(restrictBackgroundStatus: Int): Boolean =
    restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

/**
 * Etat à partir des capacités du réseau par défaut ([hasNetwork] faux : aucun réseau par défaut).
 * [internet] : capacité `NET_CAPABILITY_INTERNET` ; [notMetered] : `NET_CAPABILITY_NOT_METERED`.
 */
internal fun networkStateOf(
    hasNetwork: Boolean,
    internet: Boolean,
    notMetered: Boolean,
    restrictBackgroundStatus: Int,
): NetworkState {
    val connected = hasNetwork && internet
    return NetworkState(
        connected = connected,
        unmetered = connected && notMetered,
        dataSaver = isDataSaverOn(restrictBackgroundStatus),
    )
}
