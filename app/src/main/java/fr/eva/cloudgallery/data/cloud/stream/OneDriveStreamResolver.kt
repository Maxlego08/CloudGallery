package fr.eva.cloudgallery.data.cloud.stream

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import fr.eva.cloudgallery.data.cloud.transfer.CloudDownloadUrlProvider
import fr.eva.cloudgallery.domain.model.CloudStreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remplace l'URI stable `onedrive://item/<id>` par l'URL de téléchargement temporaire, à chaque
 * ouverture (lecture, seek, nouvelle tentative). Appelé par Media3 sur son thread de chargement,
 * où le blocage est autorisé (« allowed to block ») : jamais sur le thread principal.
 *
 * L'URL obtenue n'est transmise qu'à DefaultHttpDataSource, sans en-tête d'authentification ;
 * [resolveReportedUri] la masque dans les événements et erreurs de Media3.
 */
@OptIn(UnstableApi::class)
class OneDriveStreamResolver(
    private val itemId: String,
    private val urls: CloudDownloadUrlProvider,
) : ResolvingDataSource.Resolver {

    private val stableUri: Uri = Uri.parse(streamUriString(itemId))

    /** La prochaine ouverture redemande une URL à Graph au lieu de réutiliser celle en cache. */
    private val refreshNext = AtomicBoolean(false)

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // Uniquement l'URI de ce lecteur : toute autre URI est laissée telle quelle.
        if (dataSpec.uri.scheme != STREAM_SCHEME) return dataSpec
        val info = try {
            runBlocking { urls.get(itemId, forceRefresh = refreshNext.getAndSet(false)) }
        } catch (_: InterruptedException) {
            // Chargement annulé par Media3 (Thread.interrupt) : abandon, pas une erreur de lecture.
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Résolution OneDrive interrompue")
        } catch (_: CancellationException) {
            throw InterruptedIOException("Résolution OneDrive annulée")
        } catch (e: Exception) {
            throw CloudStreamException(cloudStreamFailureFor(e), e)
        }
        return dataSpec.withUri(Uri.parse(info.url))
    }

    override fun resolveReportedUri(uri: Uri): Uri = stableUri

    /** URL refusée par l'hôte de téléchargement : la prochaine ouverture en demande une nouvelle. */
    fun invalidate() {
        refreshNext.set(true)
        urls.invalidate(itemId)
    }
}
