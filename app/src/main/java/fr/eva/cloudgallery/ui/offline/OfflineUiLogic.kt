package fr.eva.cloudgallery.ui.offline

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import java.util.UUID

/*
 * UI follow-ups of the "Keep offline" / "Release" actions (pure logic, tested on the JVM).
 */

/** Dialog to show after an offline action. */
sealed interface OfflinePrompt {
    /** Large batch: confirm with the total size and the available space. */
    data class ConfirmLargeBatch(val ids: List<Long>, val count: Int, val bytes: Long, val freeBytes: Long) : OfflinePrompt

    /** Queue waiting for Wi-Fi: offer "Download now". */
    data class WaitingForWifi(val count: Int, val bytes: Long) : OfflinePrompt

    /** Insufficient space: offer the system storage management screen. */
    data class NoSpace(val missingBytes: Long, val storageUuid: UUID?) : OfflinePrompt

    /** "Release" of at least one last copy (item deleted from OneDrive): confirmation. */
    data class ConfirmRelease(val ids: List<Long>, val lastCopies: Int) : OfflinePrompt
}

/** Outcome of an offline action: dialog, or short message. */
sealed interface OfflineFeedback {
    data class Prompt(val prompt: OfflinePrompt) : OfflineFeedback
    data class Text(@StringRes val resId: Int) : OfflineFeedback
    data class Plural(@PluralsRes val resId: Int, val count: Int) : OfflineFeedback
}

/** Follow-up to the result of "Keep offline" for the items [ids]. */
internal fun keepFeedback(result: KeepOfflineResult, ids: List<Long>): OfflineFeedback = when (result) {
    KeepOfflineResult.NotSignedIn -> OfflineFeedback.Text(R.string.offline_not_signed_in)
    KeepOfflineResult.NothingToDo -> OfflineFeedback.Text(R.string.offline_nothing_to_do)
    is KeepOfflineResult.NoSpace -> OfflineFeedback.Prompt(OfflinePrompt.NoSpace(result.missingBytes, result.storageUuid))
    is KeepOfflineResult.NeedsConfirmation ->
        OfflineFeedback.Prompt(OfflinePrompt.ConfirmLargeBatch(ids, result.count, result.bytes, result.freeBytes))
    is KeepOfflineResult.Enqueued -> if (result.waitingForUnmetered) {
        OfflineFeedback.Prompt(OfflinePrompt.WaitingForWifi(result.count, result.bytes))
    } else {
        OfflineFeedback.Plural(R.plurals.offline_enqueued, result.count)
    }
    is KeepOfflineResult.SchedulingRefused -> OfflineFeedback.Text(R.string.offline_scheduling_refused)
}

/** Follow-up to "Release" once the last copies are counted. */
internal sealed interface ReleaseStep {
    /** No last copy: immediate release. */
    data object Release : ReleaseStep

    /** At least one last copy: destructive confirmation first. */
    data class Confirm(val prompt: OfflinePrompt.ConfirmRelease) : ReleaseStep

    /** Count failed (read error): nothing is deleted without a possible confirmation. */
    data object Abort : ReleaseStep
}

/**
 * "Release": confirmation only if a last copy is involved, otherwise immediate;
 * unknown count ([lastCopies] null): abort (never a deletion without verification).
 */
internal fun releaseStep(ids: List<Long>, lastCopies: Int?): ReleaseStep = when {
    lastCopies == null -> ReleaseStep.Abort
    lastCopies > 0 -> ReleaseStep.Confirm(OfflinePrompt.ConfirmRelease(ids, lastCopies))
    else -> ReleaseStep.Release
}

/** Offline entries of a selection menu (grid). */
internal enum class OfflineMenuState { NONE, KEEP, RELEASE, BOTH }

/**
 * Entries to offer for a selection: "Keep offline" if there are OneDrive items
 * without a copy ([keepable]), "Free up space" if there are copies ([releasable]).
 */
internal fun offlineMenuState(keepable: Int, releasable: Int): OfflineMenuState = when {
    keepable > 0 && releasable > 0 -> OfflineMenuState.BOTH
    keepable > 0 -> OfflineMenuState.KEEP
    releasable > 0 -> OfflineMenuState.RELEASE
    else -> OfflineMenuState.NONE
}

/** Offline copies that a purge (sign-out, remaining data, other account) would delete. */
internal sealed interface PurgeImpactUi {
    /** Loading: the confirmation stays disabled. */
    data object Loading : PurgeImpactUi

    /** Read failed: cautious warning (last copies possible). */
    data object Unknown : PurgeImpactUi

    data class Known(val copies: Int, val lastCopies: Int) : PurgeImpactUi
}

/** Additional text of a purge confirmation. */
internal sealed interface PurgeNotice {
    /** No offline copy (or still loading). */
    data object None : PurgeNotice

    /** [copies] copies deleted, of which [lastCopies] are last copies of items deleted from OneDrive. */
    data class Copies(val copies: Int, val lastCopies: Int) : PurgeNotice

    /** Unknown count: copies, possibly last copies, will be deleted. */
    data object Unknown : PurgeNotice
}

internal fun purgeNotice(impact: PurgeImpactUi): PurgeNotice = when (impact) {
    PurgeImpactUi.Loading -> PurgeNotice.None
    PurgeImpactUi.Unknown -> PurgeNotice.Unknown
    is PurgeImpactUi.Known -> if (impact.copies <= 0) PurgeNotice.None else PurgeNotice.Copies(impact.copies, impact.lastCopies)
}

/** The purge can only be confirmed once its effect on offline copies is known (or reading it failed). */
internal fun purgeConfirmEnabled(impact: PurgeImpactUi): Boolean = impact !is PurgeImpactUi.Loading
