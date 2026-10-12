package fr.eva.cloudgallery.domain.model

import kotlinx.serialization.Serializable

/**
 * Ensemble ordonné de médias dans lequel on navigue (grille, visionneuse…).
 * Sérialisable pour être transmis dans les routes de navigation.
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

    /** Résultats d'une recherche plein texte (saisie de l'utilisateur, telle quelle). */
    @Serializable
    data class Search(val query: String) : MediaContext

    /** Un seul média ouvert depuis une autre application (intent VIEW). */
    @Serializable
    data class Single(val mediaId: Long) : MediaContext
}
