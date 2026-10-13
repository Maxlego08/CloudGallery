package fr.eva.cloudgallery.ui.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage page: thumbnail cap pending restart, offered clearing. */
class StorageUiLogicTest {

    @Test
    fun thumbnailCap_pendingOnlyWhenTheOpenCacheUsesAnotherCap() {
        assertFalse(thumbnailCapPendingRestart(250_000_000L, 250_000_000L))
        assertTrue(thumbnailCapPendingRestart(500_000_000L, 250_000_000L))
        // Cache not opened yet: the setting will be read when it opens.
        assertFalse(thumbnailCapPendingRestart(500_000_000L, null))
    }

    @Test
    fun clearCache_offeredOnlyWithSomethingToClear_andNotTwice() {
        assertTrue(canClearCache(1L, clearing = false))
        assertFalse(canClearCache(0L, clearing = false))
        assertFalse(canClearCache(null, clearing = false))
        assertFalse(canClearCache(1_000L, clearing = true))
    }
}
