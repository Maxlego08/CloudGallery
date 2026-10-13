package fr.eva.cloudgallery.ui.grid

import androidx.compose.runtime.Immutable
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.ui.offline.OfflineMenuState
import fr.eva.cloudgallery.ui.offline.offlineMenuState

/*
 * Targets of file actions in a selection. An item that exists only on OneDrive is never copied
 * or deleted (OneDrive access is read-only: only a file present on the device can be modified);
 * it can be shared, in which case its original is downloaded.
 */

/** Usable local file: visible local source and known MediaStore URI. */
internal fun isLocalActionTarget(hasLocal: Boolean, hasUri: Boolean): Boolean = hasLocal && hasUri

/** Items of the selection to which local file actions apply. */
internal fun localActionTargets(items: List<MediaSummary>): List<MediaSummary> =
    items.filter { isLocalActionTarget(it.hasLocal, it.uri != null) }

/**
 * Number of local files ([local]) and shareable items ([shareable]: local files, offline copies
 * and OneDrive items) in the selection [forSelection], computed in the database off the main
 * thread (the selection may contain items that the paging has not loaded).
 */
@Immutable
data class SelectionTargets(
    val forSelection: Set<Long>,
    val local: Int,
    val shareable: Int = local,
    /** OneDrive items without an offline copy ("Keep offline"). */
    val keepableOffline: Int = 0,
    /** Offline copies ("Free up space"). */
    val withOfflineCopy: Int = 0,
)

/** Counts shown to the user: files processed and OneDrive items left out. */
internal data class ActionCounts(val local: Int, val skippedCloud: Int)

/**
 * Breakdown of [selection] based on [targets] if it matches this selection; otherwise
 * (computation in progress), the whole selection is counted and the action filters by itself.
 */
internal fun actionCounts(selection: Set<Long>, targets: SelectionTargets?): ActionCounts =
    if (targets != null && targets.forSelection == selection) {
        val local = targets.local.coerceIn(0, selection.size)
        ActionCounts(local = local, skippedCloud = selection.size - local)
    } else {
        ActionCounts(local = selection.size, skippedCloud = 0)
    }

/**
 * Share / Delete / Copy: disabled when the selection contains no local file (for example a whole
 * OneDrive album), rather than showing buttons that do nothing.
 */
internal fun localActionsEnabled(selection: Set<Long>, targets: SelectionTargets?): Boolean =
    selection.isNotEmpty() && actionCounts(selection, targets).local > 0

/**
 * Share: enabled as soon as the selection contains a shareable item (local file or OneDrive
 * item); also enabled while the computation is in progress (the action filters by itself).
 */
internal fun shareEnabled(selection: Set<Long>, targets: SelectionTargets?): Boolean = when {
    selection.isEmpty() -> false
    targets == null || targets.forSelection != selection -> true
    else -> targets.shareable > 0
}

/**
 * Offline entries of the "More" menu for the selection: only based on counts that match this
 * selection (no entry while computing).
 */
internal fun selectionOfflineMenu(selection: Set<Long>, targets: SelectionTargets?): OfflineMenuState =
    if (selection.isEmpty() || targets == null || targets.forSelection != selection) {
        OfflineMenuState.NONE
    } else {
        offlineMenuState(targets.keepableOffline, targets.withOfflineCopy)
    }
