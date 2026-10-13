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
 * Client MSAL « compte unique », créé une seule fois et à la demande (jamais au démarrage).
 *
 * La configuration JSON est générée depuis BuildConfig puis écrite dans `noBackupFilesDir`
 * (jamais sauvegardé), et transmise à la fabrique bloquante de MSAL, interdite sur le thread
 * principal : tout se passe sur le dispatcher d'E/S.
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

    /** @throws IllegalStateException si OneDrive n'est pas configuré dans ce build. */
    suspend fun get(): ISingleAccountPublicClientApplication {
        client?.let { return it }
        check(feature.enabled) { "MSAL non configuré dans ce build" }
        return mutex.withLock {
            client ?: withContext(io) {
                val file = writeConfig()
                PublicClientApplication.createSingleAccountPublicClientApplication(context, file)
            }.also { client = it }
        }
    }

    /** Ecrit la configuration seulement si son contenu a changé. */
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
