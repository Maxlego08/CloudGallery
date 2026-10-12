package fr.eva.cloudgallery.data.prefs

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.ORIGINALS_CACHE_CHOICES
import fr.eva.cloudgallery.domain.model.ThemeMode
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Bornes des niveaux de zoom des grilles. */
internal const val MIN_ZOOM_LEVEL = 0
internal const val MAX_ZOOM_LEVEL = 4

internal fun clampZoomLevel(level: Int): Int = level.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

/** Valeur stockée seulement si elle fait partie de [choices] (sinon [default]). */
internal fun choiceOrDefault(value: Long?, choices: List<Long>, default: Long): Long =
    value?.takeIf { it in choices } ?: default

/** Lecture tolérante d'une énumération stockée par son nom. */
internal inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "Lecture des réglages impossible, valeurs par défaut", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) = set(Keys.THEME_MODE, mode.name)
    override suspend fun setPhotosZoomLevel(level: Int) = set(Keys.PHOTOS_ZOOM, clampZoomLevel(level))
    override suspend fun setAlbumZoomLevel(level: Int) = set(Keys.ALBUM_ZOOM, clampZoomLevel(level))
    override suspend fun setAlbumSort(sort: AlbumSort) = set(Keys.ALBUM_SORT, sort.name)
    override suspend fun setShowVideoDuration(show: Boolean) = set(Keys.SHOW_VIDEO_DURATION, show)
    override suspend fun setAutoPlayVideos(enabled: Boolean) = set(Keys.AUTO_PLAY_VIDEOS, enabled)
    override suspend fun setConfirmBeforeDelete(enabled: Boolean) = set(Keys.CONFIRM_DELETE, enabled)
    override suspend fun setUseTrash(enabled: Boolean) = set(Keys.USE_TRASH, enabled)
    override suspend fun setShowCloudInPhotos(enabled: Boolean) = set(Keys.SHOW_CLOUD_IN_PHOTOS, enabled)
    override suspend fun setLoadOriginalsOnMobileData(enabled: Boolean) = set(Keys.LOAD_ORIGINALS_ON_MOBILE_DATA, enabled)
    override suspend fun setOfflineWifiOnly(enabled: Boolean) = set(Keys.OFFLINE_WIFI_ONLY, enabled)
    override suspend fun setOfflineNotificationsAsked(asked: Boolean) = set(Keys.OFFLINE_NOTIFICATIONS_ASKED, asked)
    override suspend fun setOriginalsCacheMaxBytes(bytes: Long) {
        require(bytes in ORIGINALS_CACHE_CHOICES) { "Plafond non proposé : $bytes" }
        set(Keys.ORIGINALS_CACHE_MAX_BYTES, bytes)
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOrDefault(this[Keys.THEME_MODE], d.themeMode),
            photosZoomLevel = clampZoomLevel(this[Keys.PHOTOS_ZOOM] ?: d.photosZoomLevel),
            albumZoomLevel = clampZoomLevel(this[Keys.ALBUM_ZOOM] ?: d.albumZoomLevel),
            albumSort = enumOrDefault(this[Keys.ALBUM_SORT], d.albumSort),
            showVideoDuration = this[Keys.SHOW_VIDEO_DURATION] ?: d.showVideoDuration,
            autoPlayVideos = this[Keys.AUTO_PLAY_VIDEOS] ?: d.autoPlayVideos,
            confirmBeforeDelete = this[Keys.CONFIRM_DELETE] ?: d.confirmBeforeDelete,
            useTrash = this[Keys.USE_TRASH] ?: d.useTrash,
            showCloudInPhotos = this[Keys.SHOW_CLOUD_IN_PHOTOS] ?: d.showCloudInPhotos,
            loadOriginalsOnMobileData = this[Keys.LOAD_ORIGINALS_ON_MOBILE_DATA] ?: d.loadOriginalsOnMobileData,
            offlineWifiOnly = this[Keys.OFFLINE_WIFI_ONLY] ?: d.offlineWifiOnly,
            offlineNotificationsAsked = this[Keys.OFFLINE_NOTIFICATIONS_ASKED] ?: d.offlineNotificationsAsked,
            originalsCacheMaxBytes = choiceOrDefault(this[Keys.ORIGINALS_CACHE_MAX_BYTES], ORIGINALS_CACHE_CHOICES, d.originalsCacheMaxBytes),
        )
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PHOTOS_ZOOM = intPreferencesKey("photos_zoom_level")
        val ALBUM_ZOOM = intPreferencesKey("album_zoom_level")
        val ALBUM_SORT = stringPreferencesKey("album_sort")
        val SHOW_VIDEO_DURATION = booleanPreferencesKey("show_video_duration")
        val AUTO_PLAY_VIDEOS = booleanPreferencesKey("auto_play_videos")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_before_delete")
        val USE_TRASH = booleanPreferencesKey("use_trash")
        val SHOW_CLOUD_IN_PHOTOS = booleanPreferencesKey("show_cloud_in_photos")
        val LOAD_ORIGINALS_ON_MOBILE_DATA = booleanPreferencesKey("load_originals_on_mobile_data")
        val OFFLINE_WIFI_ONLY = booleanPreferencesKey("offline_wifi_only")
        val OFFLINE_NOTIFICATIONS_ASKED = booleanPreferencesKey("offline_notifications_asked")
        val ORIGINALS_CACHE_MAX_BYTES = longPreferencesKey("originals_cache_max_bytes")
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
