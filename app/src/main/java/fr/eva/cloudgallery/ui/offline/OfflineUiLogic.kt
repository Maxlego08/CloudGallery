package fr.eva.cloudgallery.ui.offline

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.KeepOfflineResult
import java.util.UUID

/*
 * Suites UI des actions « Conserver hors ligne » / « Libérer » (logique pure, testée en JVM).
 */

/** Boîte de dialogue à afficher après une action hors ligne. */
sealed interface OfflinePrompt {
    /** Gros lot : confirmer avec la taille totale et l'espace disponible. */
    data class ConfirmLargeBatch(val ids: List<Long>, val count: Int, val bytes: Long, val freeBytes: Long) : OfflinePrompt

    /** File en attente du Wi-Fi : proposer « Télécharger maintenant ». */
    data class WaitingForWifi(val count: Int, val bytes: Long) : OfflinePrompt

    /** Espace insuffisant : proposer l'écran système de gestion de l'espace. */
    data class NoSpace(val missingBytes: Long, val storageUuid: UUID?) : OfflinePrompt

    /** « Libérer » d'au moins une dernière copie (élément supprimé de OneDrive) : confirmation. */
    data class ConfirmRelease(val ids: List<Long>, val lastCopies: Int) : OfflinePrompt
}

/** Retour d'une action hors ligne : boîte de dialogue, ou message court. */
sealed interface OfflineFeedback {
    data class Prompt(val prompt: OfflinePrompt) : OfflineFeedback
    data class Text(@StringRes val resId: Int) : OfflineFeedback
    data class Plural(@PluralsRes val resId: Int, val count: Int) : OfflineFeedback
}

/** Suite donnée au résultat de « Conserver hors ligne » pour les éléments [ids]. */
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

/** Suite de « Libérer » une fois les dernières copies comptées. */
internal sealed interface ReleaseStep {
    /** Aucune dernière copie : libération immédiate. */
    data object Release : ReleaseStep

    /** Au moins une dernière copie : confirmation destructive d'abord. */
    data class Confirm(val prompt: OfflinePrompt.ConfirmRelease) : ReleaseStep

    /** Compte impossible (erreur de lecture) : rien n'est supprimé sans confirmation possible. */
    data object Abort : ReleaseStep
}

/**
 * « Libérer » : confirmation seulement si une dernière copie est concernée, sinon immédiat ;
 * compte inconnu ([lastCopies] null) : abandon (jamais de suppression sans vérification).
 */
internal fun releaseStep(ids: List<Long>, lastCopies: Int?): ReleaseStep = when {
    lastCopies == null -> ReleaseStep.Abort
    lastCopies > 0 -> ReleaseStep.Confirm(OfflinePrompt.ConfirmRelease(ids, lastCopies))
    else -> ReleaseStep.Release
}

/** Entrées hors ligne d'un menu de sélection (grille). */
internal enum class OfflineMenuState { NONE, KEEP, RELEASE, BOTH }

/**
 * Entrées à proposer pour une sélection : « Conserver hors ligne » s'il y a des éléments OneDrive
 * sans copie ([keepable]), « Libérer l'espace » s'il y a des copies ([releasable]).
 */
internal fun offlineMenuState(keepable: Int, releasable: Int): OfflineMenuState = when {
    keepable > 0 && releasable > 0 -> OfflineMenuState.BOTH
    keepable > 0 -> OfflineMenuState.KEEP
    releasable > 0 -> OfflineMenuState.RELEASE
    else -> OfflineMenuState.NONE
}

/** Copies hors ligne qu'une purge (déconnexion, données restantes, autre compte) supprimerait. */
internal sealed interface PurgeImpactUi {
    /** Lecture en cours : la confirmation reste désactivée. */
    data object Loading : PurgeImpactUi

    /** Lecture impossible : avertissement prudent (dernières copies possibles). */
    data object Unknown : PurgeImpactUi

    data class Known(val copies: Int, val lastCopies: Int) : PurgeImpactUi
}

/** Complément de texte d'une confirmation de purge. */
internal sealed interface PurgeNotice {
    /** Aucune copie hors ligne (ou lecture en cours). */
    data object None : PurgeNotice

    /** [copies] copies supprimées, dont [lastCopies] dernières copies d'éléments supprimés de OneDrive. */
    data class Copies(val copies: Int, val lastCopies: Int) : PurgeNotice

    /** Nombre inconnu : des copies, peut-être des dernières copies, seront supprimées. */
    data object Unknown : PurgeNotice
}

internal fun purgeNotice(impact: PurgeImpactUi): PurgeNotice = when (impact) {
    PurgeImpactUi.Loading -> PurgeNotice.None
    PurgeImpactUi.Unknown -> PurgeNotice.Unknown
    is PurgeImpactUi.Known -> if (impact.copies <= 0) PurgeNotice.None else PurgeNotice.Copies(impact.copies, impact.lastCopies)
}

/** La purge ne peut être confirmée qu'une fois son effet sur les copies hors ligne connu (ou sa lecture échouée). */
internal fun purgeConfirmEnabled(impact: PurgeImpactUi): Boolean = impact !is PurgeImpactUi.Loading
