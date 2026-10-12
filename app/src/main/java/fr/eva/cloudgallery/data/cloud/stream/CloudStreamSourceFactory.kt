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

/** Source Media3 d'un élément OneDrive lu en streaming, et moyen de forcer une nouvelle URL. */
class RemoteStream internal constructor(
    val source: MediaSource,
    private val resolver: OneDriveStreamResolver,
) {
    /** Avant de réessayer : la prochaine ouverture demande une nouvelle URL de téléchargement. */
    fun invalidateUrl() = resolver.invalidate()
}

/**
 * Construit la source de lecture en streaming d'un élément OneDrive : fichier progressif lu par
 * DefaultHttpDataSource (requêtes HTTP Range pour le seek) sur l'URL de téléchargement temporaire,
 * résolue à chaque ouverture par [OneDriveStreamResolver]. Aucun en-tête d'authentification ni
 * propriété de requête par défaut : le jeton Microsoft Graph n'est jamais envoyé à l'hôte de
 * téléchargement. Pas de type MIME forcé : Media3 détecte le conteneur.
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
