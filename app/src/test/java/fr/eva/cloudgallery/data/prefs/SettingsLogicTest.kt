package fr.eva.cloudgallery.data.prefs

import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun zoomLevelIsClamped() {
        assertEquals(0, clampZoomLevel(-3))
        assertEquals(0, clampZoomLevel(0))
        assertEquals(2, clampZoomLevel(2))
        assertEquals(4, clampZoomLevel(4))
        assertEquals(4, clampZoomLevel(99))
    }

    @Test
    fun enumParsingFallsBackToDefault() {
        assertEquals(ThemeMode.DARK, enumOrDefault("DARK", ThemeMode.SYSTEM))
        assertEquals(ThemeMode.SYSTEM, enumOrDefault("PURPLE", ThemeMode.SYSTEM))
        assertEquals(ThemeMode.SYSTEM, enumOrDefault<ThemeMode>(null, ThemeMode.SYSTEM))
        assertEquals(AlbumSort.COUNT, enumOrDefault("COUNT", AlbumSort.CUSTOM))
    }
}
