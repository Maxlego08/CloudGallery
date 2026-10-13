package fr.eva.cloudgallery.ui.navigation

import fr.eva.cloudgallery.domain.model.MediaContext
import kotlinx.serialization.Serializable

/** Main tabbed screen (Photos / Albums / Menu). */
@Serializable
data object MainRoute

/** Grid of an album (local folder, cloud folder or virtual album). */
@Serializable
data class AlbumRoute(val albumId: Long)

/** "Favorites" grid. */
@Serializable
data object FavoritesRoute

/** "Videos" grid. */
@Serializable
data object VideosRoute

/** Full-screen viewer, opened on [mediaId] within a context. */
@Serializable
data class ViewerRoute(
    val contextType: String,
    val contextArg: Long,
    val mediaId: Long,
    /** Opened for another app (VIEW intent): back hands control back to it. */
    val exitOnBack: Boolean = false,
    /** Search input ([MediaContext.Search]), empty for the other contexts. */
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
 * Full-screen video player. [externalUri]: video received from another app and not indexed
 * (FileProvider, partial selection…), played directly from the granted URI; [mediaId] is then -1.
 * [exitOnBack]: opened for another app, back hands control back to it (finishes the activity).
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
 * Image received from another app and missing from the index (FileProvider URI, photo picker,
 * partial access…): shown read-only from the granted URI.
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

/** OneDrive account (sign-in, sign-out). */
@Serializable
data object AccountRoute

/** Storage: space used, cache limits, cache clearing. */
@Serializable
data object StorageRoute

/** Full-text search (name, folder, album, date, type). */
@Serializable
data object SearchRoute

/** "Hide albums": visibility of each album in Albums and Photos. */
@Serializable
data object AlbumVisibilityRoute

/** Space saver: phone copies already on OneDrive, reviewed before the trash. */
@Serializable
data object SpaceSaverRoute

fun encodeContext(context: MediaContext): Pair<String, Long> = when (context) {
    MediaContext.Timeline -> "timeline" to 0L
    MediaContext.Favorites -> "favorites" to 0L
    MediaContext.Videos -> "videos" to 0L
    is MediaContext.Album -> "album" to context.albumId
    is MediaContext.Single -> "single" to context.mediaId
    // The search input is carried separately (ViewerRoute.contextQuery).
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
