package fr.eva.cloudgallery.data.cloud.offline

import android.app.job.JobParameters
import fr.eva.cloudgallery.data.cloud.sync.CloudStamp
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.OfflineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLogicTest {

    // --- Tailles, espace, gros lots ---------------------------------------------------------

    @Test
    fun estimateBytes_ignoresUnknownSizes() {
        assertEquals(0L, estimateBytes(emptyList()))
        assertEquals(30L, estimateBytes(listOf(10L, 0L, -1L, 20L)))
    }

    @Test
    fun missingSpace_keepsMarginFree() {
        val margin = 100L
        assertEquals(0L, missingSpace(allocatable = 1_000, requested = 900, margin = margin))
        assertEquals(1L, missingSpace(allocatable = 1_000, requested = 901, margin = margin))
        assertEquals(200L, missingSpace(allocatable = 0, requested = 100, margin = margin))
        // Valeurs incohérentes (négatives) : jamais de « manque » négatif.
        assertEquals(0L, missingSpace(allocatable = 5_000, requested = -10, margin = margin))
        assertEquals(OFFLINE_SPACE_MARGIN_BYTES, missingSpace(allocatable = 0, requested = 0))
    }

    @Test
    fun largeBatch_above200ItemsOr1GB() {
        assertFalse(isLargeBatch(200, 1_000_000_000L))
        assertTrue(isLargeBatch(201, 1L))
        assertTrue(isLargeBatch(1, 1_000_000_001L))
        assertFalse(isLargeBatch(1, 0L))
    }

    // --- Réseau du travail -------------------------------------------------------------------

    @Test
    fun jobNetwork_wifiOnlyUnlessDownloadNow() {
        assertEquals(JobNetwork.UNMETERED, jobNetworkFor(wifiOnly = true, overrideNow = false))
        assertEquals(JobNetwork.ANY, jobNetworkFor(wifiOnly = true, overrideNow = true))
        assertEquals(JobNetwork.ANY, jobNetworkFor(wifiOnly = false, overrideNow = false))
        assertEquals(JobNetwork.ANY, jobNetworkFor(wifiOnly = false, overrideNow = true))
    }

    // --- Nouvelle version sur OneDrive ---------------------------------------------------------

    @Test
    fun cTagChange_requeuesOnlyKeptItems() {
        assertTrue(requeueOnCTagChange(hasOfflineCopy = true, oldCTag = "c1", newCTag = "c2"))
        assertFalse(requeueOnCTagChange(hasOfflineCopy = true, oldCTag = "c1", newCTag = "c1"))
        assertFalse(requeueOnCTagChange(hasOfflineCopy = false, oldCTag = "c1", newCTag = "c2"))
        // Version inconnue de la copie : nouvelle version connue -> téléchargement.
        assertTrue(requeueOnCTagChange(hasOfflineCopy = true, oldCTag = null, newCTag = "c2"))
        // Nouvelle version inconnue : rien à comparer, la copie est gardée.
        assertFalse(requeueOnCTagChange(hasOfflineCopy = true, oldCTag = "c1", newCTag = null))
    }

    @Test
    fun contentMatches_usesCTagWhenBothKnown_elseSizeAndETag() {
        assertTrue(contentMatches("c1", 10, "e1", "c1", 99, "e9"))
        assertFalse(contentMatches("c1", 10, "e1", "c2", 10, "e1"))
        // Une version inconnue n'est jamais « identique » par défaut (P1-11) : taille exigée.
        assertTrue(contentMatches(null, 10, null, "c1", 10, null))
        assertFalse(contentMatches(null, 10, null, "c1", 11, null))
        assertTrue(contentMatches("c1", 10, "e1", null, 10, "e1"))
        assertFalse(contentMatches("c1", 10, "e1", null, 10, "e2"))
    }

    // --- Etat affiché ----------------------------------------------------------------------------

    @Test
    fun status_withoutTask_dependsOnCopy() {
        assertEquals(OfflineStatus.NONE, offlineStatus(null, null, 0, 0, hasOffline = false, waitingForUnmetered = true).status)
        assertEquals(OfflineStatus.AVAILABLE, offlineStatus(null, null, 0, 0, hasOffline = true, waitingForUnmetered = false).status)
    }

    @Test
    fun status_queuedAndRunning_withProgressAndWifiWait() {
        val queued = offlineStatus(TaskState.QUEUED, null, 0, 100, hasOffline = false, waitingForUnmetered = true)
        assertEquals(OfflineStatus.QUEUED, queued.status)
        assertTrue(queued.waitingForUnmetered)
        assertTrue(queued.active)
        val running = offlineStatus(TaskState.RUNNING, null, 40, 100, hasOffline = false, waitingForUnmetered = true)
        assertEquals(OfflineStatus.DOWNLOADING, running.status)
        assertEquals(40L, running.bytesDone)
        // En cours de téléchargement : pas « en attente du Wi-Fi ».
        assertFalse(running.waitingForUnmetered)
        // Copie présente : mise à jour.
        assertEquals(OfflineStatus.UPDATING, offlineStatus(TaskState.QUEUED, null, 0, 1, hasOffline = true, waitingForUnmetered = false).status)
    }

    @Test
    fun status_pausedAndFailed() {
        val paused = offlineStatus(TaskState.PAUSED, null, 5, 10, hasOffline = false, waitingForUnmetered = false)
        assertEquals(OfflineStatus.PAUSED, paused.status)
        assertTrue(paused.stopped)
        val failed = offlineStatus(TaskState.FAILED, DownloadError.NOT_FOUND, 0, 10, hasOffline = false, waitingForUnmetered = false)
        assertEquals(OfflineStatus.FAILED, failed.status)
        assertEquals(DownloadError.NOT_FOUND, failed.error)
        // Echec sans code lisible : erreur générique.
        assertEquals(DownloadError.SERVER, offlineStatus(TaskState.FAILED, null, 0, 0, false, false).error)
        // Mise à jour arrêtée : la copie présente reste servie, mais l'arrêt est signalé (avec sa
        // cause) et l'élément peut être repris ou annulé.
        val updatePaused = offlineStatus(TaskState.PAUSED, null, 3, 10, hasOffline = true, waitingForUnmetered = false)
        assertEquals(OfflineStatus.UPDATE_STOPPED, updatePaused.status)
        assertNull(updatePaused.error)
        assertTrue(updatePaused.stopped)
        assertFalse(updatePaused.active)
        val updateFailed = offlineStatus(TaskState.FAILED, DownloadError.NETWORK, 0, 0, true, false)
        assertEquals(OfflineStatus.UPDATE_STOPPED, updateFailed.status)
        assertEquals(DownloadError.NETWORK, updateFailed.error)
        assertTrue(updateFailed.stopped)
        assertEquals(DownloadError.SERVER, offlineStatus(TaskState.FAILED, null, 0, 0, true, false).error)
    }

    // --- Echecs ----------------------------------------------------------------------------------

    @Test
    fun failureAction_transientErrorsRetryThenFail() {
        for (error in listOf(DownloadError.NETWORK, DownloadError.THROTTLED, DownloadError.SERVER, DownloadError.SIZE_MISMATCH)) {
            assertEquals(error.name, FailureAction.RETRY_LATER, failureAction(error, 1))
            assertEquals(error.name, FailureAction.RETRY_LATER, failureAction(error, MAX_DOWNLOAD_ATTEMPTS - 1))
            assertEquals(error.name, FailureAction.FAIL, failureAction(error, MAX_DOWNLOAD_ATTEMPTS))
        }
    }

    @Test
    fun failureAction_contentChanged_waitsForTheIndex_neverFails() {
        assertEquals(FailureAction.WAIT_FOR_INDEX, failureAction(DownloadError.CONTENT_CHANGED, 0))
        assertEquals(FailureAction.WAIT_FOR_INDEX, failureAction(DownloadError.CONTENT_CHANGED, MAX_DOWNLOAD_ATTEMPTS + 3))
    }

    @Test
    fun countsAsAttempt_notForIndexLag_norForNetworkDropsAfterProgress() {
        assertFalse(countsAsAttempt(DownloadError.CONTENT_CHANGED, progressed = false))
        assertFalse(countsAsAttempt(DownloadError.NETWORK, progressed = true))
        assertTrue(countsAsAttempt(DownloadError.NETWORK, progressed = false))
        assertTrue(countsAsAttempt(DownloadError.SERVER, progressed = true))
        assertTrue(countsAsAttempt(DownloadError.SIZE_MISMATCH, progressed = true))
    }

    @Test
    fun failureAction_accountAndSpaceStopTheWholeQueue() {
        assertEquals(FailureAction.FAIL_ALL, failureAction(DownloadError.AUTH_REQUIRED, 1))
        assertEquals(FailureAction.FAIL_ALL, failureAction(DownloadError.NO_SPACE, 1))
        assertEquals(FailureAction.FAIL, failureAction(DownloadError.NOT_FOUND, 1))
        assertEquals(FailureAction.FAIL, failureAction(DownloadError.CANCELLED, 1))
    }

    @Test
    fun downloadError_parsedTolerantly() {
        assertEquals(DownloadError.NO_SPACE, downloadErrorOrNull("NO_SPACE"))
        assertNull(downloadErrorOrNull("http://exemple"))
        assertNull(downloadErrorOrNull(null))
    }

    // --- Arrêt du travail ------------------------------------------------------------------------

    @Test
    fun stopReason_userPauses_appCancels_otherReschedules() {
        assertEquals(StopAction.PAUSE, stopActionFor(JobParameters.STOP_REASON_USER))
        assertEquals(StopAction.NONE, stopActionFor(JobParameters.STOP_REASON_CANCELLED_BY_APP))
        assertEquals(StopAction.RESCHEDULE, stopActionFor(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals(StopAction.RESCHEDULE, stopActionFor(JobParameters.STOP_REASON_TIMEOUT))
    }

    // --- Décision D1 : fin du délai de grâce -------------------------------------------------------

    @Test
    fun graceExpiry_keepsItemsWithOfflineCopyForUserDecision() {
        val withCopy = CloudStamp(sourceId = 1, mediaId = 10, externalId = "A", missingSince = 0)
        val without = CloudStamp(sourceId = 2, mediaId = 20, externalId = "B", missingSince = 0)
        val plan = planGraceExpiry(listOf(withCopy, without), mediaWithOfflineCopy = setOf(10L, 99L))
        assertEquals(listOf(without), plan.purge)
        assertEquals(listOf(withCopy), plan.awaitingDecision)
    }

    @Test
    fun graceExpiry_withoutOfflineCopies_purgesAsBefore() {
        val stamps = listOf(CloudStamp(1, 10, "A", 0), CloudStamp(2, 20, "B", 0))
        val plan = planGraceExpiry(stamps, emptySet())
        assertEquals(stamps, plan.purge)
        assertTrue(plan.awaitingDecision.isEmpty())
    }

    @Test
    fun releaseConfirmation_onlyForLastCopies() {
        assertFalse(releaseNeedsConfirmation(0))
        assertTrue(releaseNeedsConfirmation(1))
    }

    // --- Noms de fichiers ------------------------------------------------------------------------

    @Test
    fun storeNames_rejectPathTraversal() {
        assertTrue(OfflineStore.isSafeName("0123456789abcdef_01234567.jpg"))
        assertFalse(OfflineStore.isSafeName(""))
        assertFalse(OfflineStore.isSafeName("."))
        assertFalse(OfflineStore.isSafeName(".."))
        assertFalse(OfflineStore.isSafeName("../x.jpg"))
        assertFalse(OfflineStore.isSafeName("a/b.jpg"))
        assertFalse(OfflineStore.isSafeName("a\\b.jpg"))
    }

    // --- Espace disque ---------------------------------------------------------------------------

    @Test
    fun spaceAction_allocatesOnlyWhenFreeSpaceIsShortButObtainable() {
        val margin = 100L
        // Espace libre suffisant : aucune demande au système.
        assertEquals(SpaceAction.ENOUGH, spaceAction(usable = 1_000, allocatable = 4_000, needed = 900, margin = margin))
        // 1 Go libre, 3 Go de cache d'autres applis, 2 Go demandés : allocateBytes nécessaire.
        assertEquals(SpaceAction.ALLOCATE, spaceAction(usable = 1_000, allocatable = 4_000, needed = 2_000, margin = margin))
        // Même en libérant le cache, l'espace manque : jamais d'appel voué à l'échec.
        assertEquals(SpaceAction.NO_SPACE, spaceAction(usable = 1_000, allocatable = 2_000, needed = 2_000, margin = margin))
    }

    // --- Réseau (« Wi-Fi uniquement », « Télécharger maintenant ») -------------------------------

    @Test
    fun allowedOnNetwork_overrideIsLimitedToItsTasks() {
        assertNull(allowedOnNetwork(wifiOnly = false, unmeteredNetwork = false, overridden = emptySet()))
        assertNull(allowedOnNetwork(wifiOnly = true, unmeteredNetwork = true, overridden = emptySet()))
        assertEquals(setOf(1L, 2L), allowedOnNetwork(wifiOnly = true, unmeteredNetwork = false, overridden = setOf(1L, 2L)))
        assertEquals(emptySet<Long>(), allowedOnNetwork(wifiOnly = true, unmeteredNetwork = false, overridden = emptySet()))
    }

    @Test
    fun waitsForUnmetered_onlyTasksOutsideTheOverride() {
        assertTrue(waitsForUnmetered(wifiOnly = true, overridden = false, connected = true, unmetered = false))
        assertTrue(waitsForUnmetered(wifiOnly = true, overridden = false, connected = false, unmetered = false))
        assertFalse(waitsForUnmetered(wifiOnly = true, overridden = true, connected = true, unmetered = false))
        assertFalse(waitsForUnmetered(wifiOnly = true, overridden = false, connected = true, unmetered = true))
        assertFalse(waitsForUnmetered(wifiOnly = false, overridden = false, connected = true, unmetered = false))
    }

    @Test
    fun jobCovers_anyJobDoesNotCoverWifiOnlyTasks() {
        assertTrue(jobCovers(active = JobNetwork.UNMETERED, required = JobNetwork.UNMETERED))
        assertTrue(jobCovers(active = JobNetwork.UNMETERED, required = JobNetwork.ANY))
        assertTrue(jobCovers(active = JobNetwork.ANY, required = JobNetwork.ANY))
        assertFalse(jobCovers(active = JobNetwork.ANY, required = JobNetwork.UNMETERED))
    }

    // --- Reprise au démarrage ---------------------------------------------------------------------

    @Test
    fun startupRecovery_pausesOnlyAfterAUserStopDuringADownload() {
        assertEquals(StartupRecovery.PAUSE, startupRecovery(firstStart = true, userRequestedExit = true, hadRunningTasks = true))
        // Balayage depuis les applis récentes sans téléchargement en cours.
        assertEquals(StartupRecovery.REQUEUE, startupRecovery(firstStart = true, userRequestedExit = true, hadRunningTasks = false))
        assertEquals(StartupRecovery.REQUEUE, startupRecovery(firstStart = true, userRequestedExit = false, hadRunningTasks = true))
        assertEquals(StartupRecovery.REQUEUE, startupRecovery(firstStart = false, userRequestedExit = true, hadRunningTasks = true))
    }
}
