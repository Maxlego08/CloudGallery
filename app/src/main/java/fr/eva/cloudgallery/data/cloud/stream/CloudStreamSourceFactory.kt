package fr.eva.cloudgallery.data.cloud.stream

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import fr.eva.cloudgallery.data.cloud.graph.CLOUD_USER_AGENT
import fr.eva.cloudgallery.data.cloud.transfer.CloudDownloadUrlProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Media3 source of a streamed OneDrive item, and a way to force a new URL. */
class RemoteStream internal constructor(
    val source: MediaSource,
    private val resolver: OneDriveStreamResolver,
) {
    /** Before retrying: the next open requests a new download URL. */
    fun invalidateUrl() = resolver.invalidate()
}

/**
 * Builds the streaming playback source of a OneDrive item: a progressive file read by
 * DefaultHttpDataSource (HTTP Range requests for seeking) from the temporary download URL,
 * resolved on every open by [OneDriveStreamResolver]. No authentication header and no default
 * request property: the Microsoft Graph token is never sent to the download host. No forced
 * MIME type: Media3 detects the container.
 */
@OptIn(UnstableApi::class)
@Singleton
class CloudStreamSourceFactory @Inject constructor(
    private val urls: CloudDownloadUrlProvider,
) {

    fun create(itemId: String, mediaId: Long): RemoteStream {
        val resolver = OneDriveStreamResolver(itemId, urls)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(CLOUD_USER_AGENT)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        val source = ProgressiveMediaSource.Factory(ResolvingDataSource.Factory(http, resolver))
            .setLoadErrorHandlingPolicy(CloudLoadErrorPolicy(resolver))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(streamUriString(itemId))
                    .setMediaId(mediaId.toString())
                    .build(),
            )
        return RemoteStream(source, resolver)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
