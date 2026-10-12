package fr.eva.cloudgallery.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.ORIGINALS_CACHE_CHOICES
import fr.eva.cloudgallery.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    /** Décision utilisateur (D2) : sur réseau facturé, l'original est proposé, pas chargé, par défaut. */
    @Test
    fun loadOriginalsOnMobileData_isOffByDefault() = runTest {
        assertFalse(AppSettings().loadOriginalsOnMobileData)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        assertFalse(repository.settings.first().loadOriginalsOnMobileData)
        repository.setLoadOriginalsOnMobileData(true)
        assertTrue(repository.settings.first().loadOriginalsOnMobileData)
        // Indépendant de l'autre réglage OneDrive.
        assertFalse(repository.settings.first().showCloudInPhotos)
        repository.setLoadOriginalsOnMobileData(false)
        assertFalse(repository.settings.first().loadOriginalsOnMobileData)
    }

    /** Décision utilisateur (D2) : copies hors ligne en Wi-Fi uniquement par défaut. */
    @Test
    fun offlineWifiOnly_isOnByDefault_andNotificationsNotYetAsked() = runTest {
        assertTrue(AppSettings().offlineWifiOnly)
        assertFalse(AppSettings().offlineNotificationsAsked)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        assertTrue(repository.settings.first().offlineWifiOnly)
        repository.setOfflineWifiOnly(false)
        assertFalse(repository.settings.first().offlineWifiOnly)
        repository.setOfflineNotificationsAsked(true)
        assertTrue(repository.settings.first().offlineNotificationsAsked)
        // Indépendant des autres réglages OneDrive.
        assertFalse(repository.settings.first().loadOriginalsOnMobileData)
    }

    /** Décision utilisateur (phase 5) : plafond des originaux à 1 Go par défaut, parmi les choix proposés. */
    @Test
    fun originalsCacheMaxBytes_defaultsTo1Gb_andOnlyAcceptsProposedChoices() = runTest {
        assertEquals(1_000_000_000L, AppSettings().originalsCacheMaxBytes)
        assertTrue(AppSettings().originalsCacheMaxBytes in ORIGINALS_CACHE_CHOICES)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        assertEquals(1_000_000_000L, repository.settings.first().originalsCacheMaxBytes)
        repository.setOriginalsCacheMaxBytes(5_000_000_000L)
        assertEquals(5_000_000_000L, repository.settings.first().originalsCacheMaxBytes)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repository.setOriginalsCacheMaxBytes(123L) } }
        assertEquals(5_000_000_000L, repository.settings.first().originalsCacheMaxBytes)
    }

    @Test
    fun thumbnailCacheMaxBytes_defaultsTo250Mb_andOnlyAcceptsProposedChoices() = runTest {
        assertEquals(250_000_000L, AppSettings().thumbnailCacheMaxBytes)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        repository.setThumbnailCacheMaxBytes(1_000_000_000L)
        assertEquals(1_000_000_000L, repository.settings.first().thumbnailCacheMaxBytes)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repository.setThumbnailCacheMaxBytes(256L * 1024 * 1024) } }
        // Indépendant du plafond des originaux.
        assertEquals(1_000_000_000L, repository.settings.first().originalsCacheMaxBytes)
    }

    /** Décision utilisateur (phase 5) : synchro de fond réglable, toutes les 6 h par défaut, sur tout réseau. */
    @Test
    fun backgroundSync_every6HoursByDefault_onAnyNetwork() = runTest {
        assertEquals(BackgroundSyncInterval.EVERY_6_HOURS, AppSettings().backgroundSyncInterval)
        assertFalse(AppSettings().backgroundSyncWifiOnly)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        assertEquals(BackgroundSyncInterval.EVERY_6_HOURS, repository.settings.first().backgroundSyncInterval)
        repository.setBackgroundSyncInterval(BackgroundSyncInterval.OFF)
        repository.setBackgroundSyncWifiOnly(true)
        assertEquals(BackgroundSyncInterval.OFF, repository.settings.first().backgroundSyncInterval)
        assertTrue(repository.settings.first().backgroundSyncWifiOnly)
        // Indépendant du réglage Wi-Fi des copies hors ligne.
        assertTrue(repository.settings.first().offlineWifiOnly)
        assertEquals(null, BackgroundSyncInterval.OFF.hours)
        assertEquals(listOf(1L, 6L, 24L), BackgroundSyncInterval.entries.mapNotNull { it.hours })
    }

    @Test
    fun oneDriveOnlyChosen_isOffByDefault_andRemembered() = runTest {
        assertFalse(AppSettings().oneDriveOnlyChosen)
        val repository = SettingsRepositoryImpl(InMemoryDataStore())
        assertFalse(repository.settings.first().oneDriveOnlyChosen)
        repository.setOneDriveOnlyChosen(true)
        assertTrue(repository.settings.first().oneDriveOnlyChosen)
        repository.setOneDriveOnlyChosen(false)
        assertFalse(repository.settings.first().oneDriveOnlyChosen)
    }

    @Test
    fun choiceParsingFallsBackToDefault() {
        val choices = listOf(1L, 2L)
        assertEquals(2L, choiceOrDefault(2L, choices, 1L))
        assertEquals(1L, choiceOrDefault(7L, choices, 1L))
        assertEquals(1L, choiceOrDefault(null, choices, 1L))
    }
}
