package fr.eva.cloudgallery.data.local

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Présence de l'appli Dossier sécurisé Samsung, lue avec le PackageManager (paquet déclaré dans
 * `<queries>` du manifeste). Aucune API Knox n'est utilisée : l'envoi passe par la feuille de
 * partage Android.
 */
@Singleton
class SecureFolderDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Appli installée et activée pour cet utilisateur. Thread d'E/S. */
    suspend fun isInstalled(): Boolean = withContext(io) {
        try {
            context.packageManager
                .getApplicationInfo(SECURE_FOLDER_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
                .enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private companion object {
        const val SECURE_FOLDER_PACKAGE = "com.samsung.knox.securefolder"
    }
}
