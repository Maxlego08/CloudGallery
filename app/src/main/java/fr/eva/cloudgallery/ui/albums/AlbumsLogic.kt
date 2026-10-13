package fr.eva.cloudgallery.ui.albums

import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType

/**
 * Pure logic of the Albums tab (no Android dependency), unit tested.
 */
object AlbumsLogic {

    /** Number of columns of the album grid: 3 in portrait, 5 in landscape. */
    fun columnCount(isLandscape: Boolean): Int = if (isLandscape) 5 else 3

    /**
     * Displayed albums: empty albums are hidden, except virtual albums
     * (created by the user, who must be able to find them to fill them).
     */
    fun visibleAlbums(albums: List<AlbumSummary>): List<AlbumSummary> =
        albums.filter { it.mediaCount > 0 || it.type == AlbumType.VIRTUAL }

    /** Removes from the selection the albums that are no longer displayed. */
    fun pruneSelection(selection: Set<Long>, albums: List<AlbumSummary>): Set<Long> {
        if (selection.isEmpty()) return selection
        val ids = albums.mapTo(HashSet()) { it.id }
        val pruned = selection.filterTo(LinkedHashSet()) { it in ids }
        return if (pruned.size == selection.size) selection else pruned
    }

    /** Toggles an album in the selection. */
    fun toggle(selection: Set<Long>, id: Long): Set<Long> =
        if (id in selection) selection - id else selection + id

    /** "All": selects everything, or clears the selection if everything is already selected. */
    fun toggleAll(selection: Set<Long>, albums: List<AlbumSummary>): Set<Long> =
        if (isAllSelected(selection, albums)) emptySet() else albums.mapTo(LinkedHashSet()) { it.id }

    fun isAllSelected(selection: Set<Long>, albums: List<AlbumSummary>): Boolean =
        albums.isNotEmpty() && albums.all { it.id in selection }

    /** Actions available in the bottom bar for a given selection. */
    fun selectionActions(selected: List<AlbumSummary>): AlbumSelectionActions {
        val pinTarget = selected.any { !it.isPinned }
        return AlbumSelectionActions(
            canRename = selected.size == 1,
            canPin = selected.isNotEmpty(),
            pinTarget = pinTarget,
            canDelete = selected.isNotEmpty() && selected.all { it.type == AlbumType.VIRTUAL },
        )
    }

    /** Renaming a folder album only changes the displayed name (nothing is renamed on disk). */
    fun renameIsDisplayOnly(album: AlbumSummary): Boolean = album.type != AlbumType.VIRTUAL

    /** Normalized name entered by the user, or null if it is blank. */
    fun normalizeName(input: String): String? = input.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
}

/**
 * State of the selection actions.
 * @property pinTarget true => the action pins (at least one album is unpinned), false => it unpins.
 */
data class AlbumSelectionActions(
    val canRename: Boolean,
    val canPin: Boolean,
    val pinTarget: Boolean,
    val canDelete: Boolean,
)
