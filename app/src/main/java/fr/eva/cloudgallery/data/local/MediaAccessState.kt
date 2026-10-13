package fr.eva.cloudgallery.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.domain.model.MediaAccess
import fr.eva.cloudgallery.domain.model.mediaAccessOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Current access to the device's media, based on the granted permissions (no I/O). */
fun readMediaAccess(context: Context): MediaAccess = mediaAccessOf(
    images = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES),
    videos = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO),
    selected = context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
)

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Media access as seen by the data layer (no Context in the DAOs): read at process start, then
 * kept up to date by the UI on every observed change (return to foreground, answer to the
 * permission request). Used to show OneDrive in Photos without local access.
 */
@Singleton
class MediaAccessState @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _access = MutableStateFlow(readMediaAccess(context))
    val access: StateFlow<MediaAccess> = _access.asStateFlow()

    fun update(access: MediaAccess) {
        _access.value = access
    }
}
