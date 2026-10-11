package fr.eva.cloudgallery.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    /** DataStore en mémoire (JVM), vide au départ comme après une première installation. */
    private class InMemoryDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

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

    /** Décision utilisateur : « Afficher les éléments OneDrive dans Photos » est désactivé par défaut. */
    @Test
    fun showCloudInPhotos_isOffByDefault() = runTest {
        assertFalse(AppSettings().showCloudInPhotos)

        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        // Clé absente (première installation) : repli sur la valeur par défaut.
        assertFalse(repository.settings.first().showCloudInPhotos)

        repository.setShowCloudInPhotos(true)
        assertTrue(repository.settings.first().showCloudInPhotos)
        repository.setShowCloudInPhotos(false)
        assertFalse(repository.settings.first().showCloudInPhotos)
    }
}
