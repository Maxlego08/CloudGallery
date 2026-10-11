package fr.eva.cloudgallery.ui.grid

import androidx.compose.ui.geometry.Offset
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

    @Test
    fun contentCoordinates_removeTopPadding() {
        // Doigt à 300 px du haut du conteneur, marge intérieure de 250 px : 50 px dans le contenu.
        assertEquals(Offset(12f, 50f), toContentCoordinates(Offset(12f, 300f), 250))
        assertEquals(Offset(0f, -20f), toContentCoordinates(Offset(0f, 230f), 250))
        assertEquals(Offset(5f, 5f), toContentCoordinates(Offset(5f, 5f), 0))
    }
}
