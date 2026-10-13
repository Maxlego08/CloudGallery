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
 * Replaces the stable URI `onedrive://item/<id>` with the temporary download URL on every open
 * (playback, seek, retry). Called by Media3 on its loading thread, where blocking is permitted
 * ("allowed to block"): never on the main thread.
 *
 * The obtained URL is only passed to DefaultHttpDataSource, without an authentication header;
 * [resolveReportedUri] hides it in Media3's events and errors.
 */
@OptIn(UnstableApi::class)
class OneDriveStreamResolver(
    private val itemId: String,
    private val urls: CloudDownloadUrlProvider,
) : ResolvingDataSource.Resolver {

    private val stableUri: Uri = Uri.parse(streamUriString(itemId))

    /** The next open asks Graph for a URL again instead of reusing the cached one. */
    private val refreshNext = AtomicBoolean(false)

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // Only this player's URI: any other URI is left as is.
        if (dataSpec.uri.scheme != STREAM_SCHEME) return dataSpec
        val info = try {
            runBlocking { urls.get(itemId, forceRefresh = refreshNext.getAndSet(false)) }
        } catch (_: InterruptedException) {
            // Load cancelled by Media3 (Thread.interrupt): abort, not a playback error.
            Thread.currentThread().interrupt()
            throw InterruptedIOException("OneDrive resolution interrupted")
        } catch (_: CancellationException) {
            throw InterruptedIOException("OneDrive resolution cancelled")
        } catch (e: Exception) {
            throw CloudStreamException(cloudStreamFailureFor(e), e)
        }
        return dataSpec.withUri(Uri.parse(info.url))
    }

    override fun resolveReportedUri(uri: Uri): Uri = stableUri

    /** URL rejected by the download host: the next open requests a new one. */
    fun invalidate() {
        refreshNext.set(true)
        urls.invalidate(itemId)
    }
}
