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
    /** Saisie d'une recherche ([MediaContext.Search]), vide pour les autres contextes. */
    val contextQuery: String = "",
) {
    val context: MediaContext get() = decodeContext(contextType, contextArg, mediaId, contextQuery)

    companion object {
        fun of(context: MediaContext, mediaId: Long, exitOnBack: Boolean = false): ViewerRoute {
            val (type, arg) = encodeContext(context)
            return ViewerRoute(type, arg, mediaId, exitOnBack, contextQuery = (context as? MediaContext.Search)?.query.orEmpty())
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

/** Recherche plein texte (nom, dossier, album, date, type). */
@Serializable
data object SearchRoute

/** « Masquer des albums » : affichage de chaque album dans Albums et Photos. */
@Serializable
data object AlbumVisibilityRoute

/** Economiseur d'espace : copies du téléphone déjà sur OneDrive, revues avant la corbeille. */
@Serializable
data object SpaceSaverRoute

fun encodeContext(context: MediaContext): Pair<String, Long> = when (context) {
    MediaContext.Timeline -> "timeline" to 0L
    MediaContext.Favorites -> "favorites" to 0L
    MediaContext.Videos -> "videos" to 0L
    is MediaContext.Album -> "album" to context.albumId
    is MediaContext.Single -> "single" to context.mediaId
    // La saisie suit à part (ViewerRoute.contextQuery).
    is MediaContext.Search -> "search" to 0L
}

fun decodeContext(type: String, arg: Long, fallbackMediaId: Long, query: String = ""): MediaContext = when (type) {
    "timeline" -> MediaContext.Timeline
    "favorites" -> MediaContext.Favorites
    "videos" -> MediaContext.Videos
    "album" -> MediaContext.Album(arg)
    "single" -> MediaContext.Single(arg)
    "search" -> MediaContext.Search(query)
    else -> MediaContext.Single(fallbackMediaId)
}
