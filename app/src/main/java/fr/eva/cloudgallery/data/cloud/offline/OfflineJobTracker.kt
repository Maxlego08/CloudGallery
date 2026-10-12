package fr.eva.cloudgallery.data.cloud.offline

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Suite à donner à une demande de planification du travail. */
internal enum class ScheduleDecision {
    /** Un travail en cours ou planifié prendra les tâches en charge. */
    KEEP,

    /** Planifier (ou replanifier, ce qui remplace le travail existant). */
    SCHEDULE,
}

/**
 * [required] : réseau exigé par les tâches en attente. [active] : réseau du travail en cours dans
 * ce processus (null s'il n'y en a pas, ou s'il a décidé de se terminer). [pendingReliable] :
 * le travail planifié connu de JobScheduler est encore valable (pas un travail que ce processus
 * a vu se terminer : JobScheduler peut encore le renvoyer juste après `jobFinished`).
 * [pending] : réseau du travail planifié d'après JobScheduler.
 */
internal fun scheduleDecision(
    required: JobNetwork,
    active: JobNetwork?,
    pendingReliable: Boolean,
    pending: JobNetwork?,
): ScheduleDecision = when {
    active != null -> if (jobCovers(active, required)) ScheduleDecision.KEEP else ScheduleDecision.SCHEDULE
    pendingReliable && pending == required -> ScheduleDecision.KEEP
    else -> ScheduleDecision.SCHEDULE
}

/**
 * Suivi, dans ce processus, du travail de téléchargement des copies hors ligne : planifications,
 * démarrage et fin. JobScheduler continue de renvoyer un travail qui vient d'appeler
 * `jobFinished` (traité plus tard sur le thread principal) : sans ce suivi, une tâche ajoutée à
 * ce moment-là serait laissée sans travail.
 *
 * [lock] sérialise les décisions composées : planification ([scheduleDecision]) d'un côté,
 * « reste-t-il du travail, sinon fin » du travail en cours de l'autre. Les numéros de
 * planification distinguent un ancien travail (remplacé) du travail le plus récent.
 */
@Singleton
class OfflineJobTracker @Inject constructor() {

    /** Verrou des décisions « planifier » et « terminer le travail ». */
    internal val lock = Mutex()

    private var scheduleSeq = 0L
    private var endedSeq = -1L
    private var activeSeq: Long? = null
    private var activeNetwork: JobNetwork? = null

    /** Réseau du travail en cours dans ce processus (null : aucun, ou il se termine). */
    internal val active: JobNetwork?
        @Synchronized get() = activeNetwork

    /** Le travail planifié que renvoie JobScheduler n'est pas un travail déjà terminé. */
    internal val pendingReliable: Boolean
        @Synchronized get() = endedSeq < scheduleSeq

    /** Un travail vient d'être planifié (il remplace le précédent). */
    @Synchronized
    internal fun onScheduled() {
        scheduleSeq++
    }

    /** Le travail démarre avec la contrainte [network] ; renvoie son numéro. */
    @Synchronized
    internal fun onJobStarted(network: JobNetwork): Long {
        activeSeq = scheduleSeq
        activeNetwork = network
        return scheduleSeq
    }

    /** Le travail [seq] se termine ; [rescheduled] : il reste planifié (nouvel essai, arrêt par le système). */
    @Synchronized
    internal fun onJobEnded(seq: Long, rescheduled: Boolean) {
        if (activeSeq == seq) {
            activeSeq = null
            activeNetwork = null
        }
        if (!rescheduled && seq > endedSeq) endedSeq = seq
    }
}
