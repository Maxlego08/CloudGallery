package fr.eva.cloudgallery.ui.securefolder

/*
 * Pure logic of "Move to Secure Folder" (tested on the JVM). Samsung Secure Folder
 * has no public API: phone files are sent through the Android share sheet to an app
 * inside Secure Folder, then, only if the user confirms that the addition succeeded, the
 * originals go to the Android trash (confirmed by the system). An item only on OneDrive
 * is never involved (OneDrive is read-only).
 */

/**
 * Items sent at once: a single share, then a check by the user in Secure Folder
 * before the trash. Beyond that, the check becomes unrealistic.
 */
internal const val MAX_SECURE_FOLDER_ITEMS = 100

/** A media item of the selection, reduced to what decides whether it is sent. */
internal data class SecureFolderCandidate(
    val id: Long,
    /** Phone file with a MediaStore URI. */
    val hasLocalFile: Boolean,
    /** Also on OneDrive (grouped media): the OneDrive copy stays in the app. */
    val hasCloud: Boolean,
)

/** Result of analyzing a selection. */
internal sealed interface SecureFolderPlan {
    /**
     * Sending possible: [ids] in selection order; [alsoOnOneDrive] of them stay
     * visible via OneDrive; [skippedCloudOnly] OneDrive-only items ignored.
     */
    data class Ready(val ids: List<Long>, val alsoOnOneDrive: Int, val skippedCloudOnly: Int) : SecureFolderPlan

    /** No phone file in the selection. */
    data object NothingLocal : SecureFolderPlan

    /** Too many phone files for a single send ([max] at most). */
    data class TooMany(val max: Int) : SecureFolderPlan
}

internal fun planSecureFolderMove(
    candidates: List<SecureFolderCandidate>,
    max: Int = MAX_SECURE_FOLDER_ITEMS,
): SecureFolderPlan {
    val local = candidates.filter { it.hasLocalFile }
    return when {
        local.isEmpty() -> SecureFolderPlan.NothingLocal
        local.size > max -> SecureFolderPlan.TooMany(max)
        else -> SecureFolderPlan.Ready(
            ids = local.map { it.id },
            alsoOnOneDrive = local.count { it.hasCloud },
            skippedCloudOnly = candidates.size - local.size,
        )
    }
}

/** The action is offered: Secure Folder app installed and at least one phone file. */
internal fun secureFolderActionVisible(secureFolderInstalled: Boolean, localCount: Int): Boolean =
    secureFolderInstalled && localCount > 0

/**
 * Steps of the move. INTRO: explanations before the share sheet; SHARING: share sheet
 * open, waiting for the return to the app; CONFIRM_TRASH: the user says whether
 * the addition succeeded, and only then do the originals go to the trash.
 */
enum class SecureFolderStep { INTRO, SHARING, CONFIRM_TRASH }

/** Next step on returning from the share sheet (ignored if it had not been opened). */
internal fun stepAfterShareReturn(current: SecureFolderStep?): SecureFolderStep? =
    if (current == SecureFolderStep.SHARING) SecureFolderStep.CONFIRM_TRASH else current
