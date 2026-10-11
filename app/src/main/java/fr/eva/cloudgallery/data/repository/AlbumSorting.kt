package fr.eva.cloudgallery.data.repository

import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import java.text.Collator
import java.util.Locale

/**
 * Rang « par défaut » façon Samsung Galerie : Appareil photo, puis Captures d'écran
 * (DCIM/Screenshots chez Samsung, Pictures/Screenshots ailleurs), puis le reste.
 */
internal fun defaultAlbumRank(relativePath: String?): Int {
    val path = relativePath?.trim('/')?.lowercase(Locale.ROOT) ?: return 2
    return when (path) {
        "dcim/camera" -> 0
        "dcim/screenshots", "pictures/screenshots" -> 1
        else -> 2
    }
}

/**
 * Comparateur des albums : épinglés d'abord, puis selon [sort].
 * Le nom comparé est le nom affiché ([AlbumSummary.name], qui intègre déjà customName).
 */
internal fun albumComparator(sort: AlbumSort, locale: Locale = Locale.getDefault()): Comparator<AlbumSummary> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val byName = Comparator<AlbumSummary> { a, b -> collator.compare(a.name, b.name) }
    val pinnedFirst = compareByDescending<AlbumSummary> { it.isPinned }
    val bySort: Comparator<AlbumSummary> = when (sort) {
        AlbumSort.CUSTOM -> compareBy<AlbumSummary> { defaultAlbumRank(it.relativePath) }
            .thenByDescending { it.latestDate }
            .then(byName)
        AlbumSort.NAME -> byName.thenByDescending { it.latestDate }
        AlbumSort.RECENT -> compareByDescending<AlbumSummary> { it.latestDate }.then(byName)
        AlbumSort.COUNT -> compareByDescending<AlbumSummary> { it.mediaCount }.then(byName)
    }
    return pinnedFirst.then(bySort).thenBy { it.id }
}
