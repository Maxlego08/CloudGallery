package fr.eva.cloudgallery.ui.grid

import kotlin.math.roundToInt

/** Grouping of the grid cells: by day (zoomed in) or by month (zoomed out). */
enum class GridGrouping { DAY, MONTH }

/**
 * Grid zoom levels, like Samsung Gallery: 0 = largest thumbnails.
 * Pure functions (unit-tested).
 */
object GridZoomLevels {
    /** Number of portrait columns for each level. */
    val PORTRAIT_COLUMNS: List<Int> = listOf(2, 3, 4, 6, 9)

    const val MIN_LEVEL = 0
    val MAX_LEVEL: Int = PORTRAIT_COLUMNS.lastIndex
    const val DEFAULT_LEVEL = 1

    /** Factor applied in landscape (wider screen → more columns). */
    const val LANDSCAPE_FACTOR = 1.75f

    /** Last level grouped by day; beyond it, grouped by month. */
    private const val LAST_DAY_LEVEL = 2

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun columns(level: Int, landscape: Boolean): Int {
        val portrait = PORTRAIT_COLUMNS[clamp(level)]
        return if (landscape) (portrait * LANDSCAPE_FACTOR).roundToInt() else portrait
    }

    fun grouping(level: Int): GridGrouping =
        if (clamp(level) <= LAST_DAY_LEVEL) GridGrouping.DAY else GridGrouping.MONTH

    /**
     * Level resulting from a pinch. [delta] < 0: spreading the fingers (larger thumbnails, fewer
     * columns); [delta] > 0: pinching in (more columns).
     */
    fun step(level: Int, delta: Int): Int = clamp(clamp(level) + delta)
}
