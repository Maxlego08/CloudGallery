package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccountState

/*
 * Pure logic of background OneDrive sync (tested on the JVM).
 */

/** Periodic work to schedule, cancel, or leave as is. */
internal sealed interface BackgroundSyncPlan {
    /** Account state not known yet (startup, failed read, sign-out in progress). */
    data object Unchanged : BackgroundSyncPlan

    data object Cancel : BackgroundSyncPlan

    /** Every [intervalHours] hours, on an unmetered network only if [unmeteredOnly]. */
    data class Schedule(val intervalHours: Long, val unmeteredOnly: Boolean) : BackgroundSyncPlan
}

/**
 * Desired periodic work: none without OneDrive in this build, with the setting disabled or a
 * signed-out account; scheduled for a signed-in account (or one needing sign-in again: the pass
 * then does nothing and resumes after signing in again). A transient state changes nothing.
 */
internal fun backgroundSyncPlan(
    configured: Boolean,
    interval: BackgroundSyncInterval,
    wifiOnly: Boolean,
    account: CloudAccountState,
): BackgroundSyncPlan {
    if (!configured) return BackgroundSyncPlan.Cancel
    val hours = interval.hours ?: return BackgroundSyncPlan.Cancel
    return when (account) {
        is CloudAccountState.SignedIn, is CloudAccountState.NeedsReauth -> BackgroundSyncPlan.Schedule(hours, wifiOnly)
        is CloudAccountState.SignedOut, CloudAccountState.NotConfigured -> BackgroundSyncPlan.Cancel
        CloudAccountState.Loading, CloudAccountState.SigningOut, is CloudAccountState.Error -> BackgroundSyncPlan.Unchanged
    }
}
