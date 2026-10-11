package fr.eva.cloudgallery.ui.grid

import androidx.compose.ui.geometry.Offset
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // Passage à l'heure d'hiver le 25/10/2026 à Paris : journée de 25 h.
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
        // Tout instant de la période retombe sur le même en-tête.
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
        // Uniquement sur OneDrive.
        assertFalse(isLocalActionTarget(hasLocal = false, hasUri = false))
        // Copie locale masquée (corbeille) : plus d'URI exploitable.
        assertFalse(isLocalActionTarget(hasLocal = true, hasUri = false))
        assertFalse(isLocalActionTarget(hasLocal = false, hasUri = true))
    }

    @Test
    fun localActionTargets_excludeCloudOnlyItems() {
        // Sans URI (impossible à construire en JVM), aucun élément n'est une cible locale.
        val items = listOf(summary(1, hasLocal = false, hasCloud = true), summary(2, hasLocal = true, hasCloud = true))
        assertTrue(localActionTargets(items).isEmpty())
    }

    @Test
    fun actionCounts_useTargetsOnlyForTheSameSelection() {
        val selection = setOf(1L, 2L, 3L)
        assertEquals(ActionCounts(local = 1, skippedCloud = 2), actionCounts(selection, SelectionTargets(selection, 1)))
        // Calcul pas encore à jour : toute la sélection, l'action filtrera elle-même.
        assertEquals(ActionCounts(local = 3, skippedCloud = 0), actionCounts(selection, SelectionTargets(setOf(1L), 1)))
        assertEquals(ActionCounts(local = 3, skippedCloud = 0), actionCounts(selection, null))
        // Valeur incohérente bornée.
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
    fun contentCoordinates_removeTopPadding() {
        // Doigt à 300 px du haut du conteneur, marge intérieure de 250 px : 50 px dans le contenu.
        assertEquals(Offset(12f, 50f), toContentCoordinates(Offset(12f, 300f), 250))
        assertEquals(Offset(0f, -20f), toContentCoordinates(Offset(0f, 230f), 250))
        assertEquals(Offset(5f, 5f), toContentCoordinates(Offset(5f, 5f), 0))
    }
}
