package fr.eva.cloudgallery.data.cloud.auth

import android.content.Context
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Single account" MSAL client, created only once and on demand (never at startup).
 *
 * The JSON configuration is generated from BuildConfig, then written to `noBackupFilesDir`
 * (never backed up) and passed to MSAL's blocking factory, which is forbidden on the main
 * thread: everything happens on the I/O dispatcher.
 */
@Singleton
class MsalClientProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feature: CloudFeature,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    @Volatile
    private var client: ISingleAccountPublicClientApplication? = null

    /** @throws IllegalStateException if OneDrive is not configured in this build. */
    suspend fun get(): ISingleAccountPublicClientApplication {
        client?.let { return it }
        check(feature.enabled) { "MSAL not configured in this build" }
        return mutex.withLock {
            client ?: withContext(io) {
                val file = writeConfig()
                PublicClientApplication.createSingleAccountPublicClientApplication(context, file)
            }.also { client = it }
        }
    }

    /** Writes the configuration only if its content changed. */
    private fun writeConfig(): File {
        val json = buildMsalConfigJson(feature.clientId, feature.redirectUri)
        val file = File(context.noBackupFilesDir, CONFIG_FILE_NAME)
        val current = if (file.isFile) file.readText() else null
        if (current != json) file.writeText(json)
        return file
    }

    private companion object {
        const val CONFIG_FILE_NAME = "msal_config.json"
    }
}
