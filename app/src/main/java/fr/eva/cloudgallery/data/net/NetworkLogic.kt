package fr.eva.cloudgallery.data.net

import android.net.ConnectivityManager

/**
 * State of the default network, used to decide on costly transfers (previews, originals,
 * offline copies). Video streaming is allowed on any network.
 *
 * [unmetered] comes from `NET_CAPABILITY_NOT_METERED` (never inferred from the transport type:
 * a Wi-Fi network can be metered, a mobile network free). [dataSaver]: Data Saver is active for
 * the app (it is not on the exceptions list).
 */
data class NetworkState(
    val connected: Boolean,
    val unmetered: Boolean,
    val dataSaver: Boolean,
) {
    /**
     * Network to be treated as metered: metered, Data Saver active, or unknown state / no
     * network (cautious by default).
     */
    val costly: Boolean get() = !connected || !unmetered || dataSaver

    companion object {
        /** State not read yet: treated as a metered network. */
        val UNKNOWN = NetworkState(connected = false, unmetered = false, dataSaver = false)
    }
}

/**
 * Data Saver active for the app. `RESTRICT_BACKGROUND_STATUS_WHITELISTED` (app exempted by the
 * user) and `DISABLED` do not count.
 */
internal fun isDataSaverOn(restrictBackgroundStatus: Int): Boolean =
    restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

/**
 * State derived from the default network's capabilities ([hasNetwork] false: no default network).
 * [internet]: `NET_CAPABILITY_INTERNET` capability; [notMetered]: `NET_CAPABILITY_NOT_METERED`.
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
