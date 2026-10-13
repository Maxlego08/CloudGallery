package fr.eva.cloudgallery.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Offline copy queue (`download_task`) and OFFLINE_COPY sources. */
@RunWith(AndroidJUnit4::class)
class OfflineDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun media(name: String) = MediaEntity(
        mediaType = MediaType.IMAGE, displayName = name, mimeType = "image/jpeg", dateTaken = 1, dateAdded = 1,
        dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 1, orientation = 0,
        primaryUri = null, hasLocal = false, hasCloud = false, hasOffline = false,
    )

    private fun task(mediaId: Long, itemId: String, cTag: String?, createdAt: Long = 0) = DownloadTaskEntity(
        mediaId = mediaId, sourceId = null, state = DownloadState.QUEUED, bytesDownloaded = 0, bytesTotal = 10,
        keepOffline = true, createdAt = createdAt, updatedAt = createdAt, cloudItemId = itemId, cTag = cTag,
    )

    private fun source(mediaId: Long, kind: SourceKind, externalId: String, missingSince: Long? = null, cTag: String? = "c1") =
        MediaSourceEntity(
            mediaId = mediaId, kind = kind, externalId = externalId,
            uri = if (kind == SourceKind.LOCAL) "content://media/external/images/media/$externalId" else null,
            bucketKey = if (kind == SourceKind.ONEDRIVE) "onedrive:ROOT" else null, bucketName = null, relativePath = null,
            sizeBytes = 10, dateModified = 1, cTag = cTag, missingSince = missingSince,
        )

    // --- Queue --------------------------------------------------------------------------------

    @Test
    fun enqueueIfAbsent_neverCreatesTwoOpenTasksForAMedia() = runTest {
        val dao = db.downloadTaskDao()
        val id = db.mediaDao().insert(media("a"))
        assertEquals(1, dao.enqueueIfAbsent(listOf(task(id, "A", "c1"))))
        // Same version already queued: nothing more.
        assertEquals(0, dao.enqueueIfAbsent(listOf(task(id, "A", "c1"))))
        assertEquals(1, dao.listOpen().size)
        // New version: the same row is requeued with the new version.
        assertEquals(1, dao.enqueueIfAbsent(listOf(task(id, "A", "c2"))))
        val open = dao.listOpen().single()
        assertEquals("c2", open.cTag)
        assertEquals(DownloadState.QUEUED, open.state)
    }

    @Test
    fun enqueueIfAbsent_requeuesPausedAndFailed_keepsRunning() = runTest {
        val dao = db.downloadTaskDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        dao.enqueueIfAbsent(listOf(task(a, "A", "c1"), task(b, "B", "c1")))
        val taskA = dao.listOpen().first { it.mediaId == a }
        val taskB = dao.listOpen().first { it.mediaId == b }
        dao.markFailed(taskA.id, "NETWORK", 5, 1)
        dao.markRunning(taskB.id, 1)
        assertEquals(1, dao.enqueueIfAbsent(listOf(task(a, "A", "c1"), task(b, "B", "c1"))))
        val reloadedA = dao.getById(taskA.id)!!
        assertEquals(DownloadState.QUEUED, reloadedA.state)
        assertNull(reloadedA.error)
        assertEquals(0, reloadedA.attempts)
        assertEquals(DownloadState.RUNNING, dao.getById(taskB.id)!!.state)
    }

    @Test
    fun nextQueued_followsCreationOrder() = runTest {
        val dao = db.downloadTaskDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        dao.enqueueIfAbsent(listOf(task(b, "B", "c1", createdAt = 20), task(a, "A", "c1", createdAt = 10)))
        assertEquals(a, dao.nextQueued()!!.mediaId)
        dao.markRunning(dao.nextQueued()!!.id, 30)
        assertEquals(b, dao.nextQueued()!!.mediaId)
    }

    @Test
    fun recovery_runningBackToQueued_andUserPause() = runTest {
        val dao = db.downloadTaskDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        dao.enqueueIfAbsent(listOf(task(a, "A", "c1"), task(b, "B", "c1")))
        dao.markRunning(dao.nextQueued()!!.id, 1)
        assertEquals(1, dao.resetRunningToQueued(2))
        assertTrue(dao.listOpen().all { it.state == DownloadState.QUEUED })
        // User stop: the whole queue paused, nothing left "queued".
        dao.markRunning(dao.nextQueued()!!.id, 3)
        assertEquals(2, dao.pauseActive(4))
        assertNull(dao.nextQueued())
        assertFalse(dao.existsQueued())
        // Explicit resume.
        assertEquals(2, dao.resumeStopped(5))
        assertTrue(dao.existsQueued())
    }

    @Test
    fun tasksFollowTheirMedia_cascade() = runTest {
        val dao = db.downloadTaskDao()
        val a = db.mediaDao().insert(media("a"))
        dao.enqueueIfAbsent(listOf(task(a, "A", "c1")))
        db.mediaDao().deleteByIds(listOf(a))
        assertFalse(dao.existsAny())
    }

    // --- Sources OFFLINE_COPY -------------------------------------------------------------------

    @Test
    fun offlineCopy_setsHasOffline_neverPrimaryUri_andShowsInLibrary() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val id = mediaDao.insert(media("pinned"))
        sourceDao.insert(source(id, SourceKind.ONEDRIVE, "A"))
        sourceDao.insert(source(id, SourceKind.OFFLINE_COPY, "A"))
        mediaDao.refreshSourceFlags(listOf(id))
        val m = mediaDao.getById(id)!!
        assertTrue(m.hasOffline)
        assertTrue(m.hasCloud)
        assertNull(m.primaryUri)
        // "Show OneDrive in Photos" off: an item kept offline still appears there.
        assertEquals(listOf(id), mediaDao.observeTimelineIds(includeCloudOnly = false).first())
        assertEquals(1, mediaDao.countWithOfflineCopy(listOf(id)))
        assertEquals(0, mediaDao.countKeepableOffline(listOf(id)))
        assertEquals(0, mediaDao.countWithLocalFile(listOf(id)))
    }

    @Test
    fun offlineCopyVisibility_followsOneDriveSource() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val id = mediaDao.insert(media("a"))
        val cloud = sourceDao.insert(source(id, SourceKind.ONEDRIVE, "A"))
        sourceDao.insert(source(id, SourceKind.OFFLINE_COPY, "A"))
        sourceDao.markMissing(listOf(cloud), 50)
        sourceDao.syncOfflineCopyVisibility(listOf(id))
        mediaDao.refreshSourceFlags(listOf(id))
        assertEquals(50L, sourceDao.find(SourceKind.OFFLINE_COPY, "A")!!.missingSince)
        assertFalse(mediaDao.getById(id)!!.hasOffline)
        assertTrue(mediaDao.observeTimelineIds(includeCloudOnly = false).first().isEmpty())
        // No OneDrive source anymore (kept copy): visible.
        sourceDao.deleteForMedia(SourceKind.ONEDRIVE, listOf(id))
        sourceDao.syncOfflineCopyVisibility(listOf(id))
        mediaDao.refreshSourceFlags(listOf(id))
        assertNull(sourceDao.find(SourceKind.OFFLINE_COPY, "A")!!.missingSince)
        assertTrue(mediaDao.getById(id)!!.hasOffline)
    }

    @Test
    fun lastCopies_andAwaitingDecision() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        // Deleted from OneDrive long ago, offline copy: awaiting a decision.
        val old = mediaDao.insert(media("old"))
        sourceDao.insert(source(old, SourceKind.ONEDRIVE, "A", missingSince = 10))
        sourceDao.insert(source(old, SourceKind.OFFLINE_COPY, "A", missingSince = 10))
        // Deleted recently: within the grace period.
        val recent = mediaDao.insert(media("recent"))
        sourceDao.insert(source(recent, SourceKind.ONEDRIVE, "B", missingSince = 1_000))
        sourceDao.insert(source(recent, SourceKind.OFFLINE_COPY, "B", missingSince = 1_000))
        // Still on OneDrive.
        val alive = mediaDao.insert(media("alive"))
        sourceDao.insert(source(alive, SourceKind.ONEDRIVE, "C"))
        sourceDao.insert(source(alive, SourceKind.OFFLINE_COPY, "C"))
        // Also on the device: never a last copy.
        val local = mediaDao.insert(media("local"))
        sourceDao.insert(source(local, SourceKind.LOCAL, "9"))
        sourceDao.insert(source(local, SourceKind.ONEDRIVE, "D", missingSince = 10))
        sourceDao.insert(source(local, SourceKind.OFFLINE_COPY, "D", missingSince = 10))
        mediaDao.refreshSourceFlags(listOf(old, recent, alive, local))

        assertEquals(listOf(old, local), sourceDao.awaitingDecisionMediaIds(expiredBefore = 100).sorted())
        assertEquals(listOf(old, local), sourceDao.observeAwaitingDecisionMediaIds(expiredBefore = 100).first().sorted())
        assertEquals(2, sourceDao.countOfflineLastCopies())
        assertEquals(4, sourceDao.countAll(SourceKind.OFFLINE_COPY))
        // "Keep offline" candidates: visible OneDrive items without a copy.
        val fresh = mediaDao.insert(media("fresh"))
        sourceDao.insert(source(fresh, SourceKind.ONEDRIVE, "E", cTag = "e1"))
        val candidates = sourceDao.keepOfflineCandidates(listOf(old, recent, alive, local, fresh))
        assertEquals(listOf(fresh), candidates.map { it.mediaId })
        assertEquals("E", candidates.single().itemId)
        assertEquals("e1", candidates.single().cTag)
    }

    @Test
    fun lastCopiesAmong_countsHiddenCopies_andDecisionIsRecheckedPerId() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val old = mediaDao.insert(media("old"))
        sourceDao.insert(source(old, SourceKind.ONEDRIVE, "A", missingSince = 10))
        sourceDao.insert(source(old, SourceKind.OFFLINE_COPY, "A", missingSince = 10))
        // Hidden during the grace period: its (hidden) copy is already the last one.
        val recent = mediaDao.insert(media("recent"))
        sourceDao.insert(source(recent, SourceKind.ONEDRIVE, "B", missingSince = 1_000))
        sourceDao.insert(source(recent, SourceKind.OFFLINE_COPY, "B", missingSince = 1_000))
        val alive = mediaDao.insert(media("alive"))
        sourceDao.insert(source(alive, SourceKind.ONEDRIVE, "C"))
        sourceDao.insert(source(alive, SourceKind.OFFLINE_COPY, "C"))
        mediaDao.refreshSourceFlags(listOf(old, recent, alive))

        assertEquals(1, sourceDao.countOfflineLastCopiesAmong(listOf(recent)))
        assertEquals(2, sourceDao.countOfflineLastCopiesAmong(listOf(old, recent, alive)))
        assertEquals(0, sourceDao.countOfflineLastCopiesAmong(listOf(alive)))

        // Decision: only confirmed items still awaiting.
        assertEquals(listOf(old), sourceDao.awaitingDecisionAmong(listOf(old, recent, alive), expiredBefore = 100))
        // Removed only if hidden: the visible source of "alive" remains.
        sourceDao.deleteHiddenForMedia(SourceKind.ONEDRIVE, listOf(old, alive))
        assertNull(sourceDao.find(SourceKind.ONEDRIVE, "A"))
        assertEquals(alive, sourceDao.find(SourceKind.ONEDRIVE, "C")!!.mediaId)
    }

    @Test
    fun runnableTasks_skipContentChangedUntilTheIndexMoves() = runTest {
        val dao = db.downloadTaskDao()
        val sourceDao = db.mediaSourceDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        sourceDao.insert(source(a, SourceKind.ONEDRIVE, "A", cTag = "c1"))
        sourceDao.insert(source(b, SourceKind.ONEDRIVE, "B", cTag = "c1"))
        dao.enqueueIfAbsent(listOf(task(a, "A", "c1", createdAt = 1), task(b, "B", "c1", createdAt = 2)))
        val taskA = dao.listOpen().first { it.mediaId == a }
        val taskB = dao.listOpen().first { it.mediaId == b }
        // A: newer content on OneDrive than in the index, no attempt counted.
        dao.markRunning(taskA.id, 3)
        dao.requeue(taskA.id, "CONTENT_CHANGED", 0, 4)
        assertEquals(taskB.id, dao.nextRunnable(listOf(-1))!!.id)
        assertTrue(dao.existsRunnable())
        assertNull(dao.nextRunnable(listOf(taskB.id)))
        assertNull(dao.nextRunnableAmong(listOf(taskA.id), listOf(-1)))
        assertFalse(dao.existsRunnableAmong(listOf(taskA.id)))
        // Sync: new version in the index, A restarts (first, as the oldest).
        val cloudA = sourceDao.find(SourceKind.ONEDRIVE, "A")!!
        sourceDao.update(cloudA.copy(cTag = "c2"))
        assertEquals(taskA.id, dao.nextRunnable(listOf(-1))!!.id)
        assertEquals(taskA.id, dao.nextRunnableAmong(listOf(taskA.id), listOf(-1))!!.id)
        // Progress of a vanished task: no row touched.
        dao.markRunning(taskB.id, 5)
        assertEquals(1, dao.updateProgress(taskB.id, 4, 10, 6))
        dao.deleteForMedia(listOf(b))
        assertEquals(0, dao.updateProgress(taskB.id, 5, 10, 7))
    }

    @Test
    fun keepable_excludesItemsAlreadyDownloading_andStoppedUpdatesAreCandidates() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val dao = db.downloadTaskDao()
        val queued = mediaDao.insert(media("queued"))
        val failed = mediaDao.insert(media("failed"))
        val pinned = mediaDao.insert(media("pinned"))
        sourceDao.insert(source(queued, SourceKind.ONEDRIVE, "Q"))
        sourceDao.insert(source(failed, SourceKind.ONEDRIVE, "F"))
        sourceDao.insert(source(pinned, SourceKind.ONEDRIVE, "P", cTag = "c2"))
        sourceDao.insert(source(pinned, SourceKind.OFFLINE_COPY, "P", cTag = "c1"))
        mediaDao.refreshSourceFlags(listOf(queued, failed, pinned))
        dao.enqueueIfAbsent(listOf(task(queued, "Q", "c1"), task(failed, "F", "c1"), task(pinned, "P", "c2")))
        dao.markFailed(dao.listOpen().first { it.mediaId == failed }.id, "NETWORK", 5, 1)
        // Queued: no longer offered; failed: offered ("Keep offline" restarts it).
        assertEquals(1, mediaDao.countKeepableOffline(listOf(queued, failed)))
        // Stopped update of an existing copy: candidate for resuming.
        assertTrue(sourceDao.stoppedUpdateCandidates(listOf(pinned)).isEmpty())
        dao.pauseActive(2)
        assertEquals(listOf(pinned), sourceDao.stoppedUpdateCandidates(listOf(queued, failed, pinned)).map { it.mediaId })
    }
}

