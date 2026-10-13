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
 * Presence of the Samsung Secure Folder app, read with the PackageManager (package declared in
 * the manifest's `<queries>`). No Knox API is used: sending goes through the Android share
 * sheet.
 */
@Singleton
class SecureFolderDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** App installed and enabled for this user. I/O thread. */
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
