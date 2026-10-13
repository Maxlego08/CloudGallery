package fr.eva.cloudgallery.ui.grid

import androidx.compose.ui.geometry.Offset
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import fr.eva.cloudgallery.ui.offline.OfflineMenuState
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GridSelectionLogicTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun periodBounds_day() {
        val day = LocalDate.of(2026, 10, 6)
        val (start, end) = periodBoundsMs(day.toEpochDay(), GridGrouping.DAY, paris)
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 0).atZone(paris).toInstant().toEpochMilli(), start)
        assertEquals(LocalDateTime.of(2026, 10, 7, 0, 0).atZone(paris).toInstant().toEpochMilli(), end)
    }

    @Test
    fun periodBounds_dayAcrossDstChangeIs25Hours() {
        // Switch to winter time on 2026-10-25 in Paris: 25-hour day.
        val (start, end) = periodBoundsMs(LocalDate.of(2026, 10, 25).toEpochDay(), GridGrouping.DAY, paris)
        assertEquals(25L * 3_600_000L, end - start)
    }

    @Test
    fun periodBounds_month() {
        val first = LocalDate.of(2026, 2, 1)
        val (start, end) = periodBoundsMs(first.toEpochDay(), GridGrouping.MONTH, paris)
        assertEquals(first.atStartOfDay(paris).toInstant().toEpochMilli(), start)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(paris).toInstant().toEpochMilli(), end)
    }

    @Test
    fun periodBounds_matchGrouping() {
        // Any instant in the period maps to the same header.
        val epochDay = LocalDate.of(2026, 10, 6).toEpochDay()
        val (start, end) = periodBoundsMs(epochDay, GridGrouping.DAY, paris)
        assertEquals(epochDay, periodStartEpochDay(start, GridGrouping.DAY, paris))
        assertEquals(epochDay, periodStartEpochDay(end - 1, GridGrouping.DAY, paris))
        assertEquals(epochDay + 1, periodStartEpochDay(end, GridGrouping.DAY, paris))
    }

    @Test
    fun dayCheck_usesDatabaseIdsWhenResolved() {
        val loaded = LoadedDay(listOf(1L, 2L), complete = false)
        assertTrue(isDayFullySelected(listOf(1L, 2L, 3L), loaded, setOf(1L, 2L, 3L)))
        assertFalse(isDayFullySelected(listOf(1L, 2L, 3L), loaded, setOf(1L, 2L)))
        assertFalse(isDayFullySelected(emptyList(), loaded, setOf(1L, 2L)))
    }

    @Test
    fun dayCheck_neverCheckedOnTruncatedLoadedDay() {
        val truncated = LoadedDay(listOf(1L, 2L), complete = false)
        assertFalse(isDayFullySelected(null, truncated, setOf(1L, 2L)))
        val complete = LoadedDay(listOf(1L, 2L), complete = true)
        assertTrue(isDayFullySelected(null, complete, setOf(1L, 2L)))
        assertFalse(isDayFullySelected(null, complete, setOf(1L)))
        assertFalse(isDayFullySelected(null, LoadedDay(emptyList(), complete = true), emptySet()))
    }

    private fun summary(id: Long, hasLocal: Boolean, hasCloud: Boolean) = MediaSummary(
        id = id, type = MediaType.IMAGE, mimeType = "image/jpeg", dateTaken = 0, durationMs = 0, width = 1, height = 1,
        orientation = 0, uri = null, dateModified = 0, isFavorite = false, hasLocal = hasLocal, hasCloud = hasCloud,
        hasOffline = false,
    )

    @Test
    fun localActionTarget_requiresVisibleLocalFileAndUri() {
        assertTrue(isLocalActionTarget(hasLocal = true, hasUri = true))
        // Only on OneDrive.
        assertFalse(isLocalActionTarget(hasLocal = false, hasUri = false))
        // Local copy hidden (trash): no usable URI anymore.
        assertFalse(isLocalActionTarget(hasLocal = true, hasUri = false))
        assertFalse(isLocalActionTarget(hasLocal = false, hasUri = true))
    }

    @Test
    fun localActionTargets_excludeCloudOnlyItems() {
        // Without a URI (impossible to build on the JVM), no item is a local target.
        val items = listOf(summary(1, hasLocal = false, hasCloud = true), summary(2, hasLocal = true, hasCloud = true))
        assertTrue(localActionTargets(items).isEmpty())
    }

    @Test
    fun actionCounts_useTargetsOnlyForTheSameSelection() {
        val selection = setOf(1L, 2L, 3L)
        assertEquals(ActionCounts(local = 1, skippedCloud = 2), actionCounts(selection, SelectionTargets(selection, 1)))
        // Computation not up to date yet: the whole selection, the action will filter by itself.
        assertEquals(ActionCounts(local = 3, skippedCloud = 0), actionCounts(selection, SelectionTargets(setOf(1L), 1)))
        assertEquals(ActionCounts(local = 3, skippedCloud = 0), actionCounts(selection, null))
        // Inconsistent value clamped.
        assertEquals(ActionCounts(local = 3, skippedCloud = 0), actionCounts(selection, SelectionTargets(selection, 9)))
    }

    @Test
    fun localActions_disabledForCloudOnlySelection() {
        val selection = setOf(1L, 2L)
        assertFalse(localActionsEnabled(selection, SelectionTargets(selection, 0)))
        assertTrue(localActionsEnabled(selection, SelectionTargets(selection, 1)))
        assertTrue(localActionsEnabled(selection, null))
        assertFalse(localActionsEnabled(emptySet(), null))
        assertFalse(localActionsEnabled(emptySet(), SelectionTargets(emptySet(), 0)))
    }

    @Test
    fun share_enabledForCloudItems_disabledWithoutAnyShareableItem() {
        val selection = setOf(1L, 2L)
        // All OneDrive: nothing to delete, but shareable (original downloaded).
        assertFalse(localActionsEnabled(selection, SelectionTargets(selection, local = 0, shareable = 2)))
        assertTrue(shareEnabled(selection, SelectionTargets(selection, local = 0, shareable = 2)))
        // No shareable source (hidden items): disabled.
        assertFalse(shareEnabled(selection, SelectionTargets(selection, local = 0, shareable = 0)))
        // Computation in progress or for another selection: the action filters by itself.
        assertTrue(shareEnabled(selection, null))
        assertTrue(shareEnabled(selection, SelectionTargets(setOf(1L), local = 0, shareable = 0)))
        assertFalse(shareEnabled(emptySet(), null))
        // By default, shareable = local (compatibility).
        assertEquals(1, SelectionTargets(selection, 1).shareable)
    }

    @Test
    fun contentCoordinates_removeTopPadding() {
        // Finger 300 px from the top of the container, 250 px padding: 50 px into the content.
        assertEquals(Offset(12f, 50f), toContentCoordinates(Offset(12f, 300f), 250))
        assertEquals(Offset(0f, -20f), toContentCoordinates(Offset(0f, 230f), 250))
        assertEquals(Offset(5f, 5f), toContentCoordinates(Offset(5f, 5f), 0))
    }

    @Test
    fun offlineMenu_onlyFromCountsOfTheSameSelection() {
        val selection = setOf(1L, 2L)
        // Computation in progress (or for another selection): no offline entry.
        assertEquals(OfflineMenuState.NONE, selectionOfflineMenu(selection, null))
        assertEquals(
            OfflineMenuState.NONE,
            selectionOfflineMenu(selection, SelectionTargets(setOf(1L), local = 0, shareable = 1, keepableOffline = 1)),
        )
        assertEquals(
            OfflineMenuState.KEEP,
            selectionOfflineMenu(selection, SelectionTargets(selection, local = 0, shareable = 2, keepableOffline = 2)),
        )
        assertEquals(
            OfflineMenuState.BOTH,
            selectionOfflineMenu(selection, SelectionTargets(selection, local = 0, shareable = 2, keepableOffline = 1, withOfflineCopy = 1)),
        )
        assertEquals(
            OfflineMenuState.RELEASE,
            selectionOfflineMenu(selection, SelectionTargets(selection, local = 0, shareable = 2, withOfflineCopy = 2)),
        )
        assertEquals(OfflineMenuState.NONE, selectionOfflineMenu(emptySet(), SelectionTargets(emptySet(), 0, 0)))
    }
}
