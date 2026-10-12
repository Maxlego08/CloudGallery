package fr.eva.cloudgallery.data.cloud.offline

import android.app.job.JobParameters
import fr.eva.cloudgallery.data.cloud.sync.CloudStamp
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineStatus

/*
 * Logique pure des copies hors ligne (testée en JVM).
 */

/** Marge d'espace libre gardée sur l'appareil en plus des copies demandées (200 Mio). */
internal const val OFFLINE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

/** Au-delà de ce nombre d'éléments, « Conserver hors ligne » demande confirmation. */
internal const val LARGE_BATCH_ITEMS = 200

/** Au-delà de cette taille totale (1 Go), « Conserver hors ligne » demande confirmation. */
internal const val LARGE_BATCH_BYTES = 1_000_000_000L

/** Tentatives d'une copie avant de la marquer en échec (erreurs passagères : réseau, limitation). */
internal const val MAX_DOWNLOAD_ATTEMPTS = 5

/** Taille totale à télécharger : tailles connues seulement (une taille ≤ 0 compte pour 0). */
internal fun estimateBytes(sizes: Iterable<Long>): Long = sizes.sumOf { it.coerceAtLeast(0L) }

/**
 * Octets manquants pour copier [requested] octets en gardant [margin] libres, d'après l'espace
 * allouable [allocatable] (StorageManager.getAllocatableBytes). 0 si l'espace suffit.
 */
internal fun missingSpace(allocatable: Long, requested: Long, margin: Long = OFFLINE_SPACE_MARGIN_BYTES): Long =
    (requested.coerceAtLeast(0L) + margin - allocatable.coerceAtLeast(0L)).coerceAtLeast(0L)

/** Suite à donner à une demande d'espace disque. */
internal enum class SpaceAction {
    /** L'espace libre suffit déjà. */
    ENOUGH,

    /**
     * L'espace libre ne suffit pas, mais le système peut libérer du cache d'autres applis :
     * `StorageManager.allocateBytes` doit être appelé (une écriture ordinaire ne le libère pas).
     */
    ALLOCATE,

    /** Même en libérant le cache des autres applis, l'espace manque. */
    NO_SPACE,
}

/**
 * Espace pour [needed] octets en gardant [margin] libres : [usable] = espace libre du volume
 * (File.getUsableSpace), [allocatable] = espace obtenable avec le cache que le système peut
 * libérer (StorageManager.getAllocatableBytes, en général plus grand). D'après la doc de
 * getAllocatableBytes, demander plus que cette valeur à allocateBytes échoue.
 */
internal fun spaceAction(usable: Long, allocatable: Long, needed: Long, margin: Long): SpaceAction = when {
    missingSpace(usable, needed, margin) == 0L -> SpaceAction.ENOUGH
    missingSpace(allocatable, needed, margin) == 0L -> SpaceAction.ALLOCATE
    else -> SpaceAction.NO_SPACE
}

/** Lot à confirmer avant téléchargement (taille totale et espace libre affichés). */
internal fun isLargeBatch(count: Int, bytes: Long): Boolean = count > LARGE_BATCH_ITEMS || bytes > LARGE_BATCH_BYTES

/**
 * Contenu d'un élément conservé hors ligne modifié sur OneDrive (synchro) : nouveau
 * téléchargement de la copie. Une version inconnue d'un côté compte comme un changement
 * seulement si l'autre est connue (une copie sans version ne doit pas rester figée).
 */
internal fun requeueOnCTagChange(hasOfflineCopy: Boolean, oldCTag: String?, newCTag: String?): Boolean =
    hasOfflineCopy && newCTag != null && oldCTag != newCTag

/** Type de réseau exigé par le travail de téléchargement. */
enum class JobNetwork { UNMETERED, ANY }

/**
 * Réseau du travail : Wi-Fi seulement d'après le réglage, sauf si une tâche en attente fait partie
 * d'un lot « Télécharger maintenant » ([overrideNow]).
 */
internal fun jobNetworkFor(wifiOnly: Boolean, overrideNow: Boolean): JobNetwork =
    if (wifiOnly && !overrideNow) JobNetwork.UNMETERED else JobNetwork.ANY

/**
 * Un travail en cours qui exige [active] prend aussi en charge des tâches qui exigent
 * [required] : un travail limité au Wi-Fi convient à tout ; un travail « tout réseau » ne
 * télécharge les tâches limitées au Wi-Fi que s'il se trouve sur un réseau non facturé (voir
 * [allowedOnNetwork]) : il ne suffit donc pas pour elles.
 */
internal fun jobCovers(active: JobNetwork, required: JobNetwork): Boolean =
    active == JobNetwork.UNMETERED || required == JobNetwork.ANY

/**
 * Tâches qu'un passage peut télécharger sur le réseau actuel : toutes (null) sans le réglage
 * « Wi-Fi uniquement » ou sur un réseau non facturé ; sinon seulement celles d'un lot
 * « Télécharger maintenant » ([overridden]). Les autres restent en attente du Wi-Fi.
 */
internal fun allowedOnNetwork(wifiOnly: Boolean, unmeteredNetwork: Boolean, overridden: Set<Long>): Set<Long>? =
    if (!wifiOnly || unmeteredNetwork) null else overridden

/**
 * Une tâche hors lot « Télécharger maintenant » ([overridden] faux) attend le Wi-Fi : réglage
 * actif et réseau actuel absent ou facturé.
 */
internal fun waitsForUnmetered(wifiOnly: Boolean, overridden: Boolean, connected: Boolean, unmetered: Boolean): Boolean =
    wifiOnly && !overridden && (!connected || !unmetered)

/**
 * Tâche couverte par « Télécharger maintenant » : identifiant et date de mise en file (SQLite
 * peut réattribuer l'identifiant d'une tâche supprimée à une nouvelle tâche).
 */
internal data class OverrideKey(val taskId: Long, val createdAt: Long)

/** Etat d'une tâche de la file, tel que lu en base. */
internal enum class TaskState { QUEUED, RUNNING, PAUSED, FAILED }

/** Etat hors ligne affiché pour un média : tâche ouverte éventuelle et copie présente. */
internal fun offlineStatus(
    taskState: TaskState?,
    taskError: DownloadError?,
    bytesDone: Long,
    bytesTotal: Long,
    hasOffline: Boolean,
    waitingForUnmetered: Boolean,
): OfflineItemState = when (taskState) {
    null -> if (hasOffline) OfflineItemState(OfflineStatus.AVAILABLE) else OfflineItemState.NONE
    TaskState.QUEUED, TaskState.RUNNING -> OfflineItemState(
        status = when {
            hasOffline -> OfflineStatus.UPDATING
            taskState == TaskState.RUNNING -> OfflineStatus.DOWNLOADING
            else -> OfflineStatus.QUEUED
        },
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        waitingForUnmetered = taskState == TaskState.QUEUED && waitingForUnmetered,
    )
    // Copie présente : elle reste servie, mais la mise à jour arrêtée est signalée (reprise ou
    // annulation possibles pour cet élément).
    TaskState.PAUSED -> if (hasOffline) {
        OfflineItemState(OfflineStatus.UPDATE_STOPPED, bytesDone = bytesDone, bytesTotal = bytesTotal)
    } else {
        OfflineItemState(OfflineStatus.PAUSED, bytesDone = bytesDone, bytesTotal = bytesTotal)
    }
    TaskState.FAILED -> if (hasOffline) {
        OfflineItemState(OfflineStatus.UPDATE_STOPPED, error = taskError ?: DownloadError.SERVER)
    } else {
        OfflineItemState(OfflineStatus.FAILED, error = taskError ?: DownloadError.SERVER)
    }
}

/** Suite donnée à l'échec du téléchargement d'une copie. */
internal enum class FailureAction {
    /** Erreur passagère : la tâche reste en file, le travail est relancé plus tard (attente du système). */
    RETRY_LATER,

    /** Erreur propre à cet élément : la tâche passe en échec, les suivantes continuent. */
    FAIL,

    /** Erreur qui touchera toutes les tâches (compte, espace) : toute la file passe en échec. */
    FAIL_ALL,

    /**
     * Contenu plus récent sur OneDrive que dans l'index : la tâche reste en attente sans compter
     * d'essai et laisse passer les suivantes ; elle repart quand la synchronisation a mis l'index
     * à jour (voir la requête des tâches téléchargeables).
     */
    WAIT_FOR_INDEX,
}

/** Décision après un échec avec [error], [attempts] essais comptés (1 pour le premier). */
internal fun failureAction(error: DownloadError, attempts: Int): FailureAction = when (error) {
    DownloadError.AUTH_REQUIRED, DownloadError.NO_SPACE -> FailureAction.FAIL_ALL
    DownloadError.NOT_FOUND, DownloadError.CANCELLED -> FailureAction.FAIL
    DownloadError.CONTENT_CHANGED -> FailureAction.WAIT_FOR_INDEX
    DownloadError.NETWORK, DownloadError.THROTTLED, DownloadError.SERVER, DownloadError.SIZE_MISMATCH ->
        if (attempts < MAX_DOWNLOAD_ATTEMPTS) FailureAction.RETRY_LATER else FailureAction.FAIL
}

/**
 * L'échec compte-t-il comme un essai ? Pas un contenu modifié (retard de l'index, pas un défaut
 * de l'élément), ni une coupure réseau après des octets reçus pendant cet essai (Wi-Fi perdu en
 * cours de route : le fichier partiel progresse, la copie n'est pas en cause).
 */
internal fun countsAsAttempt(error: DownloadError, progressed: Boolean): Boolean = when (error) {
    DownloadError.CONTENT_CHANGED -> false
    DownloadError.NETWORK -> !progressed
    else -> true
}

/** Remise en ordre de la file au premier retour au premier plan d'un processus. */
internal enum class StartupRecovery {
    /** Tâches « en cours » d'un processus disparu remises en attente. */
    REQUEUE,

    /** Processus arrêté par l'utilisateur pendant un téléchargement : toute la file en pause. */
    PAUSE,
}

/**
 * [userRequestedExit] : dernier arrêt du processus demandé par l'utilisateur (arrêt forcé,
 * Gestionnaire de tâches, mais aussi simple balayage depuis les applis récentes). Seul un arrêt
 * pendant un téléchargement ([hadRunningTasks] : tâches restées « en cours ») met la file en
 * pause (décision D3) ; balayer l'appli sans téléchargement actif ne change rien.
 */
internal fun startupRecovery(firstStart: Boolean, userRequestedExit: Boolean, hadRunningTasks: Boolean): StartupRecovery =
    if (firstStart && userRequestedExit && hadRunningTasks) StartupRecovery.PAUSE else StartupRecovery.REQUEUE

/** Lecture tolérante d'un code d'erreur enregistré. */
internal fun downloadErrorOrNull(name: String?): DownloadError? =
    name?.let { n -> DownloadError.entries.firstOrNull { it.name == n } }

/** Suite donnée à l'arrêt du travail par le système ([JobParameters.getStopReason]). */
internal enum class StopAction {
    /** L'appli a annulé le travail (« Annuler », purge) : rien à reprendre. */
    NONE,

    /** L'utilisateur l'a arrêté (Gestionnaire de tâches) : file en pause jusqu'à sa reprise. */
    PAUSE,

    /** Contrainte perdue (réseau, Wi-Fi), délai, système : reprise automatique plus tard. */
    RESCHEDULE,
}

internal fun stopActionFor(stopReason: Int): StopAction = when (stopReason) {
    JobParameters.STOP_REASON_CANCELLED_BY_APP -> StopAction.NONE
    JobParameters.STOP_REASON_USER -> StopAction.PAUSE
    else -> StopAction.RESCHEDULE
}

/**
 * Fin du délai de grâce d'éléments supprimés de OneDrive (décision D1) : [purge] sont retirés
 * comme avant ; [awaitingDecision] ont une copie hors ligne et sont gardés (masqués) jusqu'à la
 * décision de l'utilisateur (« Supprimer les copies » ou « Les conserver »).
 */
internal data class GraceExpiryPlan(val purge: List<CloudStamp>, val awaitingDecision: List<CloudStamp>)

internal fun planGraceExpiry(expired: List<CloudStamp>, mediaWithOfflineCopy: Set<Long>): GraceExpiryPlan {
    val (awaiting, purge) = expired.partition { it.mediaId in mediaWithOfflineCopy }
    return GraceExpiryPlan(purge = purge, awaitingDecision = awaiting)
}

/**
 * Le fichier téléchargé ([gotCTag], [gotSize], [gotETag]) est bien la version de l'élément dans
 * l'index au moment d'enregistrer. cTag connus des deux côtés : ils font foi. Sinon (P1-11 :
 * deux versions inconnues ne sont jamais « identiques » par défaut) la taille doit être égale,
 * ainsi que l'eTag s'il est connu des deux côtés.
 */
internal fun contentMatches(
    indexCTag: String?,
    indexSize: Long,
    indexETag: String?,
    gotCTag: String?,
    gotSize: Long,
    gotETag: String?,
): Boolean = when {
    indexCTag != null && gotCTag != null -> indexCTag == gotCTag
    indexSize != gotSize -> false
    indexETag != null && gotETag != null -> indexETag == gotETag
    else -> true
}

/** Une copie hors ligne qui est la dernière copie de l'élément : « Libérer » demande confirmation. */
internal fun releaseNeedsConfirmation(lastCopies: Int): Boolean = lastCopies > 0
