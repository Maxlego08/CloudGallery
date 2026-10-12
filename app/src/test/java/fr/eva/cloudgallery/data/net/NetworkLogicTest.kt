package fr.eva.cloudgallery.data.net

import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLogicTest {

    @Test
    fun dataSaver_onlyWhenEnabledForTheApp() {
        assertTrue(isDataSaverOn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
        // Appli exemptée par l'utilisateur : l'économiseur ne s'applique pas à elle.
        assertFalse(isDataSaverOn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED))
        assertFalse(isDataSaverOn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED))
    }

    @Test
    fun unmeteredWifi_isNotCostly() {
        val state = networkStateOf(
            hasNetwork = true, internet = true, notMetered = true,
            restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
        assertEquals(NetworkState(connected = true, unmetered = true, dataSaver = false), state)
        assertFalse(state.costly)
    }

    @Test
    fun meteredNetwork_isCostly() {
        val state = networkStateOf(
            hasNetwork = true, internet = true, notMetered = false,
            restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
        assertTrue(state.connected)
        assertTrue(state.costly)
    }

    @Test
    fun dataSaver_makesEvenUnmeteredCostly() {
        val state = networkStateOf(
            hasNetwork = true, internet = true, notMetered = true,
            restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
        assertTrue(state.unmetered)
        assertTrue(state.costly)
    }

    @Test
    fun noNetwork_orNoInternet_orUnknown_isCostly() {
        val none = networkStateOf(
            hasNetwork = false, internet = false, notMetered = false,
            restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
        assertFalse(none.connected)
        assertTrue(none.costly)
        val noInternet = networkStateOf(
            hasNetwork = true, internet = false, notMetered = true,
            restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
        assertFalse(noInternet.connected)
        assertFalse(noInternet.unmetered)
        assertTrue(noInternet.costly)
        assertTrue(NetworkState.UNKNOWN.costly)
    }
}
