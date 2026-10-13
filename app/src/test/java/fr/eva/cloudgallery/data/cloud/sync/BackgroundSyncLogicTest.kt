package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccount
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Travail périodique de synchronisation OneDrive d'après le réglage et l'état du compte. */
class BackgroundSyncLogicTest {

    private val signedIn = CloudAccountState.SignedIn(CloudAccount("a@b.c", "id"))

    @Test
    fun signedIn_schedulesWithTheChosenIntervalAndNetwork() {
        assertEquals(
            BackgroundSyncPlan.Schedule(6, unmeteredOnly = false),
            backgroundSyncPlan(true, BackgroundSyncInterval.EVERY_6_HOURS, wifiOnly = false, account = signedIn),
        )
        assertEquals(
            BackgroundSyncPlan.Schedule(1, unmeteredOnly = true),
            backgroundSyncPlan(true, BackgroundSyncInterval.HOURLY, wifiOnly = true, account = signedIn),
        )
        // Session à renouveler : le travail reste planifié (le passage ne fait rien d'ici là).
        assertEquals(
            BackgroundSyncPlan.Schedule(24, unmeteredOnly = false),
            backgroundSyncPlan(true, BackgroundSyncInterval.DAILY, false, CloudAccountState.NeedsReauth(CloudAccount(null))),
        )
    }

    @Test
    fun cancelled_whenOff_signedOut_orWithoutOneDrive() {
        assertEquals(BackgroundSyncPlan.Cancel, backgroundSyncPlan(true, BackgroundSyncInterval.OFF, false, signedIn))
        assertEquals(
            BackgroundSyncPlan.Cancel,
            backgroundSyncPlan(true, BackgroundSyncInterval.EVERY_6_HOURS, false, CloudAccountState.SignedOut(hasLeftoverData = false)),
        )
        assertEquals(BackgroundSyncPlan.Cancel, backgroundSyncPlan(false, BackgroundSyncInterval.EVERY_6_HOURS, false, signedIn))
        assertEquals(
            BackgroundSyncPlan.Cancel,
            backgroundSyncPlan(true, BackgroundSyncInterval.EVERY_6_HOURS, false, CloudAccountState.NotConfigured),
        )
    }

    @Test
    fun transientAccountStates_changeNothing() {
        for (state in listOf(CloudAccountState.Loading, CloudAccountState.SigningOut, CloudAccountState.Error(CloudErrorCode.NETWORK))) {
            assertEquals(BackgroundSyncPlan.Unchanged, backgroundSyncPlan(true, BackgroundSyncInterval.EVERY_6_HOURS, false, state))
        }
    }
}
