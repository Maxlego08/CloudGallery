package fr.eva.cloudgallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class FastScrollLogicTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(paris).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).toEpochDay()

    private fun month(y: Int, m: Int): Long = LocalDate.of(y, m, 1).toEpochDay()

    /** Ordre de la grille : du plus récent au plus ancien, dates égales possibles. */
    private val index = FastScrollIndex(
        longArrayOf(
            ms(2026, 10, 12, 18), // 0
            ms(2026, 10, 12, 9), // 1
            ms(2026, 10, 1), // 2
            ms(2026, 9, 30), // 3
            ms(2026, 9, 30), // 4
            ms(2025, 1, 15), // 5
            ms(2025, 1, 2), // 6
            ms(2024, 12, 31, 23), // 7
        ),
    )

    @Test
    fun countAtOrAfter_dateInsideList_countsNewerAndEqual() {
        assertEquals(5, index.countAtOrAfter(ms(2026, 9, 30)))
        assertEquals(0, index.countAtOrAfter(ms(2027, 1, 1)))
        assertEquals(8, index.countAtOrAfter(0L))
    }

    @Test
    fun rankOfDate_duplicateDates_returnsFirstOccurrence() {
        assertEquals(3, index.rankOfDate(ms(2026, 9, 30)))
        assertEquals(0, index.rankOfDate(ms(2026, 10, 12, 18)))
        assertEquals(7, index.rankOfDate(ms(2024, 12, 31, 23)))
    }

    @Test
    fun periodStartRank_dayAndMonth_returnsFirstMediaOfPeriod() {
        assertEquals(0, index.periodStartRank(day(2026, 10, 12), GridGrouping.DAY, paris))
        assertEquals(2, index.periodStartRank(day(2026, 10, 1), GridGrouping.DAY, paris))
        assertEquals(5, index.periodStartRank(month(2025, 1), GridGrouping.MONTH, paris))
        assertEquals(7, index.periodStartRank(month(2024, 12), GridGrouping.MONTH, paris))
    }

    @Test
    fun periodStartRank_emptyPeriod_returnsFirstOlderMedia() {
        // Aucun média en mars 2025 : le premier média plus ancien (janvier 2025).
        assertEquals(5, index.periodStartRank(month(2025, 3), GridGrouping.MONTH, paris))
    }

    @Test
    fun rankAt_fractionBounds_clampsToList() {
        assertEquals(0, index.rankAt(-1f))
        assertEquals(0, index.rankAt(0f))
        assertEquals(7, index.rankAt(1f))
        assertEquals(7, index.rankAt(2f))
        assertEquals(4, index.rankAt(0.5f))
    }

    @Test
    fun periodAt_rank_returnsPeriodOfThatMedia() {
        assertEquals(month(2025, 1), index.periodAt(6, GridGrouping.MONTH, paris))
        assertEquals(day(2024, 12, 31), index.periodAt(7, GridGrouping.DAY, paris))
        assertNull(FastScrollIndex.EMPTY.periodAt(0, GridGrouping.DAY, paris))
    }

    @Test
    fun fractionOf_rank_isProportionalToWholeList() {
        assertEquals(0f, index.fractionOf(0), 0f)
        assertEquals(1f, index.fractionOf(7), 0f)
        assertEquals(0.5f, index.fractionOf(7) / 2, 0f)
        assertEquals(0f, FastScrollIndex(longArrayOf(1L)).fractionOf(0), 0f)
    }

    @Test
    fun rankOf_header_returnsPeriodStart() {
        assertEquals(5, index.rankOf(GridItem.Header(GridGrouping.MONTH, month(2025, 1)), paris))
        assertEquals(3, index.rankOf(GridItem.Header(GridGrouping.DAY, day(2026, 9, 30)), paris))
    }

    @Test
    fun thumbFraction_edges_overrideRank() {
        assertEquals(0f, index.thumbFraction(firstVisibleRank = 5, atTop = true, atBottom = false), 0f)
        assertEquals(1f, index.thumbFraction(firstVisibleRank = 5, atTop = false, atBottom = true), 0f)
        assertEquals(5f / 7f, index.thumbFraction(firstVisibleRank = 5, atTop = false, atBottom = false), 1e-6f)
        assertEquals(0f, index.thumbFraction(firstVisibleRank = null, atTop = false, atBottom = false), 0f)
    }

    @Test
    fun trackFraction_pointer_centersThumbOnFinger() {
        // Piste 100..300, poignée 40 : plage utile 160 (centre de 120 à 280).
        assertEquals(0f, trackFraction(50f, 100f, 300f, 40f), 0f)
        assertEquals(0f, trackFraction(120f, 100f, 300f, 40f), 0f)
        assertEquals(0.5f, trackFraction(200f, 100f, 300f, 40f), 1e-6f)
        assertEquals(1f, trackFraction(400f, 100f, 300f, 40f), 0f)
        assertEquals(0f, trackFraction(200f, 100f, 120f, 40f), 0f)
    }

    @Test
    fun fastScrollLabel_grouping_formatsDayOrMonth() {
        assertEquals("January 2025", fastScrollLabel(day(2025, 1, 15), GridGrouping.MONTH, Locale.ENGLISH))
        assertEquals("Janvier 2025", fastScrollLabel(day(2025, 1, 1), GridGrouping.MONTH, Locale.FRENCH))
        assertEquals("12 Oct 2026", fastScrollLabel(day(2026, 10, 12), GridGrouping.DAY, Locale.ENGLISH))
    }

    @Test
    fun headerKeyOf_grouping_matchesGridHeaderKey() {
        assertEquals("d:${day(2026, 10, 12)}", headerKeyOf(GridGrouping.DAY, day(2026, 10, 12)))
        assertEquals("m:${month(2025, 1)}", headerKeyOf(GridGrouping.MONTH, month(2025, 1)))
    }

    @Test
    fun monthOrdinal_consecutiveMonths_differByOne() {
        assertEquals(1, monthOrdinal(day(2025, 1, 31)) - monthOrdinal(day(2024, 12, 1)))
        assertEquals(monthOrdinal(day(2025, 1, 1)), monthOrdinal(day(2025, 1, 31)))
    }
}
