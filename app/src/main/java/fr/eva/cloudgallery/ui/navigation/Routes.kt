package fr.eva.cloudgallery.ui.navigation

import fr.eva.cloudgallery.domain.model.MediaContext
import kotlinx.serialization.Serializable

/** Ecran principal à onglets (Photos / Albums / Menu). */
@Serializable
data object MainRoute

/** Grille d'un album (dossier local, dossier cloud ou album virtuel). */
@Serializable
data class AlbumRoute(val albumId: Long)

/** Grille « Favoris ». */
@Serializable
data object FavoritesRoute

/** Grille « Vidéos ». */
@Serializable
data object VideosRoute

/** Visionneuse plein écran, ouverte sur [mediaId] au sein d'un contexte. */
@Serializable
data class ViewerRoute(
    val contextType: String,
    val contextArg: Long,
    val mediaId: Long,
    /** Ouverte pour une autre appli (intent VIEW) : le retour lui rend la main. */
    val exitOnBack: Boolean = false,
) {
    val context: MediaContext get() = decodeContext(contextType, contextArg, mediaId)

    companion object {
        fun of(context: MediaContext, mediaId: Long, exitOnBack: Boolean = false): ViewerRoute {
            val (type, arg) = encodeContext(context)
            return ViewerRoute(type, arg, mediaId, exitOnBack)
        }
    }
}

/**
 * Lecteur vidéo plein écran. [externalUri] : vidéo reçue d'une autre appli et non indexée
 * (FileProvider, sélection partielle…), lue directement depuis l'URI accordée ; [mediaId] vaut alors -1.
 * [exitOnBack] : ouverte pour une autre appli, le retour lui rend la main (fin de l'activité).
 */
@Serializable
data class VideoPlayerRoute(
    val mediaId: Long,
    val externalUri: String? = null,
    val title: String? = null,
    val mimeType: String? = null,
    val exitOnBack: Boolean = false,
)

/**
 * Image reçue d'une autre appli et absente de l'index (URI FileProvider, sélecteur de photos,
 * accès partiel…) : affichée en lecture seule depuis l'URI accordée.
 */
@Serializable
data class ExternalImageRoute(
    val uri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val exitOnBack: Boolean = false,
)

@Serializable
data object SettingsRoute

/** Compte OneDrive (connexion, déconnexion). */
@Serializable
data object AccountRoute

/** Stockage : espace occupé, plafonds des caches, vidage du cache. */
@Serializable
data object StorageRoute

fun encodeContext(context: MediaContext): Pair<String, Long> = when (context) {
    MediaContext.Timeline -> "timeline" to 0L
    MediaContext.Favorites -> "favorites" to 0L
    MediaContext.Videos -> "videos" to 0L
    is MediaContext.Album -> "album" to context.albumId
    is MediaContext.Single -> "single" to context.mediaId
}

fun decodeContext(type: String, arg: Long, fallbackMediaId: Long): MediaContext = when (type) {
    "timeline" -> MediaContext.Timeline
    "favorites" -> MediaContext.Favorites
    "videos" -> MediaContext.Videos
    "album" -> MediaContext.Album(arg)
    "single" -> MediaContext.Single(arg)
    else -> MediaContext.Single(fallbackMediaId)
}
