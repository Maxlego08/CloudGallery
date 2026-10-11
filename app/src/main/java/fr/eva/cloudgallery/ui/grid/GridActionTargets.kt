package fr.eva.cloudgallery.ui.grid

import androidx.compose.runtime.Immutable
import fr.eva.cloudgallery.domain.model.MediaSummary

/*
 * Cibles des actions sur fichiers locaux (partage, suppression, copie) dans une sélection.
 * Un élément uniquement sur OneDrive n'est jamais partagé, copié ni supprimé : l'accès OneDrive
 * est en lecture seule et seul un fichier présent sur l'appareil peut être traité.
 */

/** Fichier local utilisable : source locale visible et URI MediaStore connue. */
internal fun isLocalActionTarget(hasLocal: Boolean, hasUri: Boolean): Boolean = hasLocal && hasUri

/** Eléments de la sélection sur lesquels les actions sur fichiers locaux s'appliquent. */
internal fun localActionTargets(items: List<MediaSummary>): List<MediaSummary> =
    items.filter { isLocalActionTarget(it.hasLocal, it.uri != null) }

/**
 * Nombre de fichiers locaux ([local]) dans la sélection [forSelection], calculé en base hors du
 * thread principal (la sélection peut contenir des éléments que la pagination n'a pas chargés).
 */
@Immutable
data class SelectionTargets(val forSelection: Set<Long>, val local: Int)

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
