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
 * `content://` URI (the app's FileProvider) of a downloaded OneDrive file, to be handed over
 * with a read-only grant. The receiving app sees [uriFor]'s `displayName` (sanitized OneDrive
 * name), never the file's internal name. On the I/O dispatcher: FileProvider resolves the
 * file's canonical path (disk access).
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
        /** Authority declared in the manifest (`${applicationId}.fileprovider`). */
        fun authority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
