package fr.eva.cloudgallery.data.cloud.content

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.transfer.safeDisplayName
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * URI `content://` (FileProvider de l'appli) d'un fichier OneDrive téléchargé, à transmettre
 * avec une autorisation de lecture seule. L'appli qui le reçoit voit [uriFor] `displayName`
 * (nom OneDrive nettoyé), jamais le nom interne du fichier. Sur le dispatcher d'E/S :
 * FileProvider résout le chemin canonique du fichier (accès disque).
 */
@Singleton
class ShareUriProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun uriFor(file: File, displayName: String): Uri = withContext(io) {
        FileProvider.getUriForFile(context, authority(context), file, safeDisplayName(displayName))
    }

    companion object {
        /** Autorité déclarée dans le manifeste (`${applicationId}.fileprovider`). */
        fun authority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
