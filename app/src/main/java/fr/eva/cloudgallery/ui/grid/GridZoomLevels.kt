package fr.eva.cloudgallery.ui.grid

import kotlin.math.roundToInt

/** Regroupement des cellules de la grille : par jour (zoom rapproché) ou par mois (zoom éloigné). */
enum class GridGrouping { DAY, MONTH }

/**
 * Niveaux de zoom de la grille, comme Samsung Galerie : 0 = plus grandes miniatures.
 * Fonctions pures (testées unitairement).
 */
object GridZoomLevels {
    /** Nombre de colonnes en portrait pour chaque niveau. */
    val PORTRAIT_COLUMNS: List<Int> = listOf(2, 3, 4, 6, 9)

    const val MIN_LEVEL = 0
    val MAX_LEVEL: Int = PORTRAIT_COLUMNS.lastIndex
    const val DEFAULT_LEVEL = 1

    /** Facteur appliqué en paysage (écran plus large → plus de colonnes). */
    const val LANDSCAPE_FACTOR = 1.75f

    /** Dernier niveau regroupé par jour ; au-delà, regroupement par mois. */
    private const val LAST_DAY_LEVEL = 2

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun columns(level: Int, landscape: Boolean): Int {
        val portrait = PORTRAIT_COLUMNS[clamp(level)]
        return if (landscape) (portrait * LANDSCAPE_FACTOR).roundToInt() else portrait
    }

    fun grouping(level: Int): GridGrouping =
        if (clamp(level) <= LAST_DAY_LEVEL) GridGrouping.DAY else GridGrouping.MONTH

    /**
     * Niveau résultant d'un pincement. [delta] < 0 : écarter les doigts (miniatures plus grandes,
     * moins de colonnes) ; [delta] > 0 : pincer (plus de colonnes).
     */
    fun step(level: Int, delta: Int): Int = clamp(clamp(level) + delta)
}
