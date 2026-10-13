package fr.eva.cloudgallery.data.cloud.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Décisions de planification du travail des copies hors ligne et suivi de son cycle de vie. */
class OfflineJobTrackerTest {

    @Test
    fun decision_activeJobCoversCompatibleTasks() {
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.UNMETERED, active = JobNetwork.UNMETERED, pendingReliable = true, pending = null))
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.ANY, active = JobNetwork.UNMETERED, pendingReliable = false, pending = null))
        // Travail « tout réseau » (Télécharger maintenant terminé) : les tâches Wi-Fi exigent un nouveau travail.
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = JobNetwork.ANY, pendingReliable = true, pending = JobNetwork.ANY))
    }

    @Test
    fun decision_pendingJobTrustedOnlyIfNotAlreadyEnded() {
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = JobNetwork.UNMETERED))
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = JobNetwork.ANY))
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = null))
        // Travail terminé (jobFinished pas encore traité) encore renvoyé par JobScheduler : ignoré.
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = false, pending = JobNetwork.UNMETERED))
    }

    @Test
    fun tracker_endedJobMakesPendingUnreliableUntilNextSchedule() {
        val tracker = OfflineJobTracker()
        // Démarrage du processus : un travail planifié par un processus précédent est valable.
        assertTrue(tracker.pendingReliable)
        val seq = tracker.onJobStarted(JobNetwork.UNMETERED)
        assertEquals(JobNetwork.UNMETERED, tracker.active)
        tracker.onJobEnded(seq, rescheduled = false)
        assertNull(tracker.active)
        assertFalse(tracker.pendingReliable)
        tracker.onScheduled()
        assertTrue(tracker.pendingReliable)
    }

    @Test
    fun tracker_rescheduledJobStaysReliable() {
        val tracker = OfflineJobTracker()
        tracker.onScheduled()
        val seq = tracker.onJobStarted(JobNetwork.ANY)
        tracker.onJobEnded(seq, rescheduled = true)
        assertNull(tracker.active)
        assertTrue(tracker.pendingReliable)
    }

    @Test
    fun tracker_replacedJobEndingLateDoesNotHideTheNewOne() {
        val tracker = OfflineJobTracker()
        tracker.onScheduled()
        val old = tracker.onJobStarted(JobNetwork.ANY)
        // Replanification (réglage changé) : un nouveau travail remplace l'ancien, qui démarre aussitôt.
        tracker.onScheduled()
        val current = tracker.onJobStarted(JobNetwork.UNMETERED)
        // L'ancien finit après : le nouveau reste actif et le travail planifié reste valable.
        tracker.onJobEnded(old, rescheduled = false)
        assertEquals(JobNetwork.UNMETERED, tracker.active)
        assertTrue(tracker.pendingReliable)
        tracker.onJobEnded(current, rescheduled = false)
        assertFalse(tracker.pendingReliable)
    }
}
