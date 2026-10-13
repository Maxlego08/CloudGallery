package fr.eva.cloudgallery.domain.model

import kotlinx.serialization.Serializable

/**
 * Ordered set of media being navigated (grid, viewer...).
 * Serializable so it can be passed in navigation routes.
 */
@Serializable
sealed interface MediaContext {
    @Serializable
    data object Timeline : MediaContext

    @Serializable
    data object Favorites : MediaContext

    @Serializable
    data object Videos : MediaContext

    @Serializable
    data class Album(val albumId: Long) : MediaContext

    /** Results of a full-text search (user input, as typed). */
    @Serializable
    data class Search(val query: String) : MediaContext

    /** A single media item opened from another app (VIEW intent). */
    @Serializable
    data class Single(val mediaId: Long) : MediaContext
}
