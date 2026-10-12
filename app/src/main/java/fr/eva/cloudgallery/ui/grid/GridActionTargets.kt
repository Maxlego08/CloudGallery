package fr.eva.cloudgallery.ui.grid

import androidx.compose.runtime.Immutable
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.ui.offline.OfflineMenuState
import fr.eva.cloudgallery.ui.offline.offlineMenuState

/*
 * Cibles des actions sur fichiers dans une sélection. Un élément uniquement sur OneDrive n'est
 * jamais copié ni supprimé (accès OneDrive en lecture seule : seul un fichier présent sur
 * l'appareil peut être modifié) ; il peut être partagé, son original étant alors téléchargé.
 */

/** Fichier local utilisable : source locale visible et URI MediaStore connue. */
internal fun isLocalActionTarget(hasLocal: Boolean, hasUri: Boolean): Boolean = hasLocal && hasUri

/** Eléments de la sélection sur lesquels les actions sur fichiers locaux s'appliquent. */
internal fun localActionTargets(items: List<MediaSummary>): List<MediaSummary> =
    items.filter { isLocalActionTarget(it.hasLocal, it.uri != null) }

/**
 * Nombre de fichiers locaux ([local]) et d'éléments partageables ([shareable] : fichiers locaux,
 * copies hors ligne et éléments OneDrive) dans la sélection [forSelection], calculés en base hors
 * du thread principal (la sélection peut contenir des éléments que la pagination n'a pas chargés).
 */
@Immutable
data class SelectionTargets(
    val forSelection: Set<Long>,
    val local: Int,
    val shareable: Int = local,
    /** Eléments OneDrive sans copie hors ligne (« Conserver hors ligne »). */
    val keepableOffline: Int = 0,
    /** Copies hors ligne (« Libérer l'espace »). */
    val withOfflineCopy: Int = 0,
)

/** Nombres présentés à l'utilisateur : fichiers traités et éléments OneDrive non concernés. */
internal data class ActionCounts(val local: Int, val skippedCloud: Int)

/**
 * Répartition de [selection] d'après [targets] s'il correspond à cette sélection ; sinon
 * (calcul en cours), toute la sélection est comptée et l'action filtrera elle-même.
 */
internal fun actionCounts(selection: Set<Long>, targets: SelectionTargets?): ActionCounts =
    if (targets != null && targets.forSelection == selection) {
        val local = targets.local.coerceIn(0, selection.size)
        ActionCounts(local = local, skippedCloud = selection.size - local)
    } else {
        ActionCounts(local = selection.size, skippedCloud = 0)
    }

/**
 * Partager / Supprimer / Copier : désactivés quand la sélection ne contient aucun fichier local
 * (par exemple tout un album OneDrive), plutôt que d'afficher des boutons sans effet.
 */
internal fun localActionsEnabled(selection: Set<Long>, targets: SelectionTargets?): Boolean =
    selection.isNotEmpty() && actionCounts(selection, targets).local > 0

/**
 * Partager : activé dès que la sélection contient un élément partageable (fichier local ou
 * élément OneDrive) ; activé aussi tant que le calcul est en cours (l'action filtre elle-même).
 */
internal fun shareEnabled(selection: Set<Long>, targets: SelectionTargets?): Boolean = when {
    selection.isEmpty() -> false
    targets == null || targets.forSelection != selection -> true
    else -> targets.shareable > 0
}

/**
 * Entrées hors ligne du menu « Plus » pour la sélection : seulement d'après des nombres qui
 * correspondent à cette sélection (aucune entrée pendant le calcul).
 */
internal fun selectionOfflineMenu(selection: Set<Long>, targets: SelectionTargets?): OfflineMenuState =
    if (selection.isEmpty() || targets == null || targets.forSelection != selection) {
        OfflineMenuState.NONE
    } else {
        offlineMenuState(targets.keepableOffline, targets.withOfflineCopy)
    }
