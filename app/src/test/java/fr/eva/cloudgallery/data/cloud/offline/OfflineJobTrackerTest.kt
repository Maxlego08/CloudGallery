package fr.eva.cloudgallery.data.cloud.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scheduling decisions for the offline copies job and tracking of its lifecycle. */
class OfflineJobTrackerTest {

    @Test
    fun decision_activeJobCoversCompatibleTasks() {
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.UNMETERED, active = JobNetwork.UNMETERED, pendingReliable = true, pending = null))
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.ANY, active = JobNetwork.UNMETERED, pendingReliable = false, pending = null))
        // "Any network" job (Download now finished): Wi-Fi tasks require a new job.
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = JobNetwork.ANY, pendingReliable = true, pending = JobNetwork.ANY))
    }

    @Test
    fun decision_pendingJobTrustedOnlyIfNotAlreadyEnded() {
        assertEquals(ScheduleDecision.KEEP, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = JobNetwork.UNMETERED))
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = JobNetwork.ANY))
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = true, pending = null))
        // Finished job (jobFinished not yet processed) still returned by JobScheduler: ignored.
        assertEquals(ScheduleDecision.SCHEDULE, scheduleDecision(JobNetwork.UNMETERED, active = null, pendingReliable = false, pending = JobNetwork.UNMETERED))
    }

    @Test
    fun tracker_endedJobMakesPendingUnreliableUntilNextSchedule() {
        val tracker = OfflineJobTracker()
        // Process start: a job scheduled by a previous process is valid.
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
        // Rescheduling (setting changed): a new job replaces the old one and starts immediately.
        tracker.onScheduled()
        val current = tracker.onJobStarted(JobNetwork.UNMETERED)
        // The old one finishes afterwards: the new one stays active and the scheduled job stays valid.
        tracker.onJobEnded(old, rescheduled = false)
        assertEquals(JobNetwork.UNMETERED, tracker.active)
        assertTrue(tracker.pendingReliable)
        tracker.onJobEnded(current, rescheduled = false)
        assertFalse(tracker.pendingReliable)
    }
}
