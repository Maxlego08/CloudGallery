package fr.eva.cloudgallery.ui.albums

import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType

/**
 * Logique pure de l'onglet Albums (sans dépendance Android), testée unitairement.
 */
object AlbumsLogic {

    /** Nombre de colonnes de la grille d'albums : 3 en portrait, 5 en paysage. */
    fun columnCount(isLandscape: Boolean): Int = if (isLandscape) 5 else 3

    /**
     * Albums affichés : les albums vides sont masqués, sauf les albums virtuels
     * (créés par l'utilisateur, qui doit pouvoir les retrouver pour les remplir).
     */
    fun visibleAlbums(albums: List<AlbumSummary>): List<AlbumSummary> =
        albums.filter { it.mediaCount > 0 || it.type == AlbumType.VIRTUAL }

    /** Retire de la sélection les albums qui ne sont plus affichés. */
    fun pruneSelection(selection: Set<Long>, albums: List<AlbumSummary>): Set<Long> {
        if (selection.isEmpty()) return selection
        val ids = albums.mapTo(HashSet()) { it.id }
        val pruned = selection.filterTo(LinkedHashSet()) { it in ids }
        return if (pruned.size == selection.size) selection else pruned
    }

    /** Bascule d'un album dans la sélection. */
    fun toggle(selection: Set<Long>, id: Long): Set<Long> =
        if (id in selection) selection - id else selection + id

    /** « Tout » : sélectionne tout, ou vide la sélection si tout est déjà sélectionné. */
    fun toggleAll(selection: Set<Long>, albums: List<AlbumSummary>): Set<Long> =
        if (isAllSelected(selection, albums)) emptySet() else albums.mapTo(LinkedHashSet()) { it.id }

    fun isAllSelected(selection: Set<Long>, albums: List<AlbumSummary>): Boolean =
        albums.isNotEmpty() && albums.all { it.id in selection }

    /** Actions disponibles dans la barre inférieure pour une sélection donnée. */
    fun selectionActions(selected: List<AlbumSummary>): AlbumSelectionActions {
        val pinTarget = selected.any { !it.isPinned }
        return AlbumSelectionActions(
            canRename = selected.size == 1,
            canPin = selected.isNotEmpty(),
            pinTarget = pinTarget,
            canDelete = selected.isNotEmpty() && selected.all { it.type == AlbumType.VIRTUAL },
        )
    }

    /** Le renommage d'un album-dossier ne modifie que le nom affiché (rien n'est renommé sur le disque). */
    fun renameIsDisplayOnly(album: AlbumSummary): Boolean = album.type != AlbumType.VIRTUAL

    /** Nom normalisé saisi par l'utilisateur, ou null s'il est vide. */
    fun normalizeName(input: String): String? = input.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
}

/**
 * Etat des actions de sélection.
 * @property pinTarget true => l'action épingle (au moins un album non épinglé), false => elle désépingle.
 */
data class AlbumSelectionActions(
    val canRename: Boolean,
    val canPin: Boolean,
    val pinTarget: Boolean,
    val canDelete: Boolean,
)
