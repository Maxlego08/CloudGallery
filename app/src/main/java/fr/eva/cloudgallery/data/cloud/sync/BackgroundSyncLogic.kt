package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccountState

/*
 * Logique pure de la synchronisation OneDrive en arrière-plan (testée en JVM).
 */

/** Travail périodique à planifier, annuler, ou laisser tel quel. */
internal sealed interface BackgroundSyncPlan {
    /** Etat du compte pas encore connu (démarrage, lecture en échec, déconnexion en cours). */
    data object Unchanged : BackgroundSyncPlan

    data object Cancel : BackgroundSyncPlan

    /** Toutes les [intervalHours] heures, sur un réseau non facturé seulement si [unmeteredOnly]. */
    data class Schedule(val intervalHours: Long, val unmeteredOnly: Boolean) : BackgroundSyncPlan
}

/**
 * Travail périodique voulu : rien sans OneDrive dans ce build, réglage désactivé ou compte
 * déconnecté ; planifié pour un compte connecté (ou à reconnecter : le passage ne fait alors
 * rien et reprendra après la reconnexion). Un état transitoire ne change rien.
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
