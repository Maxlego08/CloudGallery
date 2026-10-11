package fr.eva.cloudgallery.ui.grid

import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GridSeparatorsTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun media(id: Long, dateTime: LocalDateTime, zone: ZoneId = paris) = MediaSummary(
        id = id,
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        dateTaken = dateTime.atZone(zone).toInstant().toEpochMilli(),
        durationMs = 0,
        width = 4000,
        height = 3000,
        orientation = 0,
        uri = null,
        dateModified = 0,
        isFavorite = false,
        hasLocal = true,
        hasCloud = false,
        hasOffline = false,
    )

    @Test
    fun headerBeforeFirstItem() {
        val first = media(1, LocalDateTime.of(2026, 10, 6, 14, 0))
        val header = headerBetween(null, first, GridGrouping.DAY, paris)
        assertEquals(GridItem.Header(GridGrouping.DAY, LocalDate.of(2026, 10, 6).toEpochDay()), header)
    }

    @Test
    fun noHeaderAfterLastItem() {
        val last = media(1, LocalDateTime.of(2026, 10, 6, 14, 0))
        assertNull(headerBetween(last, null, GridGrouping.DAY, paris))
        assertNull(headerBetween(null, null, GridGrouping.MONTH, paris))
    }

    @Test
    fun noHeaderWithinSameDay() {
        val a = media(2, LocalDateTime.of(2026, 10, 6, 23, 59))
        val b = media(1, LocalDateTime.of(2026, 10, 6, 0, 1))
        assertNull(headerBetween(a, b, GridGrouping.DAY, paris))
    }

    @Test
    fun headerWhenDayChanges() {
        val a = media(2, LocalDateTime.of(2026, 10, 6, 0, 1))
        val b = media(1, LocalDateTime.of(2026, 10, 5, 23, 59))
        val header = headerBetween(a, b, GridGrouping.DAY, paris)
        assertEquals(LocalDate.of(2026, 10, 5), header?.date)
    }

    @Test
    fun monthGroupingIgnoresDayChanges() {
        val a = media(2, LocalDateTime.of(2026, 10, 30, 12, 0))
        val b = media(1, LocalDateTime.of(2026, 10, 1, 12, 0))
        assertNull(headerBetween(a, b, GridGrouping.MONTH, paris))

        val c = media(0, LocalDateTime.of(2026, 9, 30, 12, 0))
        val header = headerBetween(b, c, GridGrouping.MONTH, paris)
        assertEquals(LocalDate.of(2026, 9, 1), header?.date)
        assertEquals(java.time.YearMonth.of(2026, 9), header?.yearMonth)
    }

    @Test
    fun dayBoundaryUsesProvidedZone() {
        // 23:30 UTC le 5 octobre = 01:30 le 6 octobre à Paris.
        val utc = ZoneId.of("UTC")
        val item = media(1, LocalDateTime.of(2026, 10, 5, 23, 30), utc)
        assertEquals(LocalDate.of(2026, 10, 6), headerBetween(null, item, GridGrouping.DAY, paris)?.date)
        assertEquals(LocalDate.of(2026, 10, 5), headerBetween(null, item, GridGrouping.DAY, utc)?.date)
    }

    @Test
    fun headerKeysAreStableAndDistinct() {
        val day = GridItem.Header(GridGrouping.DAY, 20_000)
        val month = GridItem.Header(GridGrouping.MONTH, 20_000)
        assertEquals("d:20000", day.key)
        assertEquals("m:20000", month.key)
        assertNotEquals(day.key, month.key)
        assertEquals(42L, GridItem.Media(media(42, LocalDateTime.of(2026, 1, 1, 0, 0))).key)
    }

    @Test
    fun folderNamesAreSanitized() {
        assertEquals("Vacances 2026", MediaGridViewModel.sanitizeFolderName("  Vacances/ 2026 "))
        assertEquals("abc", MediaGridViewModel.sanitizeFolderName("a\\b:c*?\"<>|"))
        assertEquals("", MediaGridViewModel.sanitizeFolderName(" .. "))
    }
}
