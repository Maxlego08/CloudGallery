package fr.eva.cloudgallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridZoomLevelsTest {

    @Test
    fun portraitColumnsMatchSamsungLevels() {
        assertEquals(listOf(2, 3, 4, 6, 9), (0..4).map { GridZoomLevels.columns(it, landscape = false) })
    }

    @Test
    fun landscapeHasMoreColumns() {
        assertEquals(listOf(4, 5, 7, 11, 16), (0..4).map { GridZoomLevels.columns(it, landscape = true) })
        (0..4).forEach {
            assertTrue(GridZoomLevels.columns(it, true) > GridZoomLevels.columns(it, false))
        }
    }

    @Test
    fun outOfRangeLevelsAreClamped() {
        assertEquals(2, GridZoomLevels.columns(-3, landscape = false))
        assertEquals(9, GridZoomLevels.columns(42, landscape = false))
        assertEquals(0, GridZoomLevels.clamp(-1))
        assertEquals(4, GridZoomLevels.clamp(5))
    }

    @Test
    fun groupingByDayThenMonth() {
        assertEquals(GridGrouping.DAY, GridZoomLevels.grouping(0))
        assertEquals(GridGrouping.DAY, GridZoomLevels.grouping(1))
        assertEquals(GridGrouping.DAY, GridZoomLevels.grouping(2))
        assertEquals(GridGrouping.MONTH, GridZoomLevels.grouping(3))
        assertEquals(GridGrouping.MONTH, GridZoomLevels.grouping(4))
    }

    @Test
    fun stepMovesWithinBounds() {
        assertEquals(0, GridZoomLevels.step(1, -1))
        assertEquals(0, GridZoomLevels.step(0, -1))
        assertEquals(2, GridZoomLevels.step(1, 1))
        assertEquals(4, GridZoomLevels.step(4, 1))
    }

    @Test
    fun defaultLevelIsValid() {
        assertEquals(GridZoomLevels.DEFAULT_LEVEL, GridZoomLevels.clamp(GridZoomLevels.DEFAULT_LEVEL))
    }
}
