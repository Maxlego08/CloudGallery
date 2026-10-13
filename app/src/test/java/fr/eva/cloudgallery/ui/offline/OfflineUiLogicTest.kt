package fr.eva.cloudgallery.ui.offline

import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OfflineUiLogicTest {

    private val ids = listOf(1L, 2L)

    @Test
    fun keep_enqueuedOnWifi_givesShortMessage() {
        assertEquals(
            OfflineFeedback.Plural(R.plurals.offline_enqueued, 2),
            keepFeedback(KeepOfflineResult.Enqueued(2, 100, waitingForUnmetered = false), ids),
        )
    }

    @Test
    fun keep_waitingForWifi_offersDownloadNow() {
        assertEquals(
            OfflineFeedback.Prompt(OfflinePrompt.WaitingForWifi(2, 100)),
            keepFeedback(KeepOfflineResult.Enqueued(2, 100, waitingForUnmetered = true), ids),
        )
    }

    @Test
    fun keep_largeBatch_asksConfirmationWithSameIds() {
        assertEquals(
            OfflineFeedback.Prompt(OfflinePrompt.ConfirmLargeBatch(ids, 300, 5_000, 9_000)),
            keepFeedback(KeepOfflineResult.NeedsConfirmation(300, 5_000, 9_000), ids),
        )
    }

    @Test
    fun keep_noSpace_offersStorageManagement() {
        val uuid = UUID.randomUUID()
        assertEquals(
            OfflineFeedback.Prompt(OfflinePrompt.NoSpace(42, uuid)),
            keepFeedback(KeepOfflineResult.NoSpace(42, uuid), ids),
        )
    }

    @Test
    fun keep_otherOutcomes_giveMessages() {
        assertEquals(OfflineFeedback.Text(R.string.offline_not_signed_in), keepFeedback(KeepOfflineResult.NotSignedIn, ids))
        assertEquals(OfflineFeedback.Text(R.string.offline_nothing_to_do), keepFeedback(KeepOfflineResult.NothingToDo, ids))
        assertEquals(OfflineFeedback.Text(R.string.offline_scheduling_refused), keepFeedback(KeepOfflineResult.SchedulingRefused(2), ids))
    }

    @Test
    fun release_confirmsOnlyLastCopies() {
        assertEquals(ReleaseStep.Release, releaseStep(ids, lastCopies = 0))
        assertEquals(ReleaseStep.Confirm(OfflinePrompt.ConfirmRelease(ids, 1)), releaseStep(ids, lastCopies = 1))
    }

    @Test
    fun release_unknownLastCopies_neverReleasesWithoutCheck() {
        assertEquals(ReleaseStep.Abort, releaseStep(ids, lastCopies = null))
    }

    @Test
    fun purgeDialog_confirmDisabledWhileLoading_andWarnsWhenUnknown() {
        assertFalse(purgeConfirmEnabled(PurgeImpactUi.Loading))
        assertEquals(PurgeNotice.None, purgeNotice(PurgeImpactUi.Loading))
        // Lecture impossible : confirmation possible, avec un avertissement prudent.
        assertTrue(purgeConfirmEnabled(PurgeImpactUi.Unknown))
        assertEquals(PurgeNotice.Unknown, purgeNotice(PurgeImpactUi.Unknown))
        assertTrue(purgeConfirmEnabled(PurgeImpactUi.Known(0, 0)))
        assertEquals(PurgeNotice.None, purgeNotice(PurgeImpactUi.Known(0, 0)))
        assertEquals(PurgeNotice.Copies(3, 1), purgeNotice(PurgeImpactUi.Known(3, 1)))
    }

    @Test
    fun menuState_fromKeepableAndReleasableCounts() {
        assertEquals(OfflineMenuState.NONE, offlineMenuState(0, 0))
        assertEquals(OfflineMenuState.KEEP, offlineMenuState(3, 0))
        assertEquals(OfflineMenuState.RELEASE, offlineMenuState(0, 2))
        assertEquals(OfflineMenuState.BOTH, offlineMenuState(1, 1))
    }
}
