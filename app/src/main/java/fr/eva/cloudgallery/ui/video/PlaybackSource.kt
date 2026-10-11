package fr.eva.cloudgallery.ui.video

import android.net.Uri
import androidx.media3.common.MediaItem
import fr.eva.cloudgallery.domain.model.MediaDetails

/** Source physique choisie pour lire un média. */
sealed interface PlaybackSource {
    /** Fichier présent sur l'appareil (URI `content://` MediaStore). */
    data class Local(val uri: Uri, val mimeType: String?) : PlaybackSource

    /** Aucune source lisible pour l'instant (média uniquement sur OneDrive). */
    data object Unavailable : PlaybackSource
}

/**
 * Choisit la source de lecture d'un média.
 *
 * Phase 3 : uniquement un fichier local présent (source LOCAL visible) ; jamais l'URI principale
 * du média en repli, ni pour un élément OneDrive (qui reste [PlaybackSource.Unavailable]).
 * TODO(phase 4) : ajouter `PlaybackSource.Remote` pour les médias OneDrive — obtenir une URL de
 *  téléchargement temporaire (@microsoft.graph.downloadUrl), la renouveler à l'expiration via un
 *  `ResolvingDataSource` au-dessus de `DefaultHttpDataSource` (requêtes HTTP Range pour le seek),
 *  et privilégier une éventuelle copie hors ligne (SourceKind.OFFLINE_COPY) avant le streaming.
 */
object PlaybackSourceResolver {

    fun resolve(details: MediaDetails): PlaybackSource {
        val local = details.localSource?.uri
        return if (local != null) PlaybackSource.Local(local, details.mimeType) else PlaybackSource.Unavailable
    }

    /** Fichier progressif local : ExoPlayer détecte le conteneur lui-même (pas de type MIME forcé). */
    fun toMediaItem(source: PlaybackSource.Local): MediaItem = MediaItem.fromUri(source.uri)
}
