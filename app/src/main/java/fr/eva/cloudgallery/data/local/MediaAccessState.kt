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

/** Accès actuel aux médias de l'appareil, d'après les autorisations accordées (sans E/S). */
fun readMediaAccess(context: Context): MediaAccess = mediaAccessOf(
    images = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES),
    videos = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO),
    selected = context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
)

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Accès aux médias vu par la couche données (sans Context dans les DAO) : lu au démarrage du
 * processus, puis tenu à jour par l'UI à chaque changement constaté (retour au premier plan,
 * réponse à la demande d'autorisation). Sert à montrer OneDrive dans Photos sans accès local.
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
